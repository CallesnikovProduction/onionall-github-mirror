//! Validates the signed APT chain for one pinned Tor candidate. The staged
//! provenance and package use the exact bytes consumed by verification.
//! Authors: Стефан Колесников and GPT-6.1 Sol.

use crate::process::wait_child;
use sha2::{Digest, Sha256};
use std::collections::HashMap;
use std::fs::{self, File};
use std::io::{self, Read, Write};
use std::path::{Component, Path, PathBuf};
use std::process::{Command, Stdio};
use std::time::Duration;
use time::{OffsetDateTime, format_description::well_known::Rfc2822};

const VERSION: &str = "0.4.9.15-1~d12.bookworm+1";
const SHA256: &str = "eca04d5ab2c6285601526419b3d9ca1a25bec477abb25360f4c97da92d79c88e";
const FINGERPRINT: &str = "A3C4F0F979CAA22CDBA8F512EE8CBC9E886DDD89";
const DOCKERFILE: &str = "docker/m2/Dockerfile.security-candidate";
const KEY: &str = "tor-signing-key.asc";
const SIGNED: &str = "InRelease";
const INDEX: &str = "Packages";
const INDEX_ENTRY: &str = "main/binary-amd64/Packages";
const MIB: u64 = 1024 * 1024;

struct Candidate<'a> {
    fingerprint: &'a str,
    sha256: &'a str,
}

pub fn verify(root: &Path, bundle: &Path, destination: &Path) -> io::Result<PathBuf> {
    let pins = Candidate {
        fingerprint: FINGERPRINT,
        sha256: SHA256,
    };
    verify_at(root, bundle, destination, &pins, OffsetDateTime::now_utc())
}

fn verify_at(
    root: &Path,
    bundle: &Path,
    destination: &Path,
    pins: &Candidate<'_>,
    now: OffsetDateTime,
) -> io::Result<PathBuf> {
    let destination = checked_destination(root, destination, false)?;
    let bundle = bundle.canonicalize()?;
    let temporary = tempfile::Builder::new()
        .prefix("tor-signature-")
        .tempdir()?;
    let home = temporary.path();
    let key = read_regular(&bundle.join(KEY), MIB)?;
    let signed = read_regular(&bundle.join(SIGNED), 4 * MIB)?;
    fs::write(home.join(KEY), &key)?;
    fs::write(home.join(SIGNED), &signed)?;
    let keyring = home.join("keyring.gpg");
    let release = home.join("Release");
    verify_signature(home, &keyring, &release, pins.fingerprint)?;

    let release = read_regular(&release, 4 * MIB)?;
    let index = read_regular(&bundle.join(INDEX), 32 * MIB)?;
    verify_release(utf8(&release)?, &index, now)?;
    let package = format!("tor_{VERSION}_amd64.deb");
    let data = read_regular(&bundle.join(&package), 64 * MIB)?;
    verify_package(utf8(&index)?, &package, &data, pins.sha256)?;
    let recipe = read_regular(&root.join(DOCKERFILE), MIB)?;

    // Reserve the final directory exclusively after verification. Publish the
    // Dockerfile last, so an incomplete copy cannot be consumed as a build.
    let destination = checked_destination(root, &destination, true)?;
    fs::create_dir(&destination)?;
    for (name, bytes) in [
        ("tor-update.deb", data.as_slice()),
        (KEY, &key),
        (SIGNED, &signed),
        (INDEX, &index),
    ] {
        File::create_new(destination.join(name))?.write_all(bytes)?;
    }
    let mut marker = tempfile::NamedTempFile::new_in(&destination)?;
    marker.write_all(&recipe)?;
    marker
        .persist_noclobber(destination.join("Dockerfile"))
        .map_err(|error| error.error)?;
    Ok(destination)
}

