use pb_rs::ConfigBuilder;
use pb_rs::types::FileDescriptor;
use std::path::Path;
use std::{env, fs};

use crate::codegen::gen_cxx_binding;

#[path = "../include/codegen.rs"]
mod codegen;

#[allow(clippy::unwrap_used)]
fn main() {
    println!("cargo:rerun-if-changed=proto/update_metadata.proto");

    gen_cxx_binding("boot-rs");

    let output = Path::new(&env::var("OUT_DIR").unwrap()).join("update-metadata");
    fs::create_dir_all(&output).unwrap();
    let cb = ConfigBuilder::new(
        &["proto/update_metadata.proto"],
        None,
        Some(&output.to_str().unwrap()),
        &["."],
    )
    .unwrap();
    FileDescriptor::run(
        &cb.single_module(true)
            .headers(false)
            .dont_use_cow(true)
            .generate_getters(true)
            .build(),
    )
    .unwrap();
}
