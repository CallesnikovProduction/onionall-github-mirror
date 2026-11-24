//! Runs Docker with explicit Compose ownership, bounded waits and local logs.
//! Authors: Стефан Колесников and GPT-6.1 Sol.

use std::fs::OpenOptions;
use std::io::{self, Read, Seek, Write};
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};
use std::sync::atomic::{AtomicBool, Ordering};
use std::thread;
use std::time::{Duration, Instant};

#[derive(Debug)]
pub struct Outcome {
    pub code: i32,
    pub stdout: String,
}

pub trait Docker {
    fn run(
        &self,
        args: &[&str],
        log: &str,
        seconds: u64,
        capture: bool,
        cleanup: bool,
    ) -> io::Result<Outcome>;
    fn check_cancelled(&self) -> io::Result<()>;
}

pub struct Process<'a> {
    pub root: &'a Path,
    pub dir: &'a Path,
    pub project: &'a str,
    pub cancelled: &'a AtomicBool,
}

impl Docker for Process<'_> {
    fn check_cancelled(&self) -> io::Result<()> {
        if self.cancelled.load(Ordering::Relaxed) {
            return Err(io::Error::new(
                io::ErrorKind::Interrupted,
                "Controller interrupted",
            ));
        }
        Ok(())
    }

    fn run(
        &self,
        args: &[&str],
        log: &str,
        seconds: u64,
        capture: bool,
        cleanup: bool,
    ) -> io::Result<Outcome> {
        if !cleanup {
            self.check_cancelled()?;
        }
        let mut output = OpenOptions::new()
            .create_new(true)
            .write(true)
            .open(self.dir.join(log))?;
        // File-backed capture avoids pipe backpressure and does not wait for
        // EOF from descendants after the Docker CLI has exited or been killed.
        let mut captured = capture
            .then(|| tempfile::tempfile_in(self.dir))
            .transpose()?;
        let stdout = captured.as_ref().unwrap_or(&output).try_clone()?;
        let mut command = Command::new("docker");
        command
            .args(args)
            .current_dir(self.root)
            .env("M2_RUN_DIR", self.dir)
            .env("M2_RUN_REL", format!(".runtime/m2/{}", self.project))
            .env("M2_RUNTIME_USER", runtime_user())
            .stdin(Stdio::null())
            .stdout(stdout)
            .stderr(output.try_clone()?);
        let mut child = command.spawn()?;
        let deadline = Instant::now() + Duration::from_secs(seconds);
        let status = loop {
            let result = if cleanup {
                Ok(())
            } else {
                self.check_cancelled()
            };
            let result = result.and_then(|()| child.try_wait());
            match result {
                Ok(Some(status)) => break status,
                Ok(None) if Instant::now() < deadline => thread::sleep(Duration::from_millis(25)),
                other => {
                    let _ = child.kill();
                    let _ = child.wait();
                    return Err(other.err().unwrap_or_else(|| {
                        io::Error::new(io::ErrorKind::TimedOut, format!("{log} timed out"))
                    }));
                }
            }
        };
        let mut stdout = String::new();
        if let Some(stream) = captured.as_mut() {
            stream.rewind()?;
            let mut bytes = Vec::new();
            stream.take(1024 * 1024 + 1).read_to_end(&mut bytes)?;
            if bytes.len() > 1024 * 1024 {
                return Err(io::Error::other("Docker identity output exceeds 1 MiB"));
            }
            output.write_all(&bytes)?;
            stdout = String::from_utf8(bytes).map_err(io::Error::other)?;
        }
        Ok(Outcome {
            code: exit_code(status),
            stdout,
        })
    }
}

#[cfg(unix)]
fn runtime_user() -> String {
    format!("{}:{}", nix::unistd::getuid(), nix::unistd::getgid())
}

#[cfg(windows)]
fn runtime_user() -> String {
    "1000:1000".into()
}

fn exit_code(status: std::process::ExitStatus) -> i32 {
    #[cfg(unix)]
    {
        use std::os::unix::process::ExitStatusExt;
        status
            .code()
            .unwrap_or_else(|| -status.signal().unwrap_or(1))
    }
    #[cfg(windows)]
    {
        status.code().unwrap_or(1)
    }
}

pub struct Context<'a> {
    pub docker: &'a dyn Docker,
    pub dir: PathBuf,
    pub project: &'a str,
    pub diagnostics: bool,
}

impl Context<'_> {
    pub fn compose<'a>(&'a self, args: &[&'a str]) -> Vec<&'a str> {
        let mut command = vec!["compose", "-p", self.project, "-f", "compose.m2.yaml"];
        command.extend_from_slice(args);
        command
    }
}

pub fn required(outcome: Outcome, operation: &str) -> io::Result<Outcome> {
    if outcome.code != 0 {
        return Err(io::Error::other(format!("{operation} failed")));
    }
    Ok(outcome)
}

pub fn container_ids(text: &str) -> io::Result<Vec<&str>> {
    let ids: Vec<_> = text.split_ascii_whitespace().collect();
    if ids.iter().any(|id| {
        !matches!(id.len(), 12 | 64)
            || !id
                .bytes()
                .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
    }) {
        return Err(io::Error::other("Docker returned an invalid container ID"));
    }
    Ok(ids)
}