fn checked_destination(root: &Path, requested: &Path, create_parents: bool) -> io::Result<PathBuf> {
    let runtime = root.join(".runtime");
    let requested = if requested.is_absolute() {
        requested.to_path_buf()
    } else {
        root.join(requested)
    };
    let relative = requested
        .strip_prefix(&runtime)
        .map_err(|_| destination_error())?;
    let names: Vec<_> = relative
        .components()
        .map(|component| {
            if let Component::Normal(name) = component {
                Ok(name)
            } else {
                Err(destination_error())
            }
        })
        .collect::<io::Result<_>>()?;
    let (name, parents) = names.split_last().ok_or_else(destination_error)?;
    let mut path = runtime;
    directory(&path, create_parents)?;
    for parent in parents {
        path.push(parent);
        directory(&path, create_parents)?;
    }
    path.push(name);
    match fs::symlink_metadata(&path) {
        Ok(_) => Err(destination_error()),
        Err(error) if error.kind() == io::ErrorKind::NotFound => Ok(path),
        Err(error) => Err(error),
    }
}

fn directory(path: &Path, create: bool) -> io::Result<()> {
    match fs::symlink_metadata(path) {
        Ok(meta) if meta.file_type().is_dir() => Ok(()),
        Ok(_) => Err(destination_error()),
        Err(error) if error.kind() == io::ErrorKind::NotFound && !create => Ok(()),
        Err(error) if error.kind() == io::ErrorKind::NotFound => fs::create_dir(path),
        Err(error) => Err(error),
    }
}

fn destination_error() -> io::Error {
    io::Error::other("Use a new ignored .runtime/ build directory without symlink components")
}

fn read_regular(path: &Path, limit: u64) -> io::Result<Vec<u8>> {
    if !fs::symlink_metadata(path)?.file_type().is_file() {
        return Err(io::Error::other(
            "Expected a regular verification input file",
        ));
    }
    let file = File::open(path)?;
    if !file.metadata()?.is_file() || file.metadata()?.len() > limit {
        return Err(io::Error::other(
            "Verification input is irregular or exceeds its size limit",
        ));
    }
    let mut bytes = Vec::new();
    file.take(limit + 1).read_to_end(&mut bytes)?;
    if bytes.len() as u64 > limit {
        return Err(io::Error::other(
            "Verification input exceeds its size limit",
        ));
    }
    Ok(bytes)
}

fn verify_signature(
    home: &Path,
    keyring: &Path,
    release: &Path,
    fingerprint: &str,
) -> io::Result<()> {
    let key = home.join(KEY);
    let gpg = || {
        let mut command = Command::new("gpg");
        command
            .args([
                "--no-options",
                "--batch",
                "--no-tty",
                "--no-autostart",
                "--homedir",
            ])
            .arg(home);
        command
    };
    let shown = home.join("fingerprints");
    let mut show = gpg();
    show.args(["--with-colons", "--import-options", "show-only", "--import"])
        .arg(&key);
    gpg_step(
        &mut show,
        Some(File::create_new(&shown)?),
        "Cannot inspect Tor repository signing key",
    )?;
    let shown = read_regular(&shown, 4 * MIB)?;
    if primary_fingerprints(utf8(&shown)?)? != [fingerprint] {
        return Err(io::Error::other("Unexpected Tor repository signing key"));
    }
    gpg_step(
        gpg().args(["--dearmor", "--output"]).arg(keyring).arg(key),
        None,
        "Cannot prepare Tor repository signing key",
    )?;
    gpg_step(
        Command::new("gpgv")
            .arg("--homedir")
            .arg(home)
            .arg("--keyring")
            .arg(keyring)
            .arg("--output")
            .arg(release)
            .arg(home.join(SIGNED)),
        None,
        "Invalid Tor repository signature",
    )
}

fn gpg_step(command: &mut Command, stdout: Option<File>, rejected: &'static str) -> io::Result<()> {
    let mut child = command
        .stdin(Stdio::null())
        .stderr(Stdio::null())
        .stdout(stdout.map_or_else(Stdio::null, Stdio::from))
        .spawn()?;
    if !wait_child(&mut child, Duration::from_secs(30), None)?.success() {
        return Err(io::Error::other(rejected));
    }
    Ok(())
}

fn primary_fingerprints(list: &str) -> io::Result<Vec<&str>> {
    let mut fingerprints = Vec::new();
    let mut pending = false;
    for line in list.lines() {
        let mut fields = line.split(':');
        match fields.next() {
            Some("pub" | "sub") if pending => return Err(invalid_metadata()),
            Some("pub") => pending = true,
            Some("fpr") if pending => {
                fingerprints.push(
                    fields
                        .nth(8)
                        .filter(|value| !value.is_empty())
                        .ok_or_else(invalid_metadata)?,
                );
                pending = false;
            }
            _ => {}
        }
    }
    if pending {
        return Err(invalid_metadata());
    }
    Ok(fingerprints)
}

