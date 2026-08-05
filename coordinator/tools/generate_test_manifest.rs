// coordinator/tools/generate_test_manifest.rs
// Generates a manifest.json with 512 entries for a 32x16 grid and creates placeholder tile filenames.
// Usage: cargo run --bin generate_test_manifest -- <out_manifest.json> <tile_prefix>

use serde_json::json;
use std::fs::write;
use std::env;

fn main() {
    let args: Vec<String> = env::args().collect();
    let out = args.get(1).map(|s| s.as_str()).unwrap_or("manifest_test.json");
    let prefix = args.get(2).map(|s| s.as_str()).unwrap_or("tile");
    let mut arr = Vec::new();
    let cols = 32;
    let rows = 16;
    let tile_w = 1800;
    let tile_h = 1800;
    let mut idx = 0;
    for y in 0..rows {
        for x in 0..cols {
            let filename = format!("{:}_{:04}_{:02}x{:02}.tiff", prefix, idx, x, y);
            let entry = json!({
                "index": idx,
                "col": x,
                "row": y,
                "filename": filename,
                "offset": [x * tile_w, y * tile_h],
                "resolution": [tile_w, tile_h]
            });
            arr.push(entry);
            idx += 1;
        }
    }
    let txt = serde_json::to_string_pretty(&arr).unwrap();
    write(out, txt).expect("write manifest");
    println!("Wrote {} entries to {}", idx, out);
}
