use base::nix::fcntl::OFlag;
use base::{SilentLogExt, cstr, disable_logging, libc, raw_cstr};
use libc::{O_CLOEXEC, S_IFCHR, STDERR_FILENO, STDIN_FILENO, STDOUT_FILENO, SYS_dup3, makedev, mknod, syscall};

pub fn setup_klog() {
    unsafe {
        let mut fd = cstr!("/dev/null")
            .open(OFlag::O_RDWR | OFlag::O_CLOEXEC)
            .silent();
        if fd.is_err() {
            mknod(raw_cstr!("/null"), S_IFCHR | 0o666, makedev(1, 3));
            fd = cstr!("/null")
                .open(OFlag::O_RDWR | OFlag::O_CLOEXEC)
                .silent();
            cstr!("/null").remove().ok();
        }
        if let Ok(ref fd) = fd {
            syscall(SYS_dup3, fd, STDIN_FILENO, O_CLOEXEC);
            syscall(SYS_dup3, fd, STDOUT_FILENO, O_CLOEXEC);
            syscall(SYS_dup3, fd, STDERR_FILENO, O_CLOEXEC);
        }
    }
    disable_logging();
}
