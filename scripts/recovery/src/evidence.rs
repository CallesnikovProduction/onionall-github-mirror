//! Evidence is scoped to the canonical checkout and keeps the original JSON contract.
//! Authors: Стефан Колесников and GPT-6.1 Sol.

use serde_json::Value;
use std::fs::{self, File, OpenOptions};
use std::io::{self, Read, Write};
use std::path::{Path, PathBuf};

pub const RECOVERY: &str = "gateway-recovery.json";
pub const SUPERVISION: &str = "runtime-supervision.json";
pub const DETECTION: &str = "recovery-detection.json";
pub const HEALTH: &str = "recovery-health.json";
pub const SERIES: &str = "recovery-series.json";

pub fn valid_project(project: &str) -> bool {
    project
        .strip_prefix("onionall-brawler-m2-")
        .is_some_and(|suffix| {
            suffix.len() == 12
                && suffix
                    .bytes()
                    .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
        })
}

pub fn valid_detection(name: &str) -> bool {
    ![RECOVERY, SUPERVISION, HEALTH, SERIES]
        .iter()
        .any(|reserved| name.eq_ignore_ascii_case(reserved))
        && name.ends_with(".json")
        && name.len() > ".json".len()
        && name
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || matches!(b, b'-' | b'_' | b'.'))
        && !name.starts_with('.')
        && !matches!(
            name.split('.')
                .next()
                .unwrap_or_default()
                .to_ascii_uppercase()
                .as_str(),
            "CON"
                | "PRN"
                | "AUX"
                | "NUL"
                | "COM1"
                | "COM2"
                | "COM3"
                | "COM4"
                | "COM5"
                | "COM6"
                | "COM7"
                | "COM8"
                | "COM9"
                | "LPT1"
                | "LPT2"
                | "LPT3"
                | "LPT4"
                | "LPT5"
                | "LPT6"
                | "LPT7"
                | "LPT8"
                | "LPT9"
        )
}

pub fn owned_dir(root: &Path, project: &str, requested: &Path) -> io::Result<PathBuf> {
    if !valid_project(project) {
        return Err(io::Error::other(
            "Recovery requires an isolated M2 project name",
        ));
    }
    let expected = root.join(".runtime").join("m2").join(project);
    let canonical_expected = root
        .canonicalize()?
        .join(".runtime")
        .join("m2")
        .join(project);
    if requested.canonicalize()? != canonical_expected
        || expected.canonicalize()? != canonical_expected
        || !expected.is_dir()
    {
        return Err(io::Error::other(
            "Evidence directory must belong to this M2 project",
        ));
    }
    Ok(expected)
}

pub fn exists(path: &Path) -> io::Result<bool> {
    match fs::symlink_metadata(path) {
        Ok(meta) if meta.file_type().is_file() => Ok(true),
        Ok(_) => Err(io::Error::other("Evidence path must be a regular file")),
        Err(error) if error.kind() == io::ErrorKind::NotFound => Ok(false),
        Err(error) => Err(error),
    }
}

pub fn read(path: &Path) -> io::Result<Value> {
    if !exists(path)? {
        return Err(io::Error::new(
            io::ErrorKind::NotFound,
            "Evidence file is missing",
        ));
    }
    let mut data = Vec::new();
    File::open(path)?
        .take(1024 * 1024 + 1)
        .read_to_end(&mut data)?;
    if data.len() > 1024 * 1024 {
        return Err(io::Error::other("Evidence exceeds 1 MiB"));
    }
    serde_json::from_slice(&data).map_err(|error| io::Error::new(io::ErrorKind::InvalidData, error))
}

/// The lock is held by the outer command across any in-process recovery. The
/// file itself remains, so another process cannot acquire a different inode.
pub fn lock(dir: &Path) -> io::Result<File> {
    let path = dir.join("onionall-runtime.lock");
    exists(&path)?;
    let file = OpenOptions::new()
        .read(true)
        .write(true)
        .create(true)
        .truncate(false)
        .open(path)?;
    file.try_lock()
        .map_err(|error| io::Error::other(format!("M2 controller lock unavailable: {error}")))?;
    Ok(file)
}

pub fn begin(path: &Path, initial: &Value) -> io::Result<()> {
    let mut file = OpenOptions::new().write(true).create_new(true).open(path)?;
    serde_json::to_writer(&mut file, initial)?;
    file.sync_all()
}

/// Never truncate the live journal. On failure the previous state or the
/// exclusive initial marker remains and still consumes the budget.
pub fn save(path: &Path, value: &Value) -> io::Result<()> {
    let dir = path
        .parent()
        .ok_or_else(|| io::Error::other("Journal has no parent"))?;
    let mut temporary = tempfile::NamedTempFile::new_in(dir)?;
    serde_json::to_writer_pretty(temporary.as_file_mut(), value)?;
    temporary.write_all(b"\n")?;
    temporary.as_file().sync_all()?;
    temporary.persist(path).map_err(|error| error.error)?;
    Ok(())
}
