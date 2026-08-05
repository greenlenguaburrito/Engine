// coordinator/src/main.rs
// Watch-mode coordinator with HTTP admin endpoints and job queue.
// Build: cd coordinator && cargo build --release
// Run (watch mode): ./target/release/coordinator --watch --manifests-dir ./manifests --storage local:///shared/tiles --out /results/final --workdir /tmp/work --upload local:///uploads --admin-token <token>

use anyhow::Context;
use clap::Parser;
use rand::Rng;
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::fs;
use std::io::{BufReader, Read};
use std::net::SocketAddr;
use std::path::{Path, PathBuf};
use std::process::Command;
use std::sync::mpsc::channel;
use std::time::Duration;
use tokio::sync::Semaphore;
use tokio::time::sleep;
use tokio::io::AsyncReadExt;
use walkdir::WalkDir;
use warp::Filter;
use log::{info, warn, error, debug};

#[derive(Parser, Debug)]
struct Cli {
    /// One-off manifest file (mutually exclusive with --watch)
    #[arg(long)]
    manifest: Option<String>,

    /// Run in watch mode and monitor manifests dir
    #[arg(long, default_value_t = false)]
    watch: bool,

    /// Directory containing manifests (when --watch)
    #[arg(long, default_value = "./manifests")]
    manifests_dir: String,

    /// Storage base (local://...)
    #[arg(long)]
    storage: String,

    /// Output prefix for result (final will be <out>.<job>.tiff)
    #[arg(long)]
    out: String,

    /// Work directory
    #[arg(long, default_value = "/tmp/tiles_workdir")]
    workdir: String,

    /// Upload destination (local://...)
    #[arg(long)]
    upload: String,

    /// concurrency
    #[arg(long, default_value_t = 8)]
    concurrency: usize,

    /// max attempts per tile
    #[arg(long, default_value_t = 5)]
    max_attempts: u32,

    /// base backoff seconds
    #[arg(long, default_value_t = 2)]
    backoff_base: u64,

    /// upload copy verify attempts
    #[arg(long, default_value_t = 5)]
    upload_max_attempts: u32,

    /// admin http bind, e.g. 0.0.0.0:3030
    #[arg(long, default_value = "127.0.0.1:3030")]
    admin_bind: String,

    /// Optional admin token; if set, admin endpoints require this token via Authorization: Bearer <token> or X-Admin-Token header
    #[arg(long, default_value = "")]
    admin_token: String,
}

#[derive(Serialize, Deserialize, Debug, Clone)]
struct ManifestEntry {
    index: usize,
    col: u32,
    row: u32,
    filename: String,
    offset: (u32, u32),
    resolution: (u32, u32),
    sha256: Option<String>,
}

#[derive(Debug)]
struct Unauthorized;
impl warp::reject::Reject for Unauthorized {}

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    env_logger::init();
    let cli = Cli::parse();

    if !cli.storage.starts_with("local://") {
        anyhow::bail!("Storage must be local:// for self-hosted coordinator");
    }
    if !cli.upload.starts_with("local://") {
        anyhow::bail!("Upload must be local:// for self-hosted coordinator");
    }

    fs::create_dir_all(&cli.workdir)?;
    if cli.watch {
        fs::create_dir_all(&cli.manifests_dir)?;
    }

    // spawn admin HTTP server
    let manifests_dir = cli.manifests_dir.clone();
    let admin_bind = cli.admin_bind.clone();
    let admin_token = cli.admin_token.clone();
    tokio::spawn(async move {
        if let Err(e) = run_admin_server(manifests_dir, admin_bind, admin_token).await {
            error!("Admin server error: {}", e);
        }
    });

    // if single manifest provided, process and exit
    if let Some(manifest_path) = cli.manifest.as_ref() {
        process_manifest(PathBuf::from(manifest_path), &cli).await?;
        return Ok(());
    }

    if cli.watch {
        run_watch_loop(&cli).await?;
    } else {
        anyhow::bail!("Either --manifest or --watch must be provided");
    }

    Ok(())
}

