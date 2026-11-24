//! Host-side M2 controller. The Compose health probe and JVM protocol remain
//! the authorities for Tor observations and Frontier remains the scheduler.
//! Authors: Стефан Колесников and GPT-6.1 Sol.

mod docker;
mod evidence;
mod recover;
mod supervise;

#[cfg(test)]
#[path = "../../../test/m2/onionall_runtime.rs"]
mod tests;

use crate::docker::{Context, Process};
use serde_json::{Value, json};
use std::ffi::{OsStr, OsString};
use std::io::{self, Read};
use std::path::Path;
use std::process::ExitCode;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};

const USAGE: &str = "Run from the Onionall Brawler checkout.\n\
Usage: ./onionall supervise <project> <run-dir>\n\
       ./onionall recover <project> <run-dir> [--detection <filename>] [--diagnostics]";

fn main() -> ExitCode {
    let args: Vec<_> = std::env::args_os().skip(1).collect();
    if matches!(args.as_slice(), [flag] | [_, flag] if flag == "--help" || flag == "-h") {
        println!("{USAGE}");
        return ExitCode::SUCCESS;
    }
    if matches!(args.as_slice(), [command] if command == "check-connect-failure") {
        return match check_connect_failure() {
            Ok(true) => ExitCode::SUCCESS,
            Ok(false) => ExitCode::FAILURE,
            Err(error) => {
                eprintln!("{error}");
                ExitCode::from(2)
            }
        };
    }
    let Some((command, project, directory, detection, diagnostics)) = arguments(&args) else {
        eprintln!("{USAGE}");
        return ExitCode::from(2);
    };
    let outcome = execute(
        command,
        project,
        Path::new(directory),
        detection,
        diagnostics,
    )
    .unwrap_or_else(|error| json!({"passed": false, "blocker": error.to_string()}));
    let mut output = io::stdout().lock();
    if serde_json::to_writer_pretty(&mut output, &outcome).is_err() {
        return ExitCode::FAILURE;
    }
    use std::io::Write;
    if writeln!(output).is_err() {
        return ExitCode::FAILURE;
    }
    if outcome["passed"] == true {
        ExitCode::SUCCESS
    } else {
        ExitCode::FAILURE
    }
}

/// Classify one bounded observation from the M2 fixtures without contacting Docker.
fn check_connect_failure() -> io::Result<bool> {
    let mut data = Vec::new();
    io::stdin()
        .lock()
        .take(1024 * 1024 + 1)
        .read_to_end(&mut data)?;
    if data.len() > 1024 * 1024 {
        return Err(io::Error::other("Evidence exceeds 1 MiB"));
    }
    let observation = serde_json::from_slice(&data)?;
    Ok(recover::connect_failure(&observation))
}

fn arguments(args: &[OsString]) -> Option<(&str, &str, &OsStr, &str, bool)> {
    let [command, project, directory, flags @ ..] = args else {
        return None;
    };
    let command = command.to_str()?;
    let project = project.to_str()?;
    let mut diagnostics = std::env::var("M2_TOR_DIAGNOSTICS").is_ok_and(|value| value == "1");
    let mut detection = evidence::DETECTION;
    match command {
        "supervise" if flags.is_empty() => {}
        "recover" => {
            let mut flags = flags.iter();
            while let Some(flag) = flags.next() {
                match flag.to_str()? {
                    "--diagnostics" => diagnostics = true,
                    "--detection" => detection = flags.next()?.to_str()?,
                    _ => return None,
                }
            }
        }
        _ => return None,
    }
    Some((
        command,
        project,
        directory.as_os_str(),
        detection,
        diagnostics,
    ))
}

fn execute(
    command: &str,
    project: &str,
    directory: &Path,
    detection: &str,
    diagnostics: bool,
) -> io::Result<Value> {
    let cwd = std::env::current_dir()?;
    let root = cwd
        .ancestors()
        .find(|path| {
            path.join("compose.m2.yaml").is_file()
                && path.join("test/m2/network_probe.py").is_file()
        })
        .ok_or_else(|| io::Error::other("Run from the Onionall Brawler checkout"))?;
    let dir = evidence::owned_dir(root, project, directory)?;
    let cancelled = Arc::new(AtomicBool::new(false));
    let signal = Arc::clone(&cancelled);
    ctrlc::set_handler(move || signal.store(true, Ordering::Relaxed)).map_err(io::Error::other)?;
    let _lock = evidence::lock(&dir)?;
    let process = Process {
        root,
        dir: &dir,
        project,
        cancelled: &cancelled,
    };
    let context = Context {
        docker: &process,
        dir: dir.clone(),
        project,
        diagnostics,
    };
    match command {
        "supervise" => supervise::run(&context),
        "recover" => recover::run(&context, detection, true),
        _ => Err(io::Error::other("Unknown M2 command")),
    }
}
