//! Local Compose lifecycle for the canonical deployment and isolated fixture.
//! The JVM CLI owns crawl approval, budgets, and execution; this module supplies deployment paths.
//! Authors: Стефан Колесников and GPT-6.1 Sol.

#[cfg(test)]
mod tests;

use serde_json::{Value, json};
use std::ffi::{OsStr, OsString};
use std::fs::{self, File};
use std::io::{self, Read, Seek, Write};
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};
use std::sync::Arc;
use std::sync::atomic::AtomicBool;
use std::time::Duration;

const STORM_IMAGE: &str = "onionall-storm:3.1.0-jre25-cleanup2";
const TOKEN: &str = "/state/admission-token";
const DESCRIPTOR: &str = "/state/runtime.json";
const FIXTURE_HOST: &str = "aeaqcaibaeaqcaibaeaqcaibaeaqcaibaeaqcaibaeaqcaibaea37ead.onion";
const FOREIGN_HOST: &str = "aibaeaqcaibaeaqcaibaeaqcaibaeaqcaibaeaqcaibaeaqcaibejsqd.onion";
const DOCKER_UNAVAILABLE: &str = "Docker Engine/Compose unavailable. Start Docker and check docker info; no services or data were removed.";
const INVALID_PROXY: &str = "Invalid runtime SOCKS configuration; no default fallback.";
const NOT_PRIVATE: &str = "Admission token must be a private regular file (mode 600).";
const USAGE: &str = "Usage: ./onionall [--fixture] {up,status,down,cli} [CLI arguments...]";
const CAPTURE_LIMIT: u64 = 1024 * 1024;

type Environment = Vec<(&'static str, OsString)>;
type Result<T> = std::result::Result<T, Failure>;

#[derive(Debug)]
enum Failure {
    Message(&'static str),
    Io(io::Error),
    Exit(i32),
}

impl From<io::Error> for Failure {
    fn from(error: io::Error) -> Self {
        Self::Io(error)
    }
}

impl From<serde_json::Error> for Failure {
    fn from(error: serde_json::Error) -> Self {
        Self::Io(io::Error::from(error))
    }
}

#[derive(Clone, Copy)]
enum Mode {
    Tor,
    Fixture,
}

impl Mode {
    fn profile(self) -> &'static str {
        match self {
            Self::Tor => "tor",
            Self::Fixture => "fixture",
        }
    }

    fn project(self) -> &'static str {
        match self {
            Self::Tor => "onionall-runtime",
            Self::Fixture => "onionall-runtime-fixture",
        }
    }

    fn state_directory(self) -> &'static str {
        match self {
            Self::Tor => ".runtime/local",
            Self::Fixture => ".runtime/local-fixture",
        }
    }

    fn other_project(self) -> &'static str {
        match self {
            Self::Tor => Self::Fixture.project(),
            Self::Fixture => Self::Tor.project(),
        }
    }
}

#[derive(Clone, Copy)]
enum Action {
    Up,
    Status,
    Down,
    Cli,
}

impl Action {
    fn label(self) -> &'static str {
        match self {
            Self::Up => "up",
            Self::Status => "status",
            Self::Down => "down",
            Self::Cli => "cli",
        }
    }
}

enum Invocation<'a> {
    Help,
    Operation(Mode, Action, &'a [OsString]),
}

fn parse(args: &[OsString]) -> Option<Invocation<'_>> {
    let (mode, args) = match args {
        [flag, rest @ ..] if flag == "--fixture" => (Mode::Fixture, rest),
        _ => (Mode::Tor, args),
    };
    let (name, rest) = args.split_first()?;
    let help = |argument: &OsStr| argument == "-h" || argument == "--help";
    if help(name) && rest.is_empty() {
        return Some(Invocation::Help);
    }
    let action = match name.to_str()? {
        "up" => Action::Up,
        "status" => Action::Status,
        "down" => Action::Down,
        "cli" => Action::Cli,
        _ => return None,
    };
    if rest.first().is_some_and(|arg| help(arg)) {
        return Some(Invocation::Help);
    }
    (matches!(action, Action::Cli) || rest.is_empty())
        .then_some(Invocation::Operation(mode, action, rest))
}