async fn run_admin_server(manifests_dir: String, bind: String, admin_token: String) -> anyhow::Result<()> {
    // Create an auth filter: if admin_token is empty, allow all; otherwise require header
    let token = admin_token.clone();
    let auth_filter = if token.is_empty() {
        warp::any().map(|| ()).boxed()
    } else {
        let t = token.clone();
        warp::header::optional::<String>("authorization")
            .and(warp::header::optional::<String>("x-admin-token"))
            .and_then(move |auth: Option<String>, x: Option<String>| {
                let t = t.clone();
                async move {
                    let ok = if let Some(a) = auth {
                        let low = a.to_lowercase();
                        if low.starts_with("bearer ") { a[7..].trim() == t } else { a.trim() == t }
                    } else if let Some(xv) = x { xv == t } else { false };
                    if ok { Ok::<(), warp::Rejection>(()) } else { Err(warp::reject::custom(Unauthorized)) }
                }
            })
            .boxed()
    };

    let dir = manifests_dir.clone();
    let dir_jobs = manifests_dir.clone();
    let dir_reproc = manifests_dir.clone();

    // status route returns serialized JSON string to avoid borrow issues
    let status_route = warp::path!("admin" / "status")
        .and(auth_filter.clone())
        .map(move |_u: ()| {
            let v = gather_status(&dir);
            let s = serde_json::to_string(&v).unwrap_or_else(|_| "{}".to_string());
            warp::reply::with_header(s, "content-type", "application/json")
        });

    // jobs route
    let jobs_route = warp::path!("admin" / "jobs")
        .and(auth_filter.clone())
        .map(move |_u: ()| {
            let v = list_jobs(&dir_jobs);
            let s = serde_json::to_string(&v).unwrap_or_else(|_| "{}".to_string());
            warp::reply::with_header(s, "content-type", "application/json")
        });

    // reprocess route
    let reproc_route = warp::path!("admin" / "reprocess" / String)
        .and(warp::post())
        .and(auth_filter.clone())
        .and_then(move |name: String, _u: ()| {
            let d = dir_reproc.clone();
            async move {
                match reprocess_manifest(&d, &name) {
                    Ok(_) => Ok::<_, warp::Rejection>(warp::reply::with_status("ok", warp::http::StatusCode::OK)),
                    Err(err) => {
                        let msg = format!("error: {}", err);
                        Ok(warp::reply::with_status(msg, warp::http::StatusCode::INTERNAL_SERVER_ERROR))
                    }
                }
            }
        });

    let routes = status_route.or(jobs_route).or(reproc_route)
        .recover(|err: warp::Rejection| async move {
            if err.find::<Unauthorized>().is_some() {
                Ok::<_, warp::Rejection>(warp::reply::with_status("unauthorized", warp::http::StatusCode::UNAUTHORIZED))
            } else {
                Err(err)
            }
        });

    let addr: SocketAddr = bind.parse().context("Invalid admin bind address")?;
    info!("Admin server listening on http://{}", addr);
    warp::serve(routes).run(addr).await;
    Ok(())
}

fn gather_status(manifests_dir: &str) -> serde_json::Value {
    let queue = Path::new(manifests_dir).join("queue");
    let processing = Path::new(manifests_dir).join("processing");
    let processed = Path::new(manifests_dir).join("processed");
    let failed = Path::new(manifests_dir).join("failed");
    let q = count_files_in(&queue);
    let p = count_files_in(&processing);
    let pc = count_files_in(&processed);
    let f = count_files_in(&failed);
    serde_json::json!({"queue": q, "processing": p, "processed": pc, "failed": f})
}

fn count_files_in(path: &Path) -> usize {
    if !path.exists() { return 0; }
    match fs::read_dir(path) {
        Ok(rd) => rd.filter_map(|e| e.ok()).filter(|e| e.path().is_file()).count(),
        Err(_) => 0,
    }
}

