# Engine / Coordinator README

This repository contains the "coordinator" service for the Engine pipeline. The coordinator watches a manifests folder for tile manifests, downloads tiles from a local storage directory, verifies checksums, stitches tiles into a single TIFF (using libvips), compresses with LZMA (xz), verifies the upload, and moves job manifests through a queue lifecycle. An HTTP admin server allows simple inspection and reprocessing.

Files added:
- coordinator/: Coordinator Rust service
- coordinator/tools/generate_test_manifest.rs: generate a 512-entry manifest for 32x16 grid
- coordinator/tools/synthesize_tiles.rs: synthesize fake tiles (solid color) into a storage folder for testing

Building (Linux):
1. Install prerequisites:
   - Rust toolchain (rustup)
   - libvips and dev headers: `sudo apt-get install libvips-dev libvips-tools pkg-config`
   - xz-utils: `sudo apt-get install xz-utils`
   - Image crate dependencies: included via Cargo

2. Build coordinator:
   cd coordinator
   cargo build --release

3. Run in watch mode:
   mkdir -p /shared/storage
   mkdir -p manifests
   ./target/release/coordinator --watch --manifests-dir ./manifests --storage local:///shared/storage --out /tmp/final_output --workdir /tmp/work --upload local:///tmp/uploads --admin-bind 0.0.0.0:3030

4. Use admin API:
   GET http://localhost:3030/admin/status
   GET http://localhost:3030/admin/jobs
   POST http://localhost:3030/admin/reprocess/<manifest_name.json>

Testing locally (end-to-end):
1. Generate test manifest and fake tiles:
   cd coordinator
   cargo run --bin generate_test_manifest -- manifest_test.json tile
   cargo run --bin synth_tiles -- ./shared_storage manifest_test.json
   # Move manifest into manifests/ directory so coordinator picks it up
   mv manifest_test.json ../manifests/manifest_test.json

2. Coordinator will detect and process the job. Resulting compressed file will be copied to the upload directory.

Windows notes:
- The coordinator is primarily targeted at Linux environments with libvips and xz installed. On Windows you can run the coordinator but you must install libvips and ensure `vips` and `xz` are available in PATH. Building with MSVC toolchain is supported via Rust toolchain; use `cargo build --release`.

Security:
- Admin HTTP endpoint has no authentication in this prototype — bind to a secure interface or put behind a firewall in production.

