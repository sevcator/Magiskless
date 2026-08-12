use base::{LogLevel, Utf8CStr, update_logger};

fn noop(_level: LogLevel, _msg: &Utf8CStr) {}

pub fn android_logging() {
    update_logger(|logger| logger.write = noop);
}
pub fn zygisk_logging() {
    update_logger(|logger| logger.write = noop);
}
