//! Bounded waits for directly owned child processes.
//! Authors: Стефан Колесников and GPT-6.1 Sol.

use std::io;
use std::process::{Child, ExitStatus};
use std::sync::atomic::{AtomicBool, Ordering};
use std::thread;
use std::time::{Duration, Instant};

pub(crate) fn wait_child(
    child: &mut Child,
    timeout: Duration,
    cancelled: Option<&AtomicBool>,
) -> io::Result<ExitStatus> {
    let started = Instant::now();
    let error = loop {
        if cancelled.is_some_and(|flag| flag.load(Ordering::Relaxed)) {
            break io::Error::new(io::ErrorKind::Interrupted, "Operation interrupted");
        }
        match child.try_wait() {
            Ok(Some(status)) => return Ok(status),
            Err(error) => break error,
            Ok(None) if started.elapsed() >= timeout => {
                break io::Error::new(io::ErrorKind::TimedOut, "Child process timed out");
            }
            Ok(None) => thread::sleep(Duration::from_millis(25)),
        }
    };
    let _ = child.kill();
    let _ = child.wait();
    Err(error)
}
