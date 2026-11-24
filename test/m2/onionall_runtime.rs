//! Offline controller characterizations with synthetic evidence and mock Docker.
//! Authors: Стефан Колесников and GPT-6.1 Sol.

use crate::docker::{Context, Docker, Outcome};
use crate::{evidence, recover, supervise};
use serde_json::{Value, json};
use std::cell::RefCell;
use std::collections::VecDeque;
use std::fs;
use std::io;
use std::path::{Path, PathBuf};

const PROJECT: &str = "onionall-brawler-m2-123456abcdef";
const GATEWAY: &str = "0123456789ab";
const ONE: &str = "aaaaaaaaaaaa";
const TWO: &str = "bbbbbbbbbbbb";
const SOCKS: &str = "Caused by: java.io.IOException: SOCKS_FAILURE: example\r\n";

struct Fixture {
    root: tempfile::TempDir,
    dir: PathBuf,
    fail: Option<(&'static str, io::ErrorKind)>,
    calls: RefCell<Vec<DockerCall>>,
    batches: RefCell<VecDeque<(i32, &'static str)>>,
    detection: Value,
    active_crawler: bool,
    changed_gateway: bool,
    fail_terminal_save: bool,
    health: Option<Value>,
    series: Option<Value>,
}

type DockerCall = (String, Vec<String>, u64, bool);

fn detection() -> Value {
    json!({"passed": false, "phase": "socks_connect_reply", "error_type": "TimeoutError"})
}

impl Fixture {
    fn new() -> Self {
        let root = tempfile::tempdir().unwrap();
        let dir = root.path().join(".runtime/m2").join(PROJECT);
        fs::create_dir_all(&dir).unwrap();
        Self {
            root,
            dir,
            fail: None,
            calls: RefCell::new(Vec::new()),
            batches: RefCell::new(VecDeque::new()),
            detection: detection(),
            active_crawler: false,
            changed_gateway: false,
            fail_terminal_save: false,
            health: None,
            series: None,
        }
    }

    fn context(&self) -> Context<'_> {
        Context {
            docker: self,
            dir: self.dir.clone(),
            project: PROJECT,
            diagnostics: true,
        }
    }

    fn write(&self, file: &str, value: &Value) {
        fs::write(self.dir.join(file), value.to_string()).unwrap();
    }

    fn recover(&self) -> Value {
        self.write(evidence::DETECTION, &self.detection);
        let result = recover::run(&self.context(), evidence::DETECTION, true).unwrap();
        assert_eq!(
            evidence::read(&self.dir.join(evidence::RECOVERY)).unwrap(),
            result
        );
        result
    }

    fn supervise(&self, batches: &[(i32, &'static str)]) -> Value {
        self.batches.borrow_mut().extend(batches);
        let result = supervise::run(&self.context()).unwrap();
        assert_eq!(
            evidence::read(&self.dir.join(evidence::SUPERVISION)).unwrap(),
            result
        );
        result
    }

    fn has(&self, name: &str) -> bool {
        self.calls.borrow().iter().any(|(log, _, _, _)| log == name)
    }
}

impl Docker for Fixture {
    fn run(
        &self,
        args: &[&str],
        log: &str,
        seconds: u64,
        _capture: bool,
        cleanup: bool,
    ) -> io::Result<Outcome> {
        self.calls.borrow_mut().push((
            log.into(),
            args.iter().map(|value| (*value).into()).collect(),
            seconds,
            cleanup,
        ));
        if self.fail_terminal_save {
            let journal = self.dir.join(evidence::RECOVERY);
            let previous = journal.with_extension("previous");
            if log == "recovery-resume-1.log" {
                fs::rename(&journal, &previous)?;
                fs::create_dir(&journal)?;
            } else if log == "recovery-retain-pause-0.log" {
                fs::remove_dir(&journal)?;
                fs::rename(previous, journal)?;
            }
        }
        if let Some((failed_log, kind)) = self.fail
            && log == failed_log
        {
            if kind == io::ErrorKind::Other {
                return Ok(Outcome {
                    code: 42,
                    stdout: String::new(),
                });
            }
            return Err(io::Error::new(kind, "Injected Docker error"));
        }
        let (code, stdout) = match log {
            "recovery-gateway-identity-after.log" if self.changed_gateway => (0, TWO.to_owned()),
            "recovery-gateway-identity-before.log"
            | "recovery-gateway-identity-after.log"
            | "recovery-gateway-identity-resume.log" => (0, GATEWAY.to_owned()),
            "recovery-consumers.log" => (0, format!("{ONE}\n{TWO}")),
            "recovery-gateway-ready-0.log" => (0, "running healthy".into()),
            "recovery-detect.log" => {
                assert!(args.contains(&"--single-health"));
                self.write(evidence::DETECTION, &self.detection);
                (1, String::new())
            }
            "recovery-health.log" => {
                assert!(args.contains(&"/workspace/test/m2/network_probe.py"));
                let normal = json!({"passed": true, "health": {
                    "http_status": 200, "health_body": "ready", "socks_atyp": 3, "remote_hostname_sent": true
                }});
                self.write(evidence::HEALTH, self.health.as_ref().unwrap_or(&normal));
                (0, String::new())
            }
            "recovery-series.log" => {
                assert!(args.contains(&"dev.onionall.brawler.m2.RecoverySeriesProbe"));
                assert!(args.contains(&"M2_TOR_DIAGNOSTICS=1"));
                let normal =
                    json!({"passed": true, "completed": (["/robots.txt", "/health"].repeat(3))});
                self.write(evidence::SERIES, self.series.as_ref().unwrap_or(&normal));
                (0, String::new())
            }
            "runtime-crawl-0.log" | "runtime-crawl-1.log" => {
                let (code, body) = self.batches.borrow_mut().pop_front().unwrap();
                fs::write(self.dir.join(log), body)?;
                (code, String::new())
            }
            "runtime-crawler-cleanup-id.log" if self.active_crawler => (0, ONE.into()),
            _ => (0, String::new()),
        };
        Ok(Outcome { code, stdout })
    }

    fn check_cancelled(&self) -> io::Result<()> {
        Ok(())
    }
}

#[test]
fn owned_paths_and_exclusive_journals() {
    let fixture = Fixture::new();
    assert_eq!(
        evidence::owned_dir(fixture.root.path(), PROJECT, &fixture.dir).unwrap(),
        fixture.dir
    );
    for project in [
        "unrelated",
        "onionall-brawler-m2-123456abcdeF",
        "onionall-brawler-m2-123456abcdefx",
    ] {
        assert!(evidence::owned_dir(fixture.root.path(), project, &fixture.dir).is_err());
    }
    assert!(evidence::owned_dir(fixture.root.path(), PROJECT, fixture.root.path()).is_err());
    let lock = evidence::lock(&fixture.dir).unwrap();
    assert!(evidence::lock(&fixture.dir).is_err());
    drop(lock);
    assert!(evidence::lock(&fixture.dir).is_ok());
    let first = fixture.recover();
    assert_eq!(first["passed"], true);
    let count = fixture.calls.borrow().len();
    assert!(recover::run(&fixture.context(), evidence::DETECTION, true).is_err());
    assert_eq!(count, fixture.calls.borrow().len());
}

#[test]
fn local_names_and_incident_documents_reject_malformed_inputs() {
    for name in [
        "../detected.json",
        "C:/detected.json",
        "con.json",
        ".json",
        "gateway-recovery.json",
        "RECOVERY-HEALTH.json",
        "a.json:stream",
    ] {
        assert!(!evidence::valid_detection(name), "{name}");
    }
    assert!(evidence::valid_detection(
        "onion-readiness-controlled-return.json"
    ));
    let phases = [
        "proxy_tcp_connect",
        "socks_greeting",
        "socks_authentication",
        "http_response",
        "socks_connect_reply",
    ];
    let codes = [
        json!(null),
        json!(0),
        json!(1),
        json!(255),
        json!(256),
        json!("4"),
        json!([]),
        json!(false),
    ];
    for phase in phases {
        for code in &codes {
            let row = json!({"phase": phase, "error_type": "OSError", "socks_reply_code": code});
            let expected = phase == "socks_connect_reply"
                && matches!(code.as_u64(), Some(1 | 255) | None)
                && (code.is_null() || code.is_u64());
            assert_eq!(
                recover::connect_failure(&json!({"passed":false,"attempts":[row]})),
                expected
            );
        }
    }
    for malformed in [
        json!(null),
        json!({}),
        json!({"passed":false,"attempts":[]}),
        json!({"passed":false,"attempts":[detection(), null]}),
        json!({"passed":true,"attempts":[detection()]}),
    ] {
        assert!(!recover::connect_failure(&malformed));
    }
}

#[test]
fn recovery_success_has_the_legacy_stages_and_budget() {
    let fixture = Fixture::new();
    let result = fixture.recover();
    assert_eq!(result["passed"], true);
    assert_eq!(result["consumer_count"], 2);
    assert_eq!(result["automatic_direct_fallback"], false);
    assert_eq!(result["gateway_container_preserved"], true);
    let states: Vec<_> = result["events"]
        .as_array()
        .unwrap()
        .iter()
        .map(|row| row["state"].as_str().unwrap())
        .collect();
    assert_eq!(
        states,
        [
            "quarantining",
            "quarantined",
            "replacing",
            "verifying",
            "resuming",
            "recovered"
        ]
    );
    let calls = fixture.calls.borrow();
    let position = |name| {
        calls
            .iter()
            .position(|(label, _, _, _)| label == name)
            .unwrap()
    };
    assert!(position("recovery-pause-1.log") < position("recovery-gateway-restart.log"));
    assert!(position("recovery-health.log") < position("recovery-series.log"));
    assert!(position("recovery-series.log") < position("recovery-resume-0.log"));
    assert_eq!(
        calls
            .iter()
            .filter(|(_, args, _, _)| args[0] == "restart")
            .count(),
        1
    );
    for (_, args, _, _) in calls.iter() {
        assert!(
            !args
                .iter()
                .any(|arg| ["-v", "down", "seed", "frontier"].contains(&arg.as_str()))
        );
    }
}

#[test]
fn partial_failures_and_interruptions_stop_pinned_gateway() {
    for (step, error) in [
        ("recovery-pause-1.log", io::ErrorKind::Other),
        ("recovery-gateway-stop.log", io::ErrorKind::Other),
        ("recovery-gateway-restart.log", io::ErrorKind::TimedOut),
        ("recovery-gateway-ready-0.log", io::ErrorKind::Other),
        ("recovery-gateway-identity-after.log", io::ErrorKind::Other),
        ("recovery-health.log", io::ErrorKind::TimedOut),
        ("recovery-series.log", io::ErrorKind::Interrupted),
        ("recovery-resume-1.log", io::ErrorKind::Other),
    ] {
        let mut fixture = Fixture::new();
        fixture.fail = Some((step, error));
        let result = fixture.recover();
        assert_eq!(result["state"], "blocked", "{step}");
        assert_eq!(result["passed"], false);
        assert_eq!(result["gateway_stop_exit_code"], 0);
        assert_eq!(
            fixture.has("recovery-resume-0.log"),
            step == "recovery-resume-1.log"
        );
        let calls = fixture.calls.borrow();
        let (_, args, _, cleanup) = calls.last().unwrap();
        assert!(*cleanup);
        assert_eq!(args, &["stop", "--time", "5", GATEWAY]);
        assert!(fixture.has("recovery-retain-pause-0.log"));
        assert!(fixture.has("recovery-retain-pause-1.log"));
    }
}

#[test]
fn refuses_http_policy_stale_or_malformed_evidence_before_mutation() {
    for phase in [
        "http_response",
        "socks_greeting",
        "socks_authentication",
        "scope_rejected",
    ] {
        let mut fixture = Fixture::new();
        fixture.detection["phase"] = json!(phase);
        assert_eq!(fixture.recover()["passed"], false);
        assert!(fixture.calls.borrow().is_empty());
    }
    let fixture = Fixture::new();
    fixture.write(evidence::HEALTH, &json!({"passed":true}));
    assert_eq!(fixture.recover()["passed"], false);
    assert!(fixture.calls.borrow().is_empty());
    let fixture = Fixture::new();
    fs::write(fixture.dir.join(evidence::DETECTION), b"{").unwrap();
    let result = recover::run(&fixture.context(), evidence::DETECTION, true).unwrap();
    assert!(
        result["blocker"]
            .as_str()
            .unwrap()
            .starts_with("JSONDecodeError:")
    );
    assert!(fixture.calls.borrow().is_empty());
}

#[test]
fn terminal_journal_failure_retains_quarantine_without_a_success_result() {
    let mut fixture = Fixture::new();
    fixture.fail_terminal_save = true;
    let result = fixture.recover();
    assert_eq!(result["passed"], false);
    assert_eq!(result["state"], "blocked");
    assert!(fixture.has("recovery-resume-1.log"));
    assert!(fixture.has("recovery-retain-pause-0.log"));
    assert!(fixture.has("recovery-fail-closed-stop.log"));
}

#[test]
fn identity_and_both_verification_results_are_required() {
    let mut fixture = Fixture::new();
    fixture.changed_gateway = true;
    let result = fixture.recover();
    assert!(
        result["blocker"]
            .as_str()
            .unwrap()
            .contains("container changed")
    );
    assert!(!fixture.has("recovery-health.log"));
    assert!(fixture.has("recovery-fail-closed-stop.log"));

    for health in [
        json!([]),
        json!({"passed":true,"health":{
            "http_status":200,"health_body":"ready","socks_atyp":1,"remote_hostname_sent":false}}),
        detection(),
    ] {
        let flat_failure = health["phase"] == "socks_connect_reply";
        let mut fixture = Fixture::new();
        fixture.health = Some(health);
        let result = fixture.recover();
        assert_eq!(result["passed"], false);
        if flat_failure {
            assert_eq!(result["health_failure"]["phase"], "socks_connect_reply");
            assert_eq!(result["health_failure"]["error_type"], "TimeoutError");
        }
        assert!(!fixture.has("recovery-series.log"));
        assert!(!fixture.has("recovery-resume-0.log"));
        assert!(fixture.has("recovery-fail-closed-stop.log"));
    }
    let mut fixture = Fixture::new();
    fixture.series = Some(json!({"passed":true,"completed":["/health"]}));
    assert_eq!(fixture.recover()["passed"], false);
    assert!(!fixture.has("recovery-resume-0.log"));
}

#[test]
fn supervises_at_most_two_batches_and_preserves_budget() {
    let fixture = Fixture::new();
    let result = fixture.supervise(&[(0, SOCKS), (0, "index output")]);
    assert_eq!(result["state"], "completed");
    assert_eq!(result["batches"].as_array().unwrap().len(), 2);
    assert_eq!(result["batches"][0]["transport_failed"], true);
    assert_eq!(result["batches"][1]["transport_failed"], false);
    assert!(supervise::run(&fixture.context()).is_err());
    let fixture = Fixture::new();
    assert_eq!(
        fixture.supervise(&[(0, SOCKS), (9, SOCKS)])["state"],
        "blocked"
    );
    assert!(!fixture.has("runtime-crawl-2.log"));
    let fixture = Fixture::new();
    fixture.write(
        evidence::RECOVERY,
        &json!({"passed":true,"state":"recovered"}),
    );
    assert_eq!(fixture.supervise(&[(0, SOCKS)])["state"], "blocked");
    assert!(!fixture.has("recovery-detect.log"));
}

#[test]
fn nonzero_and_http_policy_errors_are_not_transport_failures() {
    for (code, log) in [
        (2, "unrelated application exception"),
        (0, "java.io.IOException: ROBOTS_UNAVAILABLE\n"),
        (1, "java.io.IOException: SCOPE_REJECTED\n"),
    ] {
        let fixture = Fixture::new();
        let result = fixture.supervise(&[(code, log)]);
        assert_eq!(result["state"], "blocked");
        assert_eq!(result["batches"][0]["transport_failed"], false);
        assert!(!fixture.dir.join(evidence::RECOVERY).exists());
    }
    let fixture = Fixture::new();
    assert_eq!(
        fixture.supervise(&[(0, "indexed text SOCKS_FAILURE REQUEST_TIMEOUT")])["state"],
        "completed"
    );
}

#[test]
fn cleanup_is_scoped_and_quarantine_refuses_start() {
    let fixture = Fixture::new();
    fixture.write(
        evidence::RECOVERY,
        &json!({"passed":false, "state":"blocked"}),
    );
    assert_eq!(fixture.supervise(&[])["state"], "blocked");
    assert!(fixture.calls.borrow().is_empty());
    let mut fixture = Fixture::new();
    fixture.fail = Some(("runtime-crawl-0.log", io::ErrorKind::Interrupted));
    fixture.active_crawler = true;
    let result = fixture.supervise(&[]);
    assert_eq!(result["state"], "blocked");
    assert_eq!(result["crawler_stop_exit_code"], 0);
    let calls = fixture.calls.borrow();
    let (_, args, timeout, cleanup) = calls.last().unwrap();
    assert_eq!(args, &["stop", "--time", "30", ONE]);
    assert_eq!(*timeout, 45);
    assert!(*cleanup);
}

#[test]
fn missing_docker_and_failed_detection_cannot_start_a_second_batch() {
    let mut fixture = Fixture::new();
    fixture.fail = Some(("runtime-crawler-preflight-0.log", io::ErrorKind::NotFound));
    assert_eq!(fixture.supervise(&[])["state"], "blocked");
    assert!(!fixture.has("runtime-crawl-0.log"));
    let mut fixture = Fixture::new();
    fixture.detection["phase"] = json!("http_response");
    assert_eq!(fixture.supervise(&[(1, SOCKS)])["state"], "blocked");
    assert!(!fixture.has("runtime-crawl-1.log"));
    assert!(!fixture.has("recovery-gateway-restart.log"));
}

#[test]
fn parser_survives_generated_shapes_and_non_utf8_logs() {
    let fixture = Fixture::new();
    fs::write(
        fixture.dir.join(Path::new("arbitrary.log")),
        vec![255; 400_000],
    )
    .unwrap();
    assert!(!supervise::test_transport(&fixture.dir.join("arbitrary.log")).unwrap());
    let mut bits = 0x92c3_219fu32;
    for _ in 0..2048 {
        bits = bits.wrapping_mul(1664525).wrapping_add(1013904223);
        let value = json!({"passed": false, "attempts": [detection(),
            {"phase":"socks_connect_reply","error_type":"OSError","socks_reply_code": bits}]});
        let serialized = value.to_string();
        let decoded = serde_json::from_str(&serialized).unwrap();
        assert_eq!(
            recover::connect_failure(&decoded),
            (1..=255).contains(&bits)
        );
    }
}