fn list_jobs(manifests_dir: &str) -> serde_json::Value {
    let mut result = serde_json::Map::new();
    for state in ["queue", "processing", "processed", "failed"] {
        let dir = Path::new(manifests_dir).join(state);
        let mut arr = Vec::new();
        if dir.exists() {
            if let Ok(rd) = fs::read_dir(&dir) {
                for entry in rd.filter_map(|e| e.ok()) {
                    let p = entry.path();
                    if p.is_file() {
                        arr.push(entry.file_name().to_string_lossy().to_string());
                    }
                }
            }
        }
        result.insert(state.to_string(), serde_json::Value::Array(arr.into_iter().map(serde_json::Value::String).collect()));
    }
    serde_json::Value::Object(result)
}

fn reprocess_manifest(manifests_dir: &str, name: &str) -> anyhow::Result<()> {
    let failed = Path::new(manifests_dir).join("failed").join(name);
    let queue = Path::new(manifests_dir).join("queue").join(name);
    if !failed.exists() { anyhow::bail!("Failed manifest not found: {}", failed.display()); }
    atomic_move(&failed, &queue).context("Failed to move manifest back to queue")?;
    info!("Requeued manifest {}", name);
    Ok(())
}

async fn run_watch_loop(cli: &Cli) -> anyhow::Result<()> {
    let manifests_dir = Path::new(&cli.manifests_dir);
    let queue_dir = manifests_dir.join("queue");
    let processing_dir = manifests_dir.join("processing");
    let processed_dir = manifests_dir.join("processed");
    let failed_dir = manifests_dir.join("failed");
    fs::create_dir_all(&queue_dir)?;
    fs::create_dir_all(&processing_dir)?;
    fs::create_dir_all(&processed_dir)?;
    fs::create_dir_all(&failed_dir)?;

    // enqueue existing manifests
    for entry in WalkDir::new(manifests_dir).max_depth(1).into_iter().filter_map(|e| e.ok()) {
        let p = entry.path();
        if p.is_file() {
            if let Some(ext) = p.extension() {
                if ext == "json" {
                    let fname = p.file_name().unwrap().to_string_lossy().to_string();
                    let queued = queue_dir.join(&fname);
                    if !queued.exists() {
                        atomic_move(p, &queued).ok();
                        info!("Enqueued existing manifest {}", fname);
                    }
                }
            }
        }
    }

    // create notify watcher
    let (tx, rx) = channel();
    let mut watcher: notify::RecommendedWatcher = notify::RecommendedWatcher::new(tx, notify::Config::default())?;
    watcher.watch(manifests_dir, notify::RecursiveMode::NonRecursive)?;

    loop {
        // process any queued manifests
        for entry in fs::read_dir(&queue_dir)? {
            let path = entry?.path();
            if path.is_file() {
                let fname = path.file_name().unwrap().to_string_lossy().to_string();
                let processing_path = processing_dir.join(&fname);
                if atomic_move(&path, &processing_path).is_ok() {
                    info!("Picked job {}", fname);
                    match process_manifest(processing_path.clone(), cli).await {
                        Ok(_) => {
                            let processed_target = processed_dir.join(&fname);
                            atomic_move(&processing_path, &processed_target).ok();
                            info!("Job {} processed", fname);
                        }
                        Err(err) => {
                            error!("Job {} failed: {}", fname, err);
                            let failed_target = failed_dir.join(&fname);
                            atomic_move(&processing_path, &failed_target).ok();
                            warn!("Moved failed manifest to {}", failed_target.display());
                        }
                    }
                }
            }
        }

        // wait for notify events or sleep
        match rx.recv_timeout(Duration::from_secs(5)) {
            Ok(ev) => {
                debug!("FS event: {:?}", ev);
            }
            Err(_) => {
                // timeout
            }
        }
    }
}

