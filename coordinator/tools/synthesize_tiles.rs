// coordinator/tools/synthesize_tiles.rs
// Create fake uncompressed TIFF tiles (solid-color) into a storage directory to test the coordinator.
// Usage: cargo run --bin synth_tiles -- <storage_dir> <manifest.json>

use image::{RgbImage, Rgb};
use serde_json::Value;
use std::fs::{read_to_string, create_dir_all};
use std::path::Path;
use std::env;

fn main() {
    let args: Vec<String> = env::args().collect();
    let out_dir = args.get(1).map(|s| s.as_str()).unwrap_or("./storage");
    let manifest = args.get(2).map(|s| s.as_str()).unwrap_or("manifest_test.json");
    create_dir_all(out_dir).unwrap();
    let txt = read_to_string(manifest).expect("manifest");
    let arr: Value = serde_json::from_str(&txt).unwrap();
    if let Value::Array(items) = arr {
        for it in items.iter() {
            let filename = it.get("filename").and_then(|v| v.as_str()).unwrap();
            let res = it.get("resolution").and_then(|v| v.as_array()).unwrap();
            let w = res[0].as_u64().unwrap() as u32;
            let h = res[1].as_u64().unwrap() as u32;
            // pick color by index
            let idx = it.get("index").and_then(|v| v.as_u64()).unwrap() as u8;
            let r = idx.wrapping_mul(37);
            let g = idx.wrapping_mul(73);
            let b = idx.wrapping_mul(149);
            let mut img = RgbImage::new(w, h);
            for y in 0..h {
                for x in 0..w {
                    img.put_pixel(x, y, Rgb([r, g, b]));
                }
            }
            let path = Path::new(out_dir).join(filename);
            img.save(path).unwrap();
        }
    }
    println!("Synthesized tiles into {}", out_dir);
}
