use base::{LogLevel, Utf8CStr, raw_cstr, update_logger};
use libc::c_char;
use std::os::fd::RawFd;
use std::sync::atomic::{AtomicI32, Ordering};

unsafe extern "C" {
    fn __android_log_write(prio: i32, tag: *const c_char, msg: *const c_char);
}

fn android_log_write(_level: LogLevel, _msg: &Utf8CStr) {
    #[cfg(debug_assertions)]
    unsafe {
        __android_log_write(log_prio(_level), raw_cstr!("Magisk"), _msg.as_ptr());
    }
}

#[cfg(debug_assertions)]
fn log_prio(level: LogLevel) -> i32 {
    match level {
        LogLevel::Error => 6,
        LogLevel::Warn => 5,
        LogLevel::Info => 4,
        LogLevel::Debug => 3,
    }
}

pub fn android_logging() {
    update_logger(|logger| logger.write = android_log_write);
}

pub fn magisk_logging() {
    update_logger(|logger| logger.write = android_log_write);
}

pub fn zygisk_logging() {
    update_logger(|logger| logger.write = android_log_write);
}

pub fn zygisk_close_logd() {}

static ZYGISK_LOGD: AtomicI32 = AtomicI32::new(-1);

pub fn zygisk_get_logd() -> RawFd {
    ZYGISK_LOGD.load(Ordering::Relaxed)
}

pub fn setup_logfile() {}

pub fn start_log_daemon() {}
