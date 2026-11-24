//! Operational commands shipped with the Onionall toolchain image.
//! Authors: Стефан Колесников and GPT-6.1 Sol.

mod candidate;
mod process;
mod runtime;

use std::ffi::OsStr;
use std::path::Path;
use std::process::ExitCode;

const USAGE: &str = "Usage: onionall-tooling verify-tor-candidate <bundle> <destination>";

fn main() -> ExitCode {
    let args: Vec<_> = std::env::args_os().skip(1).collect();
    match args.as_slice() {
        [command, rest @ ..] if command == "runtime" => ExitCode::from(runtime::run(rest) as u8),
        [help] if help == "-h" || help == "--help" => {
            println!("{USAGE}");
            ExitCode::SUCCESS
        }
        [command, help]
            if command == "verify-tor-candidate" && (help == "-h" || help == "--help") =>
        {
            println!("{USAGE}");
            ExitCode::SUCCESS
        }
        [command, bundle, destination]
            if command == "verify-tor-candidate"
                && ![bundle, destination]
                    .iter()
                    .any(|arg| arg.as_os_str() == OsStr::new("")) =>
        {
            match std::env::current_dir().and_then(|root| {
                candidate::verify(&root, Path::new(bundle), Path::new(destination))
            }) {
                Ok(path) => {
                    println!(
                        "Verified signed APT chain and pinned package; staged: {}",
                        path.display()
                    );
                    ExitCode::SUCCESS
                }
                Err(error) => {
                    eprintln!("Tor candidate verification failed: {error}");
                    ExitCode::FAILURE
                }
            }
        }
        _ => {
            eprintln!("{USAGE}");
            ExitCode::from(2)
        }
    }
}