pub fn run(args: &[OsString]) -> i32 {
    let (mode, action, arguments) = match parse(args) {
        Some(Invocation::Operation(mode, action, args)) => (mode, action, args),
        Some(Invocation::Help) => {
            println!("{USAGE}");
            return 0;
        }
        None => {
            eprintln!("{USAGE}");
            return 2;
        }
    };

    let root = match std::env::current_dir() {
        Ok(root) if root.join("compose.runtime.yaml").is_file() => root,
        _ => {
            eprintln!("Use ./onionall from the project checkout.");
            return 1;
        }
    };
    let user = match compose_user() {
        Ok(user) => user,
        Err(error) => {
            eprintln!("{error}");
            return 1;
        }
    };
    let cancelled = Arc::new(AtomicBool::new(false));
    let signal = cancelled.clone();
    if ctrlc::set_handler(move || signal.store(true, std::sync::atomic::Ordering::Relaxed)).is_err()
    {
        eprintln!("Could not initialize runtime cancellation.");
        return 1;
    }
    let mut runtime = Runtime::new(&root, mode, user, Engine { cancelled });
    let mut out = io::stdout();
    let result = match action {
        Action::Up => runtime.up(&mut out),
        Action::Status => runtime.status(&mut out),
        Action::Down => runtime.down(&mut out),
        Action::Cli => runtime.cli(arguments),
    };
    match result {
        Ok(()) => 0,
        Err(Failure::Message(message)) => {
            eprintln!("{message}");
            1
        }
        Err(Failure::Io(error)) => {
            eprintln!("{error}");
            if error.kind() == io::ErrorKind::Interrupted {
                130
            } else {
                1
            }
        }
        Err(Failure::Exit(code)) => {
            eprintln!(
                "{} failed (exit {code}). Data retained at {}. For runtime failures check status and private bridge configuration; for build failures inspect the preceding compiler/test output.",
                action.label(),
                runtime.state.display()
            );
            code.max(1)
        }
    }
}

#[cfg(unix)]
fn compose_user() -> io::Result<OsString> {
    Ok(format!("{}:{}", nix::unistd::getuid(), nix::unistd::getgid()).into())
}

#[cfg(not(unix))]
fn compose_user() -> io::Result<OsString> {
    Err(io::Error::new(
        io::ErrorKind::Unsupported,
        "The canonical runtime controller requires the Linux toolchain container.",
    ))
}