async fn process_manifest(manifest_path: PathBuf, cli: &Cli) -> anyhow::Result<()> {
    info!("Processing manifest {}", manifest_path.display());
    let data = fs::read_to_string(&manifest_path)?;
    let entries: Vec<ManifestEntry> = serde_json::from_str(&data)?;
    info!("{} tiles", entries.len());
    let job_name = manifest_path.file_stem().unwrap().to_string_lossy().to_string();
    let job_workdir = Path::new(&cli.workdir).join(&job_name);
    if job_workdir.exists() { fs::remove_dir_all(&job_workdir).ok(); }
    fs::create_dir_all(&job_workdir)?;

    let sem = Semaphore::new(cli.concurrency);
    let mut handles = Vec::new();
    for e in entries.iter() {
        let permit = sem.clone().acquire_owned().await.unwrap();
        let storage_base = cli.storage.trim_start_matches("local://").to_string();
        let e = e.clone();
        let job_workdir = job_workdir.clone();
        let max_attempts = cli.max_attempts;
        let backoff_base = cli.backoff_base;
        let handle = tokio::spawn(async move {
            let dest = job_workdir.join(format!("tile_{:04}{}", e.index, extract_ext(&e.filename)));
            if dest.exists() {
                if let Some(ref expected) = e.sha256 {
                    match compute_sha256_hex_async(&dest).await {
                        Ok(found) if found.eq_ignore_ascii_case(expected) => {
                            info!("Tile {} already verified", e.index);
                            drop(permit);
                            return Ok::<(), anyhow::Error>(());
                        }
                        _ => { fs::remove_file(&dest).ok(); }
                    }
                } else { info!("Tile {} present no-checksum, skipping", e.index); drop(permit); return Ok(()); }
            }
            let src = Path::new(&storage_base).join(&e.filename);
            if !src.exists() { anyhow::bail!("Source missing: {:?}", src); }
            let mut attempt = 0u32;
            loop {
                attempt += 1;
                let tmp = dest.with_extension("tmp");
                let copy_res = tokio::fs::copy(&src, &tmp).await;
                if let Err(err) = copy_res { warn!("copy failed {}", err); }
                else {
                    if let Some(ref expected) = e.sha256 {
                        match compute_sha256_hex_async(&tmp).await {
                            Ok(found) if found.eq_ignore_ascii_case(expected) => { tokio::fs::rename(&tmp, &dest).await?; break; }
                            Ok(found) => { warn!("checksum mismatch {} != {}", found, expected); tokio::fs::remove_file(&tmp).await.ok(); }
                            Err(err) => { warn!("checksum compute failed {}", err); tokio::fs::remove_file(&tmp).await.ok(); }
                        }
                    } else { tokio::fs::rename(&tmp, &dest).await?; break; }
                }
                if attempt >= max_attempts { anyhow::bail!("Failed tile {} after {} attempts", e.index, attempt); }
                let backoff = backoff_base.saturating_pow(attempt.saturating_sub(1)) as u64;
                let jitter = rand::thread_rng().gen_range(0..=backoff);
                let s = Duration::from_secs(backoff + jitter);
                info!("Retrying tile {} after {:?}", e.index, s);
                sleep(s).await;
            }
            drop(permit);
            Ok::<(), anyhow::Error>(())
        });
        handles.push(handle);
    }

    for h in handles {
        match h.await { Ok(Ok(())) => {}, Ok(Err(e)) => return Err(e), Err(e) => return Err(anyhow::anyhow!("join error {:?}", e)), }
    }

    info!("All tiles downloaded to {}", job_workdir.display());
    let out_tiff = format!("{}.{}.tiff", cli.out, job_name);
    // try rust vips
    match try_rust_vips_stitch(&job_workdir, &entries, cli) {
        Ok(()) => info!("stitched via rust vips"),
        Err(_) => {
            if command_exists("vips") {
                vips_cli_arrayjoin(&job_workdir, &entries, compute_cols(&entries), &out_tiff)?;
            } else {
                anyhow::bail!("No vips available");
            }
        }
    }

    let compressed = compress_tiff_with_xz(&out_tiff)?;

    let upload_dir = cli.upload.trim_start_matches("local://"); fs::create_dir_all(upload_dir)?;
    let dest = Path::new(upload_dir).join(Path::new(&compressed).file_name().unwrap());
    let mut attempt = 0u32;
    loop {
        attempt += 1;
        fs::copy(&compressed, &dest)?;
        let ssrc = compute_sha256_hex_sync(Path::new(&compressed))?;
        let sdst = compute_sha256_hex_sync(&dest)?;
        if ssrc.eq_ignore_ascii_case(&sdst) { info!("Upload verified {}", ssrc); break; }
        warn!("Upload verify failed {} != {}", ssrc, sdst);
        if attempt >= cli.upload_max_attempts { anyhow::bail!("Upload verify failed after {} attempts", attempt); }
        fs::remove_file(&dest).ok(); std::thread::sleep(Duration::from_secs(2u64.pow(attempt.min(6))));
    }

    info!("Job {} done", job_name);
    Ok(())
}