fn fields(text: &str) -> io::Result<HashMap<String, String>> {
    let mut fields = HashMap::<String, String>::new();
    let mut current = String::new();
    for line in text.lines() {
        if line.starts_with([' ', '\t']) {
            let content = fields.get_mut(&current).ok_or_else(invalid_metadata)?;
            content.push('\n');
            content.push_str(line.trim());
        } else if !line.is_empty() {
            let (name, value) = line.split_once(':').ok_or_else(invalid_metadata)?;
            if name.is_empty()
                || name.starts_with(['#', '-'])
                || !name.bytes().all(|byte| byte.is_ascii_graphic())
            {
                return Err(invalid_metadata());
            }
            current = name.to_ascii_lowercase();
            if fields
                .insert(current.clone(), value.trim().to_owned())
                .is_some()
            {
                return Err(invalid_metadata());
            }
        }
    }
    Ok(fields)
}

fn invalid_metadata() -> io::Error {
    io::Error::other("Malformed or ambiguous signed APT metadata")
}

fn utf8(bytes: &[u8]) -> io::Result<&str> {
    std::str::from_utf8(bytes).map_err(|_| invalid_metadata())
}

fn date(text: &str) -> io::Result<OffsetDateTime> {
    if text.ends_with(" -0000") {
        return Err(invalid_metadata());
    }
    // APT Release files often use the UTC name in place of a numeric offset.
    if let Some(stamp) = text.strip_suffix(" UTC") {
        OffsetDateTime::parse(&format!("{stamp} +0000"), &Rfc2822)
    } else {
        OffsetDateTime::parse(text, &Rfc2822)
    }
    .map_err(|_| invalid_metadata())
}

fn sha256(bytes: &[u8]) -> String {
    format!("{:x}", Sha256::digest(bytes))
}

fn verify_release(text: &str, index: &[u8], now: OffsetDateTime) -> io::Result<()> {
    let release = fields(text)?;
    if release.get("codename").map(String::as_str) != Some("bookworm") {
        return Err(io::Error::other("Unexpected signed repository codename"));
    }
    let published = date(release.get("date").ok_or_else(invalid_metadata)?)?;
    if published < now - time::Duration::days(7) || published > now + time::Duration::minutes(5) {
        return Err(io::Error::other(
            "Signed index is stale or future-dated; obtain a current bundle",
        ));
    }
    if let Some(expiry) = release.get("valid-until")
        && date(expiry)? < now
    {
        return Err(io::Error::other("Signed index has expired"));
    }
    let mut entries = release
        .get("sha256")
        .ok_or_else(invalid_metadata)?
        .lines()
        .filter_map(|row| {
            let mut parts = row.split_ascii_whitespace();
            let (hash, size, file) = (parts.next()?, parts.next()?, parts.next()?);
            (file == INDEX_ENTRY && parts.next().is_none()).then_some((hash, size))
        });
    let expected = (sha256(index), index.len().to_string());
    if entries.next() != Some((expected.0.as_str(), expected.1.as_str()))
        || entries.next().is_some()
    {
        return Err(io::Error::other("Packages does not match signed Release"));
    }
    Ok(())
}

fn verify_package(index: &str, package: &str, data: &[u8], pin: &str) -> io::Result<()> {
    let index = index.replace("\r\n", "\n");
    let expected_path = format!("pool/main/t/tor/{package}");
    let size = data.len().to_string();
    let observed_hash = sha256(data);
    let mut matched = false;
    for stanza in index
        .split("\n\n")
        .filter(|stanza| !stanza.trim().is_empty())
    {
        let record = fields(stanza)?;
        let get = |field| record.get(field).map(String::as_str);
        if get("package") == Some("tor")
            && get("version") == Some(VERSION)
            && get("architecture") == Some("amd64")
        {
            if matched
                || get("filename") != Some(&expected_path)
                || get("size") != Some(&size)
                || get("sha256") != Some(pin)
                || observed_hash != pin
            {
                return Err(package_error());
            }
            matched = true;
        }
    }
    if !matched {
        return Err(package_error());
    }
    Ok(())
}

