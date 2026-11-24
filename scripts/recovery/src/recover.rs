//! At most one restart of the identified Tor container. Any failure after
//! quarantine starts re-pauses all known consumers and stops that same ID.
//! Authors: Стефан Колесников and GPT-6.1 Sol.

use crate::docker::{Context, Outcome, container_ids, required};
use crate::evidence::{self, HEALTH, RECOVERY, SERIES};
use serde_json::{Value, json};
use std::io;
use std::thread;
use std::time::{Duration, Instant};
use time::{OffsetDateTime, format_description::well_known::Rfc3339};

pub fn connect_failure(document: &Value) -> bool {
    let qualifies = |row: &Value| {
        row["phase"] == "socks_connect_reply"
            && matches!(row["error_type"].as_str(), Some("TimeoutError" | "OSError"))
            && match row.get("socks_reply_code") {
                None | Some(Value::Null) => true,
                Some(code) => code.as_u64().is_some_and(|n| (1..=255).contains(&n)),
            }
    };
    document["passed"] == false
        && match document.get("attempts") {
            None => qualifies(document),
            Some(Value::Array(rows)) => !rows.is_empty() && rows.iter().all(qualifies),
            Some(_) => false,
        }
}

pub fn run(context: &Context<'_>, detection: &str, reuse_detection: bool) -> io::Result<Value> {
    if !evidence::valid_detection(detection) {
        return Err(io::Error::other("Detection must be a local JSON filename"));
    }
    let journal = context.dir.join(RECOVERY);
    let mut state = Recovery {
        context,
        result: json!({
            "passed": false, "state": "detecting", "events": [],
            "replacement_limit": 1, "automatic_direct_fallback": false
        }),
        gateway: None,
        consumers: Vec::new(),
        quarantine_started: false,
    };
    evidence::begin(&journal, &state.result)?;
    if let Err(error) = state.execute(detection, reuse_detection) {
        state.result["passed"] = json!(false);
        state.result["blocker"] = json!(format!("{}: {error}", error_name(&error)));
        state.retain_quarantine();
        state.save("blocked")?;
    }
    Ok(state.result)
}

struct Recovery<'a, 'ctx> {
    context: &'a Context<'ctx>,
    result: Value,
    gateway: Option<String>,
    consumers: Vec<String>,
    quarantine_started: bool,
}

impl Recovery<'_, '_> {
    fn save(&mut self, state: &str) -> io::Result<()> {
        let utc = OffsetDateTime::now_utc()
            .format(&Rfc3339)
            .map_err(io::Error::other)?;
        self.result["state"] = json!(state);
        self.result["events"]
            .as_array_mut()
            .ok_or_else(|| io::Error::other("Missing recovery events"))?
            .push(json!({ "state": state, "utc": utc }));
        evidence::save(&self.context.dir.join(RECOVERY), &self.result)
    }

    fn command(
        &self,
        args: &[&str],
        name: &str,
        seconds: u64,
        capture: bool,
    ) -> io::Result<Outcome> {
        required(
            self.context.docker.run(
                args,
                &format!("recovery-{name}.log"),
                seconds,
                capture,
                false,
            )?,
            name,
        )
    }

    fn gateway(&self, name: &str) -> io::Result<String> {
        let observed = self.command(&self.context.compose(&["ps", "-q", "tor"]), name, 30, true)?;
        let ids = container_ids(&observed.stdout)?;
        match ids.as_slice() {
            [id] => Ok((*id).to_owned()),
            _ => Err(io::Error::other(
                "Expected exactly one configured gateway container",
            )),
        }
    }

    fn health(&self, filename: &str, name: &str) -> io::Result<(i32, Value)> {
        let args = self.context.compose(&[
            "run",
            "--rm",
            "--no-deps",
            "--entrypoint",
            "python3",
            "crawler",
            "/workspace/test/m2/network_probe.py",
            "--single-health",
            "--readiness-output",
            filename,
        ]);
        let code = self
            .context
            .docker
            .run(&args, &format!("recovery-{name}.log"), 45, false, false)?
            .code;
        Ok((code, evidence::read(&self.context.dir.join(filename))?))
    }

    fn wait_ready(&self, gateway: &str) -> io::Result<()> {
        let deadline = Instant::now() + Duration::from_secs(90);
        for attempt in 0.. {
            self.context.docker.check_cancelled()?;
            let remaining = deadline.saturating_duration_since(Instant::now());
            if remaining.is_zero() {
                break;
            }
            let observed = self.command(
                &[
                    "inspect",
                    "--format",
                    "{{.State.Status}} {{if .State.Health}}{{.State.Health.Status}}{{end}}",
                    gateway,
                ],
                &format!("gateway-ready-{attempt}"),
                remaining.as_secs().clamp(1, 10),
                true,
            )?;
            match observed.stdout.trim() {
                "running healthy" => return Ok(()),
                "running starting" => thread::sleep(remaining.min(Duration::from_secs(1))),
                _ => {
                    return Err(io::Error::other(
                        "Pinned gateway is not running with a healthy Tor check",
                    ));
                }
            }
        }
        Err(io::Error::new(
            io::ErrorKind::TimedOut,
            "Pinned gateway readiness timed out",
        ))
    }

