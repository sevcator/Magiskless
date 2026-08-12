use crate::consts::{BBPATH, MAGISK_VERSION, SECURE_DIR};
use crate::ffi::{exec_script, exec_script_async, get_magisk_tmp};
use base::const_format::concatcp;
use base::{FsPathBuilder, ResultExt, cstr};
use std::process::{Command, Stdio};

pub const UDONGE_MODULE_NAME: &str = "@udonge";
pub const UDONGE_ROOT: &str = concatcp!(SECURE_DIR, "/udonge");
pub const UDONGE_RUNTIME: &str = concatcp!(UDONGE_ROOT, "/runtime");
const UDONGE_NEXT: &str = concatcp!(UDONGE_ROOT, "/runtime.new");
const UDONGE_DISABLED: &str = concatcp!(UDONGE_ROOT, "/state/disabled");

pub fn is_enabled() -> bool {
    cstr!(UDONGE_RUNTIME).exists() && !cstr!(UDONGE_DISABLED).exists()
}

pub fn setup_runtime() {
    let buffer = cstr::buf::default();
    let archive = buffer.join_path(get_magisk_tmp()).join_path("udonge.bin");
    if !archive.exists() {
        return;
    }

    cstr!(UDONGE_ROOT).mkdirs(0o700).log_ok();
    cstr!(UDONGE_ROOT).follow_link().chmod(0o700).log_ok();

    let version_path = cstr::buf::default()
        .join_path(UDONGE_RUNTIME)
        .join_path("version");
    let installed = std::fs::read_to_string(&version_path)
        .map(|version| version.trim() == MAGISK_VERSION)
        .unwrap_or(false);

    if !installed {
        cstr!(UDONGE_NEXT).remove_all().ok();
        cstr!(UDONGE_NEXT).mkdirs(0o700).log_ok();

        let busybox = cstr::buf::default()
            .join_path(get_magisk_tmp())
            .join_path(BBPATH)
            .join_path("busybox");
        let extracted = Command::new(&busybox)
            .arg("unzip")
            .arg("-oq")
            .arg(&archive)
            .arg("-d")
            .arg(UDONGE_NEXT)
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .status()
            .map(|status| status.success())
            .unwrap_or(false);
        let next_version = cstr::buf::default()
            .join_path(UDONGE_NEXT)
            .join_path("version");
        let verified = extracted
            && std::fs::read_to_string(&next_version)
                .map(|version| version.trim() == MAGISK_VERSION)
                .unwrap_or(false);
        if verified {
            cstr!(UDONGE_RUNTIME).remove_all().ok();
            cstr!(UDONGE_NEXT).rename_to(cstr!(UDONGE_RUNTIME)).log_ok();
        } else {
            cstr!(UDONGE_NEXT).remove_all().ok();
        }
    }

    if cstr!(UDONGE_RUNTIME).exists() {
        let post_fs_data = cstr::buf::default()
            .join_path(UDONGE_RUNTIME)
            .join_path("post-fs-data.sh");
        post_fs_data.follow_link().chmod(0o700).log_ok();
        exec_script(&post_fs_data);
    }
}

pub fn run_service() {
    if !is_enabled() {
        return;
    }
    let service = cstr::buf::default()
        .join_path(UDONGE_RUNTIME)
        .join_path("service.sh");
    if service.exists() {
        service.follow_link().chmod(0o700).log_ok();
        exec_script_async(&service);
    }
}
