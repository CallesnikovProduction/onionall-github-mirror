use super::*;
use std::cell::RefCell;

const WORKER: &str = "0123456789ab";
const STORM_LABEL: &str = "cleanup-submit-lock-2";

#[derive(Default)]
struct FakeDocker {
    calls: RefCell<Vec<Call>>,
    workers: &'static str,
    busy: bool,
    fail: Option<&'static str>,
}

struct Call {
    args: Vec<OsString>,
    env: Environment,
    seconds: u64,
    capture: bool,
}

impl Call {
    fn action(&self) -> &OsStr {
        if self.args.get(1).is_some_and(|arg| arg == "-p") {
            &self.args[7]
        } else {
            &self.args[0]
        }
    }

    fn includes(&self, value: &str) -> bool {
        self.args.iter().any(|arg| arg == value)
    }
}

impl Docker for FakeDocker {
    fn run(
        &self,
        _root: &Path,
        args: &[OsString],
        env: &[(&'static str, OsString)],
        timeout: Duration,
        capture: bool,
    ) -> Result<String> {
        let call = Call {
            args: args.to_vec(),
            env: env.to_vec(),
            seconds: timeout.as_secs(),
            capture,
        };
        let result = match call.action().to_str() {
            Some(action) if Some(action) == self.fail => Err(Failure::Exit(17)),
            Some("ps") if call.includes("label=com.docker.compose.service=crawler") => {
                Ok(self.workers.to_owned())
            }
            Some("ps") if call.includes("--filter") => {
                Ok(if self.busy { WORKER } else { "" }.to_owned())
            }
            Some("image") => Ok(STORM_LABEL.to_owned()),
            _ => Ok(String::new()),
        };
        self.calls.borrow_mut().push(call);
        result
    }
}

fn arguments(args: &[&str]) -> Vec<OsString> {
    args.iter().map(|value| (*value).into()).collect()
}

fn setup(root: &Path, mode: Mode, docker: FakeDocker) -> Runtime<'_, FakeDocker> {
    Runtime::new(root, mode, "1000:1000".into(), docker)
}

#[cfg(unix)]
fn fake_build_artifacts(root: &Path) -> io::Result<()> {
    for name in [
        "target/onionall/bin/onionall",
        "target/onionall-brawler-0.1.0-SNAPSHOT.jar",
        "frontier-server/target/urlfrontier-admission.jar",
    ] {
        let path = root.join(name);
        fs::create_dir_all(path.parent().unwrap())?;
        File::create(path)?;
    }
    Ok(())
}

#[test]
fn deployment_arguments_and_timeouts_are_scoped_to_known_jvm_commands() {
    for prefix in [
        vec!["--json"],
        vec!["--archive-dir", "research"],
        vec!["--archive-dir=/custom", "--no-color"],
    ] {
        for (command, token) in [
            (["crawl", "status"], true),
            (["research", "run"], true),
            (["research", "results"], true),
            (["research", "status"], true),
            (["config", "proxy"], false),
            (["system", "doctor"], false),
            (["research", "discover"], false),
        ] {
            let argv = [prefix.as_slice(), &command].concat();
            let (forwarded, _) = forward_cli(&arguments(&argv));
            assert!(forwarded.contains(&DESCRIPTOR.into()), "{argv:?}");
            assert_eq!(token, forwarded.contains(&TOKEN.into()), "{argv:?}");
            assert!(!forwarded.contains(&"--approve".into()), "{argv:?}");
        }
    }
    let (forwarded, timeout) = forward_cli(&arguments(&[
        "--json",
        "research",
        "thread",
        "crawl",
        "session",
        "--total-seconds",
        "900",
        "--runtime=/chosen/runtime.json",
        "--admission-token-file=/chosen/token",
    ]));
    assert_eq!(1140, timeout);
    assert!(forwarded.contains(&"--runtime=/chosen/runtime.json".into()));
    assert!(forwarded.contains(&"--admission-token-file=/chosen/token".into()));
    assert!(!forwarded.contains(&DESCRIPTOR.into()));
    assert!(!forwarded.contains(&TOKEN.into()));

    for argv in [
        ["--json", "research", "thread", "plan"],
        ["--json", "research", "search-local", "run"],
        ["--unknown", "research", "thread", "crawl"],
    ] {
        let (forwarded, timeout) = forward_cli(&arguments(&argv));
        assert!(!forwarded.contains(&DESCRIPTOR.into()), "{argv:?}");
        assert!(!forwarded.contains(&TOKEN.into()), "{argv:?}");
        assert_eq!(740, timeout);
    }
    let (_, timeout) = forward_cli(&arguments(&[
        "research",
        "run",
        "--session",
        "session",
        "--total-seconds=1200",
    ]));
    assert_eq!(1440, timeout);
    let (_, timeout) = forward_cli(&arguments(&[
        "research",
        "run",
        "--session=1",
        "--total-seconds=100000",
    ]));
    assert_eq!(3840, timeout);
}

#[test]
fn scoped_workers_stop_before_frontier_and_failed_stop_blocks_teardown() -> io::Result<()> {
    let root = tempfile::tempdir()?;
    let controller = setup(
        root.path(),
        Mode::Tor,
        FakeDocker {
            workers: WORKER,
            ..Default::default()
        },
    );
    controller.down(&mut Vec::new()).unwrap();
    let observed = controller.engine.calls.borrow();
    assert_eq!(
        observed.iter().map(Call::action).collect::<Vec<_>>(),
        ["info", "compose", "ps", "stop", "down"]
    );
    assert!(observed[2].includes("label=com.docker.compose.project=onionall-runtime"));
    assert!(observed[2].includes("label=com.docker.compose.service=crawler"));
    assert!(observed[2].capture);
    assert!(
        observed[4]
            .env
            .iter()
            .any(|(name, value)| *name == "ONIONALL_RUNTIME_USER" && value == "1000:1000")
    );
    assert_eq!(
        observed[3].args,
        arguments(&["stop", "--time", "120", WORKER])
    );
    assert_eq!(observed[3].seconds, 150);
    assert!(
        !observed
            .iter()
            .any(|call| call.includes("-v") || call.includes("--volumes"))
    );

    let failing = setup(
        root.path(),
        Mode::Fixture,
        FakeDocker {
            workers: WORKER,
            fail: Some("stop"),
            ..Default::default()
        },
    );
    assert!(matches!(
        failing.down(&mut Vec::new()),
        Err(Failure::Exit(17))
    ));
    assert!(
        !failing
            .engine
            .calls
            .borrow()
            .iter()
            .any(|call| call.action() == "down")
    );

    let invalid = setup(
        root.path(),
        Mode::Fixture,
        FakeDocker {
            workers: "--all",
            ..Default::default()
        },
    );
    assert!(matches!(
        invalid.down(&mut Vec::new()),
        Err(Failure::Message(
            "Docker returned an invalid crawler container ID."
        ))
    ));
    assert!(
        !invalid
            .engine
            .calls
            .borrow()
            .iter()
            .any(|call| ["stop", "down"].iter().any(|name| call.action() == *name))
    );
    Ok(())
}

#[test]
fn unavailable_engine_and_other_mode_create_no_state() -> io::Result<()> {
    let root = tempfile::tempdir()?;
    let mut failed = setup(
        root.path(),
        Mode::Fixture,
        FakeDocker {
            fail: Some("info"),
            ..Default::default()
        },
    );
    assert!(matches!(
        failed.up(&mut Vec::new()),
        Err(Failure::Message(DOCKER_UNAVAILABLE))
    ));
    assert_eq!(1, failed.engine.calls.borrow().len());
    assert!(!failed.state.exists());

    let mut busy = setup(
        root.path(),
        Mode::Fixture,
        FakeDocker {
            busy: true,
            ..Default::default()
        },
    );
    assert!(matches!(
        busy.up(&mut Vec::new()),
        Err(Failure::Message(
            "Another canonical runtime mode is active. Stop it before switching mode."
        ))
    ));
    assert_eq!(3, busy.engine.calls.borrow().len());
    let calls = busy.engine.calls.borrow();
    assert!(calls[2].includes("label=com.docker.compose.project=onionall-runtime"));
    assert!(!busy.state.exists());
    Ok(())
}

#[cfg(unix)]
#[test]
fn fixture_prepares_private_token_and_uses_only_fixture_transport() -> io::Result<()> {
    use std::os::unix::fs::PermissionsExt;
    let root = tempfile::tempdir()?;
    fake_build_artifacts(root.path())?;
    let mut controller = setup(root.path(), Mode::Fixture, FakeDocker::default());
    let mut status = Vec::new();
    controller.up(&mut status).unwrap();
    let token = controller.state.join("admission-token");
    let first = fs::read_to_string(&token)?;
    assert_eq!(64, first.len());
    assert!(
        first
            .bytes()
            .all(|byte| byte.is_ascii_digit() || (b'a'..=b'f').contains(&byte))
    );
    assert_eq!(0o600, fs::metadata(&token)?.permissions().mode() & 0o777);
    controller.prepare().unwrap();
    assert_eq!(first, fs::read_to_string(token)?);
    assert_eq!(
        FIXTURE_HOST,
        fs::read_to_string(controller.state.join("onion-hostname.txt"))?
    );
    assert_eq!(
        FOREIGN_HOST,
        fs::read_to_string(controller.state.join("foreign-hostname.txt"))?
    );
    let observed = controller.engine.calls.borrow();
    let up = observed.iter().find(|call| call.action() == "up").unwrap();
    assert!(up.includes("socks-fixture") && !up.includes("tor") && up.includes("never"));
    assert!(
        up.env
            .iter()
            .any(|(name, value)| *name == "ONIONALL_RUNTIME_USER" && value == "1000:1000")
    );
    assert!(
        observed
            .iter()
            .any(|call| call.includes("socks") && call.includes("socks-fixture"))
    );
    assert!(
        observed
            .iter()
            .any(|call| call.includes("--help") && call.capture)
    );
    assert!(!observed.iter().any(|call| call.includes("tor")));
    let report = String::from_utf8(status).unwrap();
    assert!(report.contains("READY_AUTHENTICATED"));
    assert!(report.contains("NOT_RUN_FIXTURE"));
    assert!(report.contains(&format!("Controlled seed: http://{FIXTURE_HOST}/a")));
    Ok(())
}

#[cfg(unix)]
#[test]
fn descriptor_validations_preserve_operator_proxy_and_fail_closed() -> io::Result<()> {
    use std::os::unix::fs::symlink;
    let root = tempfile::tempdir()?;
    fake_build_artifacts(root.path())?;
    let controller = setup(root.path(), Mode::Fixture, FakeDocker::default());
    private_directory(&controller.state)?;
    let descriptor = controller.state.join("runtime.json");
    fs::write(
        &descriptor,
        r#"{"socks_host":"operator-gateway","socks_port":9150}"#,
    )?;
    controller.prepare().unwrap();
    assert_eq!(
        ("operator-gateway".into(), 9150),
        controller.configured_proxy().unwrap()
    );
    for invalid in [
        r#"{"socks_host":"x.ONION","socks_port":9050}"#,
        r#"{"socks_host":"127.0.0.1","socks_port":true}"#,
        r#"{"socks_host":"[::1]","socks_port":9050}"#,
        r#"{"socks_host":"localhost","socks_port":65536}"#,
    ] {
        fs::write(&descriptor, invalid)?;
        assert!(matches!(
            controller.prepare(),
            Err(Failure::Message(INVALID_PROXY))
        ));
        assert_eq!(invalid, fs::read_to_string(&descriptor)?);
    }
    fs::remove_file(&descriptor)?;
    let external = root.path().join("external.json");
    fs::write(&external, r#"{"socks_host":"localhost","socks_port":9050}"#)?;
    symlink(&external, &descriptor)?;
    assert!(matches!(
        controller.configured_proxy(),
        Err(Failure::Message(
            "Require regular runtime descriptor; run up first."
        ))
    ));
    assert!(controller.prepare().is_err());
    assert_eq!(
        r#"{"socks_host":"localhost","socks_port":9050}"#,
        fs::read_to_string(external)?
    );
    Ok(())
}

#[cfg(unix)]
#[test]
fn token_symlink_is_rejected_without_touching_its_target() -> io::Result<()> {
    use std::os::unix::fs::symlink;
    let root = tempfile::tempdir()?;
    fake_build_artifacts(root.path())?;
    let controller = setup(root.path(), Mode::Fixture, FakeDocker::default());
    private_directory(&controller.state)?;
    let external = root.path().join("existing");
    fs::write(&external, "owned by someone else")?;
    symlink(&external, controller.state.join("admission-token"))?;
    assert!(matches!(
        controller.prepare(),
        Err(Failure::Message(NOT_PRIVATE))
    ));
    assert_eq!("owned by someone else", fs::read_to_string(external)?);
    assert!(!controller.state.join("runtime.json").exists());
    Ok(())
}

#[test]
fn cli_parser_separates_host_mode_from_jvm_arguments() {
    let argv = arguments(&[
        "--fixture",
        "cli",
        "--archive-dir",
        "paths with spaces",
        "research",
        "status",
    ]);
    let Some(Invocation::Operation(Mode::Fixture, Action::Cli, forwarded)) = parse(&argv) else {
        panic!("valid invocation was rejected")
    };
    assert_eq!(forwarded, &argv[2..]);
    assert!(matches!(
        parse(&arguments(&["status", "--help"])),
        Some(Invocation::Help)
    ));
    assert!(parse(&arguments(&["status", "--unknown"])).is_none());
}

#[cfg(unix)]
#[test]
fn exclusive_publication_keeps_a_token_created_after_preflight() -> io::Result<()> {
    let root = tempfile::tempdir()?;
    let state = root.path().join("local-fixture");
    private_directory(&state)?;
    let token = state.join("admission-token");
    assert!(!token.exists());

    // A competing publisher has won since the absence check.
    fs::write(&token, "existing-fixture-capability")?;
    let refused = create_admission_token(&state, &token).unwrap_err();
    assert_eq!(refused.kind(), io::ErrorKind::AlreadyExists);
    assert_eq!("existing-fixture-capability", fs::read_to_string(token)?);
    assert!(!state.join("runtime.json").exists());
    Ok(())
}