    fn execute(&mut self, detection: &str, reuse_detection: bool) -> io::Result<()> {
        let path = self.context.dir.join(detection);
        let observation = if evidence::exists(&path)? {
            if !reuse_detection {
                return Err(io::Error::other("Stale recovery detection evidence"));
            }
            evidence::read(&path)?
        } else {
            let (code, observed) = self.health(detection, "detect")?;
            if code == 0 {
                return Err(io::Error::other(
                    "No qualifying SOCKS CONNECT failure; no replacement attempted",
                ));
            }
            observed
        };
        if !connect_failure(&observation) {
            return Err(io::Error::other(
                "No qualifying SOCKS CONNECT failure; no replacement attempted",
            ));
        }
        // Refuse to consume evidence left by a different probe execution.
        for file in [HEALTH, SERIES] {
            if evidence::exists(&self.context.dir.join(file))? {
                return Err(io::Error::other(format!("Stale recovery evidence: {file}")));
            }
        }
        let gateway = self.gateway("gateway-identity-before")?;
        self.gateway = Some(gateway.clone());
        self.save("quarantining")?;
        let project = format!("label=com.docker.compose.project={}", self.context.project);
        let listed = self.command(
            &[
                "ps",
                "-q",
                "--filter",
                &project,
                "--filter",
                "label=com.docker.compose.service=crawler",
            ],
            "consumers",
            30,
            true,
        )?;
        self.consumers = container_ids(&listed.stdout)?
            .into_iter()
            .map(str::to_owned)
            .collect();
        self.result["consumer_count"] = json!(self.consumers.len());
        self.quarantine_started = true;
        for (index, container) in self.consumers.iter().enumerate() {
            self.command(&["pause", container], &format!("pause-{index}"), 30, false)?;
        }
        self.save("quarantined")?;
        self.command(
            &["stop", "--time", "5", &gateway],
            "gateway-stop",
            20,
            false,
        )?;
        self.save("replacing")?;
        self.command(
            &["restart", "--time", "5", &gateway],
            "gateway-restart",
            20,
            false,
        )?;
        // Target the pinned ID throughout. A Compose up could create a new
        // container if the old one disappeared while the controller ran.
        self.wait_ready(&gateway)?;
        if self.gateway("gateway-identity-after")? != gateway {
            return Err(io::Error::other(
                "Gateway container changed; pinned client endpoint cannot be trusted",
            ));
        }
        self.result["gateway_container_preserved"] = json!(true);
        self.save("verifying")?;
        let (code, health) = self.health(HEALTH, "health")?;
        if health["passed"] == false {
            let mut selected = serde_json::Map::new();
            for field in ["phase", "error_type", "elapsed_seconds", "socks_reply_code"] {
                if let Some(value) = health.get(field) {
                    selected.insert(field.to_owned(), value.clone());
                }
            }
            self.result["health_failure"] = Value::Object(selected);
            return Err(io::Error::other(
                "Replacement controlled onion probe failed; inspect recovery-health.json",
            ));
        }
        if !health["health"].is_object() {
            return Err(io::Error::other("Malformed recovery health evidence"));
        }
        let observed = &health["health"];
        if code != 0
            || health["passed"] != true
            || observed["http_status"] != 200
            || observed["health_body"] != "ready"
            || observed["socks_atyp"] != 3
            || observed["remote_hostname_sent"] != true
        {
            return Err(io::Error::other(
                "Replacement did not restore controlled onion HTTP; quarantine retained",
            ));
        }
        let mut args = self.context.compose(&["run", "--rm", "--no-deps"]);
        if self.context.diagnostics {
            args.extend(["-e", "M2_TOR_DIAGNOSTICS=1"]);
        }
        args.extend([
            "--entrypoint",
            "java",
            "probe",
            "-cp",
            "/apache-storm/lib/*:/apache-storm/lib-common/*:/evidence/crawler-m2.jar",
            "dev.onionall.brawler.m2.RecoverySeriesProbe",
        ]);
        self.command(&args, "series", 160, false)?;
        let series = evidence::read(&self.context.dir.join(SERIES))?;
        if series["passed"] != true
            || series["completed"] != json!(["/robots.txt", "/health"].repeat(3))
        {
            return Err(io::Error::other(
                "Real protocol series failed; quarantine retained",
            ));
        }
        self.context.docker.check_cancelled()?;
        if self.gateway("gateway-identity-resume")? != gateway {
            return Err(io::Error::other(
                "Gateway container changed during verification",
            ));
        }
        self.save("resuming")?;
        for (index, container) in self.consumers.iter().enumerate() {
            self.command(
                &["unpause", container],
                &format!("resume-{index}"),
                30,
                false,
            )?;
        }
        self.context.docker.check_cancelled()?;
        self.result["passed"] = json!(true);
        self.save("recovered")
    }

    fn retain_quarantine(&mut self) {
        if !self.quarantine_started {
            return;
        }
        for (index, container) in self.consumers.iter().enumerate() {
            let _ = self.context.docker.run(
                &["pause", container],
                &format!("recovery-retain-pause-{index}.log"),
                30,
                false,
                true,
            );
        }
        if let Some(gateway) = &self.gateway {
            let code = self
                .context
                .docker
                .run(
                    &["stop", "--time", "5", gateway],
                    "recovery-fail-closed-stop.log",
                    20,
                    false,
                    true,
                )
                .map(|outcome| outcome.code)
                .ok();
            self.result["gateway_stop_exit_code"] = json!(code);
        }
    }
}

pub fn error_name(error: &io::Error) -> &'static str {
    match error.kind() {
        io::ErrorKind::NotFound => "FileNotFoundError",
        io::ErrorKind::Interrupted => "KeyboardInterrupt",
        io::ErrorKind::TimedOut => "TimeoutExpired",
        io::ErrorKind::InvalidData => "JSONDecodeError",
        io::ErrorKind::Other => "RuntimeError",
        _ => "OSError",
    }
}