fn package_error() -> io::Error {
    io::Error::other("Pinned package/index/checksum mismatch; do not install")
}

#[cfg(test)]
mod tests {
    use super::*;
    use time::macros::datetime;

    fn release(index: &[u8], date: &str, extra: &str) -> String {
        format!(
            "Codename: bookworm\nDate: {date}\n{extra}SHA256:\n {} {} {INDEX_ENTRY}\n",
            sha256(index),
            index.len()
        )
    }

    fn package_index(package: &str, content: &[u8]) -> String {
        format!(
            "Package: tor\nVersion: {VERSION}\nArchitecture: amd64\n\
                 Filename: pool/main/t/tor/{package}\nSize: {}\nSHA256: {}\n",
            content.len(),
            sha256(content)
        )
    }

    #[test]
    fn freshness_index_size_and_digest_are_independent_checks() {
        let now = datetime!(2026-11-23 12:00 UTC);
        let fresh = "Mon, 23 Nov 2026 12:00:00 UTC";
        let index = b"Package: tor";
        assert!(verify_release(&release(index, fresh, ""), index, now).is_ok());
        for timestamp in [
            "Sun, 15 Nov 2026 12:00:00 UTC",
            "Mon, 23 Nov 2026 12:05:01 +0000",
            "Mon, 23 Nov 2026 12:00:00 -0000",
        ] {
            assert!(verify_release(&release(index, timestamp, ""), index, now).is_err());
        }
        assert!(
            verify_release(
                &release(index, fresh, "Valid-Until: Sun, 22 Nov 2026 12:00:00 GMT\n"),
                index,
                now
            )
            .is_err()
        );
        assert!(verify_release(&release(index, fresh, ""), b"Package: arm", now).is_err());
        let hash_line = format!(" {} {} {INDEX_ENTRY}\n", sha256(index), index.len());
        assert!(verify_release(&(release(index, fresh, "") + &hash_line), index, now).is_err());
    }

    #[test]
    fn rejects_duplicate_fields_and_package_records_and_checks_independent_pin() {
        assert!(fields("SHA256: one\nsha256: two\n").is_err());
        let package = format!("tor_{VERSION}_amd64.deb");
        let payload = b"Debian package test bytes";
        let index = package_index(&package, payload);
        let pin = sha256(payload);
        assert!(verify_package(&index.replace('\n', "\r\n"), &package, payload, &pin).is_ok());
        assert!(verify_package(&index, &package, b"changed", &pin).is_err());
        assert!(verify_package(&index, &package, payload, SHA256).is_err());
        assert!(verify_package(&(index.clone() + "\n" + &index), &package, payload, &pin).is_err());
        assert!(
            verify_package(
                &index.replace("pool/main/t/tor/", "../"),
                &package,
                payload,
                &pin
            )
            .is_err()
        );
    }

    #[test]
    fn refuses_overwrite_and_destinations_outside_runtime() {
        let root = tempfile::tempdir().unwrap();
        let path = root.path();
        assert!(checked_destination(path, Path::new("outside"), true).is_err());
        assert!(checked_destination(path, Path::new(".runtime/../outside"), true).is_err());
        let out = Path::new(".runtime/nested/stage");
        fs::create_dir_all(path.join(out)).unwrap();
        assert!(checked_destination(path, out, true).is_err());
        #[cfg(unix)]
        {
            std::os::unix::fs::symlink(path, path.join(".runtime/link")).unwrap();
            assert!(checked_destination(path, Path::new(".runtime/link/stage"), true).is_err());
        }
    }

    #[test]
    fn rejects_linked_and_oversized_input_before_processing() {
        let root = tempfile::tempdir().unwrap();
        let input = root.path().join("input");
        fs::write(&input, b"abc").unwrap();
        assert!(read_regular(&input, 2).is_err());
        assert_eq!(read_regular(&input, 3).unwrap(), b"abc");
        #[cfg(unix)]
        {
            let link = root.path().join("link");
            std::os::unix::fs::symlink(input, &link).unwrap();
            assert!(read_regular(&link, 3).is_err());
        }
    }