fn compute_cols(entries: &[ManifestEntry]) -> usize {
    entries.iter().map(|e| e.col).max().map(|m| (m + 1) as usize).unwrap_or(0)
}

fn try_rust_vips_stitch(_workdir: &Path, _entries: &[ManifestEntry], _cli: &Cli) -> anyhow::Result<()> {
    // Placeholder: only enabled if vips-rs feature and crate available.
    Err(anyhow::anyhow!("Rust libvips integration not compiled in"))
}

fn vips_cli_arrayjoin(workdir: &Path, entries: &[ManifestEntry], cols: usize, out: &str) -> anyhow::Result<()> {
    let mut args: Vec<String> = Vec::new();
    for e in entries { args.push(workdir.join(format!("tile_{:04}{}", e.index, extract_ext(&e.filename))).to_string_lossy().into_owned()); }
    args.push(out.to_string()); args.push("--across".to_string()); args.push(cols.to_string());
    let status = Command::new("vips").arg("arrayjoin").args(&args).status()?;
    if !status.success() { anyhow::bail!("vips failed"); }
    Ok(())
}

fn compress_tiff_with_xz(out_tiff: &str) -> anyhow::Result<String> {
    if command_exists("xz") { let status = Command::new("xz").arg("-T0").arg("-9").arg("-e").arg("-v").arg(out_tiff).status()?; if !status.success() { anyhow::bail!("xz failed"); } Ok(format!("{}.xz", out_tiff)) }
    else if command_exists("lzma") { let status = Command::new("lzma").arg("-9").arg(out_tiff).status()?; if !status.success() { anyhow::bail!("lzma failed"); } Ok(format!("{}.lzma", out_tiff)) }
    else { anyhow::bail!("No compressor") }
}

fn command_exists(n: &str) -> bool { which::which(n).is_ok() }

fn extract_ext(fname: &str) -> String { Path::new(fname).extension().map(|s| format!(".{}", s.to_string_lossy())).unwrap_or_else(|| ".tiff".to_string()) }

async fn compute_sha256_hex_async(p: &Path) -> anyhow::Result<String> {
    // streaming async read to avoid loading whole file into memory
    let mut file = tokio::fs::File::open(p).await?;
    let mut hasher = Sha256::new();
    let mut buf = vec![0u8; 8 * 1024];
    loop {
        let n = file.read(&mut buf).await?;
        if n == 0 { break; }
        hasher.update(&buf[..n]);
    }
    Ok(hex::encode(hasher.finalize()))
}

fn compute_sha256_hex_sync(p: &Path) -> anyhow::Result<String> {
    let f = fs::File::open(p)?;
    let mut reader = BufReader::new(f);
    let mut hasher = Sha256::new();
    let mut buf = [0u8; 8192];
    loop {
        let n = reader.read(&mut buf)?;
        if n == 0 { break; }
        hasher.update(&buf[..n]);
    }
    Ok(hex::encode(hasher.finalize()))
}

fn atomic_move(src: &Path, dst: &Path) -> anyhow::Result<()> {
    // try rename first
    match fs::rename(src, dst) {
        Ok(_) => return Ok(()),
        Err(e) => {
            warn!("Rename failed (attempting copy+remove): {}", e);
            // fallback to copy + remove
            fs::copy(src, dst)?;
            fs::remove_file(src)?;
            return Ok(());
        }
    }
}