trait Docker {
    fn run(
        &self,
        root: &Path,
        args: &[OsString],
        env: &[(&'static str, OsString)],
        timeout: Duration,
        capture: bool,
    ) -> Result<String>;
}

struct Engine {
    cancelled: Arc<AtomicBool>,
}

impl Docker for Engine {
    fn run(
        &self,
        root: &Path,
        args: &[OsString],
        env: &[(&'static str, OsString)],
        timeout: Duration,
        capture: bool,
    ) -> Result<String> {
        if self.cancelled.load(std::sync::atomic::Ordering::Relaxed) {
            return Err(io::Error::new(io::ErrorKind::Interrupted, "Runtime interrupted").into());
        }
        let mut command = Command::new("docker");
        command
            .current_dir(root)
            .args(args)
            .envs(env.iter().map(|(k, v)| (k, v)));
        let mut captured = if capture {
            let file = tempfile::tempfile()?;
            command.stdout(file.try_clone()?).stderr(Stdio::null());
            Some(file)
        } else {
            None
        };
        let mut child = command.spawn()?;
        let exit = crate::process::wait_child(&mut child, timeout, Some(&self.cancelled))?;
        if !exit.success() {
            return Err(Failure::Exit(exit.code().unwrap_or(1)));
        }
        let mut output = String::new();
        if let Some(file) = &mut captured {
            file.rewind()?;
            file.take(CAPTURE_LIMIT + 1).read_to_string(&mut output)?;
            if output.len() as u64 > CAPTURE_LIMIT {
                return Err(Failure::Message(
                    "Docker output exceeded the observation limit.",
                ));
            }
        }
        Ok(output)
    }
}

struct Runtime<'a, D> {
    root: &'a Path,
    mode: Mode,
    state: PathBuf,
    engine: D,
    env: Environment,
}

impl<'a, D: Docker> Runtime<'a, D> {
    fn new(root: &'a Path, mode: Mode, user: OsString, engine: D) -> Self {
        let state = root.join(mode.state_directory());
        Self {
            root,
            mode,
            env: vec![
                ("ONIONALL_RUNTIME_DIR", state.clone().into_os_string()),
                ("ONIONALL_RUNTIME_USER", user),
            ],
            state,
            engine,
        }
    }

    fn docker<T: AsRef<OsStr>>(&self, args: &[T], seconds: u64, capture: bool) -> Result<String> {
        let args: Vec<_> = args.iter().map(|value| value.as_ref().to_owned()).collect();
        self.engine
            .run(self.root, &args, &[], Duration::from_secs(seconds), capture)
    }

    fn compose<T: AsRef<OsStr>>(&self, args: &[T], seconds: u64, capture: bool) -> Result<String> {
        let mut command = vec![
            "compose".into(),
            "-p".into(),
            self.mode.project().into(),
            "-f".into(),
            self.root.join("compose.runtime.yaml").into_os_string(),
            "--profile".into(),
            self.mode.profile().into(),
        ];
        command.extend(args.iter().map(|value| value.as_ref().to_owned()));
        self.engine.run(
            self.root,
            &command,
            &self.env,
            Duration::from_secs(seconds),
            capture,
        )
    }

    fn check_docker(&self) -> Result<()> {
        let info = || {
            self.docker(&["info", "--format", "{{.ServerVersion}}"], 15, true)?;
            self.docker(&["compose", "version"], 15, true)?;
            Ok(())
        };
        match info() {
            Err(Failure::Io(error)) if error.kind() == io::ErrorKind::Interrupted => {
                Err(error.into())
            }
            Err(_) => Err(Failure::Message(DOCKER_UNAVAILABLE)),
            success => success,
        }
    }

    fn prepare(&self) -> Result<()> {
        for artifact in [
            "target/onionall/bin/onionall",
            "target/onionall-brawler-0.1.0-SNAPSHOT.jar",
            "frontier-server/target/urlfrontier-admission.jar",
        ] {
            if !self.root.join(artifact).is_file() {
                return Err(Failure::Message(
                    "Missing build artifact; run ./onionall build first.",
                ));
            }
        }
        private_directory(&self.state)?;
        let token = self.state.join("admission-token");
        if matches!(fs::symlink_metadata(&token), Err(error) if error.kind() == io::ErrorKind::NotFound)
        {
            create_admission_token(&self.state, &token)?;
        }
        let metadata = fs::symlink_metadata(&token)?;
        if !private_file(&metadata) {
            return Err(Failure::Message(NOT_PRIVATE));
        }
        let path = self.state.join("runtime.json");
        let fixture = matches!(self.mode, Mode::Fixture);
        let (host, port) = match fs::symlink_metadata(&path) {
            Ok(_) => self.configured_proxy()?,
            Err(error) if error.kind() == io::ErrorKind::NotFound => {
                (if fixture { "socks-fixture" } else { "tor" }.into(), 9050)
            }
            Err(error) => return Err(error.into()),
        };
        let descriptor = json!({
            "frontier_host": "frontier",
            "frontier_port": 7071,
            "socks_host": host,
            "socks_port": port,
            "storm_executable": "/apache-storm/bin/storm",
            "crawler_jar": "/app/crawler.jar",
            "work_directory": "/state/execution",
            "seconds": if fixture { 120 } else { 180 },
            "http_timeout_ms": if fixture { 2000 } else { 20000 },
            "retry_minutes": if fixture { 1 } else { 120 },
        });
        // Readers outside this launcher should never see a partially written descriptor.
        let mut temporary = tempfile::NamedTempFile::new_in(&self.state)?;
        write_json(&mut temporary, &descriptor)?;
        temporary.as_file().sync_all()?;
        temporary.persist(&path).map_err(|error| error.error)?;
        if fixture {
            fs::write(self.state.join("onion-hostname.txt"), FIXTURE_HOST)?;
            fs::write(self.state.join("foreign-hostname.txt"), FOREIGN_HOST)?;
        }
        Ok(())
    }

    fn configured_proxy(&self) -> Result<(String, u16)> {
        let path = self.state.join("runtime.json");
        if !matches!(fs::symlink_metadata(&path), Ok(metadata) if metadata.is_file()) {
            return Err(Failure::Message(
                "Require regular runtime descriptor; run up first.",
            ));
        }
        read_proxy(&path).ok_or(Failure::Message(INVALID_PROXY))
    }

    fn up(&mut self, out: &mut impl Write) -> Result<()> {
        self.check_docker()?;
        let label = format!(
            "label=com.docker.compose.project={}",
            self.mode.other_project()
        );
        if !self
            .docker(&["ps", "-q", "--filter", &label], 180, true)?
            .trim()
            .is_empty()
        {
            return Err(Failure::Message(
                "Another canonical runtime mode is active. Stop it before switching mode.",
            ));
        }
        self.prepare()?;
        let image = std::env::var_os("ONIONALL_STORM_IMAGE").unwrap_or_else(|| STORM_IMAGE.into());
        let inspected = self.docker(
            &[
                OsStr::new("image"),
                OsStr::new("inspect"),
                &image,
                OsStr::new("--format"),
                OsStr::new(r#"{{index .Config.Labels "dev.onionall.storm.lifecycle-fix"}}"#),
            ],
            180,
            true,
        );
        let label = match inspected {
            Err(Failure::Exit(_)) => {
                return Err(Failure::Message(
                    "Patched Storm image missing; run ./onionall build --images.",
                ));
            }
            result => result?,
        };
        if label.trim() != "cleanup-submit-lock-2" {
            return Err(Failure::Message(
                "Expected pinned Storm cleanup2 image; run ./onionall build --images.",
            ));
        }
        let transport = match self.mode {
            Mode::Fixture => "socks-fixture",
            Mode::Tor => {
                let bridge = std::env::var_os("ONIONALL_BRIDGES_FILE")
                    .map(PathBuf::from)
                    .unwrap_or_else(|| self.root.join(".secrets/m2/bridges.conf"));
                let bridge = self.root.join(bridge).canonicalize().ok().filter(|path| path.is_file())
                    .ok_or(Failure::Message("Set ONIONALL_BRIDGES_FILE to your private obfs4 bridge file; see docker/m2/bridges.example.conf. No direct Tor fallback."))?;
                self.env
                    .push(("ONIONALL_BRIDGES_FILE", bridge.into_os_string()));
                "tor"
            }
        };
        self.compose(
            &[
                "up",
                "-d",
                "--no-build",
                "--pull",
                "never",
                "--wait",
                "--wait-timeout",
                "120",
                "frontier",
                transport,
            ],
            150,
            false,
        )?;
        self.status(out)
    }

    fn status(&self, out: &mut impl Write) -> Result<()> {
        self.check_docker()?;
        self.compose(&["ps", "--all"], 180, false)?;
        self.compose(
            &[
                "run",
                "--rm",
                "--no-deps",
                "probe",
                "frontier",
                "frontier",
                "7071",
                TOKEN,
            ],
            35,
            false,
        )?;
        let fixture = matches!(self.mode, Mode::Fixture);
        if !fixture {
            self.compose(
                &[
                    "exec",
                    "-T",
                    "tor",
                    "python3",
                    "/usr/local/lib/tor-health.py",
                ],
                15,
                false,
            )?;
        }
        let (host, port) = self.configured_proxy()?;
        self.compose(
            &[
                "run",
                "--rm",
                "--no-deps",
                "probe",
                "socks",
                &host,
                &port.to_string(),
            ],
            25,
            false,
        )?;
        self.compose(&["run", "--rm", "--no-deps", "crawler", "--help"], 25, true)?;
        self.compose(
            &[
                "run",
                "--rm",
                "--no-deps",
                "--entrypoint",
                "/apache-storm/bin/storm",
                "crawler",
                "version",
            ],
            25,
            false,
        )?;
        let tor = if fixture {
            "NOT_RUN_FIXTURE"
        } else if host == "tor" && port == 9050 {
            "BOOTSTRAP_100_SOCKS_READY"
        } else {
            "MANAGED_TOR_BOOTSTRAP_100_CONFIGURED_PROXY_IDENTITY_UNVERIFIED"
        };
        write_json(
            out,
            &json!({
                "frontier": "READY_AUTHENTICATED",
                "crawler": "PREPARED",
                "tor": tor,
                "socks_host": host,
                "socks_port": port,
                "data": self.state,
                "project": self.mode.project(),
            }),
        )?;
        if fixture {
            writeln!(out, "Controlled seed: http://{FIXTURE_HOST}/a")?;
        }
        Ok(())
    }

    fn cli(&self, arguments: &[OsString]) -> Result<()> {
        self.check_docker()?;
        let (args, timeout) = forward_cli(arguments);
        self.compose(&args, timeout, false)?;
        Ok(())
    }

    fn down(&self, out: &mut impl Write) -> Result<()> {
        self.check_docker()?;
        let project = format!("label=com.docker.compose.project={}", self.mode.project());
        let workers = self.docker(
            &[
                "ps",
                "-q",
                "--filter",
                &project,
                "--filter",
                "label=com.docker.compose.service=crawler",
            ],
            180,
            true,
        )?;
        let ids: Vec<_> = workers.split_whitespace().collect();
        if ids.iter().any(|id| {
            !matches!(id.len(), 12 | 64)
                || !id
                    .bytes()
                    .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
        }) {
            return Err(Failure::Message(
                "Docker returned an invalid crawler container ID.",
            ));
        }
        if !ids.is_empty() {
            let mut stop = vec!["stop", "--time", "120"];
            stop.extend(ids);
            self.docker(&stop, 150, false)?;
        }
        self.compose(&["down", "--timeout", "30"], 90, false)?;
        writeln!(
            out,
            "Stopped; Frontier, Tor identity, archive and execution data retained."
        )?;
        Ok(())
    }
}

/// Publish the whole capability at once and fail if another writer already won.
fn create_admission_token(directory: &Path, token: &Path) -> io::Result<()> {
    let mut entropy = [0_u8; 32];
    getrandom::fill(&mut entropy)
        .map_err(|error| io::Error::other(format!("Could not create admission token: {error}")))?;
    let mut temporary = tempfile::NamedTempFile::new_in(directory)?;
    for byte in entropy {
        write!(temporary, "{byte:02x}")?;
    }
    temporary.as_file().sync_all()?;
    temporary
        .persist_noclobber(token)
        .map_err(|error| error.error)?;
    Ok(())
}

#[cfg(unix)]
fn private_file(metadata: &fs::Metadata) -> bool {
    use std::os::unix::fs::PermissionsExt;
    metadata.is_file() && metadata.permissions().mode() & 0o077 == 0
}

#[cfg(not(unix))]
fn private_file(_metadata: &fs::Metadata) -> bool {
    false
}

fn private_directory(path: &Path) -> io::Result<()> {
    let builder = fs::DirBuilder::new();
    #[cfg(unix)]
    let builder = {
        use std::os::unix::fs::DirBuilderExt;
        let mut builder = builder;
        builder.mode(0o700);
        builder
    };
    let parent = path
        .parent()
        .ok_or_else(|| io::Error::other("Runtime state path has no parent."))?;
    fs::create_dir_all(parent)?;
    match builder.create(path) {
        Err(error) if error.kind() == io::ErrorKind::AlreadyExists && path.is_dir() => Ok(()),
        result => result,
    }
}

fn write_json(out: &mut impl Write, value: &Value) -> Result<()> {
    serde_json::to_writer_pretty(&mut *out, value)?;
    writeln!(out)?;
    Ok(())
}

fn read_proxy(path: &Path) -> Option<(String, u16)> {
    let mut data = Vec::new();
    File::open(path)
        .ok()?
        .take(32769)
        .read_to_end(&mut data)
        .ok()?;
    if data.len() > 32768 {
        return None;
    }
    let value: Value = serde_json::from_slice(&data).ok()?;
    let host = value.get("socks_host")?.as_str()?;
    let bytes = host.as_bytes();
    if bytes.len() > 253
        || !bytes.first().is_some_and(u8::is_ascii_alphanumeric)
        || !bytes.last().is_some_and(u8::is_ascii_alphanumeric)
        || !bytes
            .iter()
            .all(|b| b.is_ascii_alphanumeric() || matches!(b, b'.' | b'-'))
        || host.to_ascii_lowercase().ends_with(".onion")
    {
        return None;
    }
    let port = u16::try_from(value.get("socks_port")?.as_u64()?).ok()?;
    (port != 0).then(|| (host.into(), port))
}

fn forward_cli(arguments: &[OsString]) -> (Vec<OsString>, u64) {
    let names = command_path(arguments);
    let command = names.as_slice();
    let capability = matches!(
        command,
        ["crawl", "start" | "status"]
            | ["research", "run" | "results" | "status"]
            | ["research", "thread", "crawl"]
    );
    let runtime_only = matches!(
        command,
        ["config", "proxy"] | ["system", "doctor"] | ["research", "discover"]
    );
    let mut args = ["run", "--rm", "--no-deps", "crawler"]
        .map(Into::into)
        .to_vec();
    if arguments.is_empty() {
        args.push("--help".into());
    }
    args.extend_from_slice(arguments);
    if (capability || runtime_only) && !contains_option(arguments, "--runtime") {
        args.extend(["--runtime", DESCRIPTOR].map(Into::into));
    }
    if capability && !contains_option(arguments, "--admission-token-file") {
        args.extend(["--admission-token-file", TOKEN].map(Into::into));
    }
    let mut timeout = 740;
    if command == ["research", "thread", "crawl"]
        || (command == ["research", "run"] && contains_option(arguments, "--session"))
    {
        // Invalid values remain JVM CLI input. This bounds only the launcher wait.
        let mut budget = 600_i64;
        for (index, arg) in arguments.iter().enumerate() {
            let value = if arg == "--total-seconds" {
                arguments.get(index + 1).and_then(|arg| arg.to_str())
            } else {
                arg.to_str()
                    .and_then(|argument| argument.strip_prefix("--total-seconds="))
            };
            if let Some(parsed) = value.and_then(|value| value.trim().parse::<i64>().ok()) {
                budget = parsed;
            }
        }
        timeout = 740.max(budget.clamp(30, 3600).unsigned_abs() + 240);
    }
    (args, timeout)
}

fn contains_option(arguments: &[OsString], name: &str) -> bool {
    arguments.iter().any(|arg| {
        let bytes = arg.as_encoded_bytes();
        bytes == name.as_bytes()
            || bytes
                .strip_prefix(name.as_bytes())
                .is_some_and(|tail| tail.first() == Some(&b'='))
    })
}

fn command_path(args: &[OsString]) -> Vec<&str> {
    let mut path = Vec::new();
    let mut args = args.iter();
    while path.len()
        < if path.starts_with(&["research", "thread"]) {
            3
        } else {
            2
        }
    {
        let Some(arg) = args.next() else { break };
        let Some(arg) = arg.to_str() else {
            return Vec::new();
        };
        let (option, value) = arg.split_once('=').unwrap_or((arg, ""));
        match option {
            "--archive-dir" | "--download-dir" | "--max-bytes" | "--socks-port"
            | "--timeout-ms" => {
                if value.is_empty() && !arg.ends_with('=') {
                    args.next();
                }
            }
            "--json" | "--no-color" => {}
            _ if option.starts_with('-') => return Vec::new(),
            _ => path.push(arg),
        }
    }
    path
}