    #[test]
    fn actual_openpgp_chain_stages_verified_bytes_and_refuses_tampering() {
        let root = tempfile::tempdir().unwrap();
        let path = root.path();
        let signing = tempfile::tempdir().unwrap();
        let bundle = path.join("bundle");
        fs::create_dir(&bundle).unwrap();
        fs::create_dir_all(path.join("docker/m2")).unwrap();
        fs::write(path.join(DOCKERFILE), b"FROM scratch\n").unwrap();
        let gpg = || {
            let mut command = Command::new("gpg");
            command
                .args(["--no-options", "--homedir"])
                .arg(signing.path())
                .args(["--batch", "--pinentry-mode", "loopback", "--passphrase", ""]);
            command
        };
        let checked = |command: &mut Command| {
            let result = command
                .output()
                .expect("GnuPG is required for signature tests");
            assert!(
                result.status.success(),
                "{}",
                String::from_utf8_lossy(&result.stderr)
            );
            result.stdout
        };
        checked(gpg().args([
            "--quick-generate-key",
            "Test fixture <fixture@example.invalid>",
            "ed25519",
            "sign",
            "1d",
        ]));
        let _agent = TestAgent(signing.path());
        let listed = checked(gpg().args(["--with-colons", "--list-keys"]));
        let fingerprint = primary_fingerprints(utf8(&listed).unwrap()).unwrap()[0];
        fs::write(
            bundle.join(KEY),
            checked(gpg().args(["--armor", "--export", fingerprint])),
        )
        .unwrap();
        let data = b"Pinned test package";
        let package = format!("tor_{VERSION}_amd64.deb");
        let index = package_index(&package, data);
        fs::write(bundle.join(&package), data).unwrap();
        fs::write(bundle.join(INDEX), &index).unwrap();
        let now = OffsetDateTime::now_utc();
        let release_text = release(index.as_bytes(), &now.format(&Rfc2822).unwrap(), "");
        let release_path = path.join("Release");
        fs::write(&release_path, release_text).unwrap();
        checked(
            gpg()
                .arg("--output")
                .arg(bundle.join(SIGNED))
                .arg("--clearsign")
                .arg(release_path),
        );
        let pin = sha256(data);
        let pins = Candidate {
            fingerprint,
            sha256: &pin,
        };
        let out = Path::new(".runtime/nested/passed");
        let staged = verify_at(path, &bundle, out, &pins, now).unwrap();
        assert_eq!(fs::read(staged.join("tor-update.deb")).unwrap(), data);
        for name in [KEY, SIGNED, INDEX] {
            assert_eq!(
                fs::read(staged.join(name)).unwrap(),
                fs::read(bundle.join(name)).unwrap()
            );
        }
        assert_eq!(
            fs::read(staged.join("Dockerfile")).unwrap(),
            b"FROM scratch\n"
        );
        assert!(verify_at(path, &bundle, out, &pins, now).is_err());
        let wrong_pin = Candidate {
            fingerprint: FINGERPRINT,
            sha256: &pin,
        };
        let blocked = Path::new(".runtime/wrong-key");
        assert!(verify_at(path, &bundle, blocked, &wrong_pin, now).is_err());
        assert!(!path.join(blocked).exists());
        let signed_file = bundle.join(SIGNED);
        let signed = fs::read_to_string(&signed_file).unwrap();
        fs::write(signed_file, signed.replace("bookworm", "bullseye")).unwrap();
        let blocked = Path::new(".runtime/wrong-signature");
        let error = verify_at(path, &bundle, blocked, &pins, now).unwrap_err();
        assert_eq!(error.to_string(), "Invalid Tor repository signature");
        assert!(!path.join(blocked).exists());
    }

    // Test key generation starts a process scoped to this temporary home.
    struct TestAgent<'a>(&'a Path);
    impl Drop for TestAgent<'_> {
        fn drop(&mut self) {
            let _ = Command::new("gpgconf")
                .arg("--homedir")
                .arg(self.0)
                .args(["--kill", "gpg-agent"])
                .output();
        }
    }
}
