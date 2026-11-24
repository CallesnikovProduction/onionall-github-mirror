//! A finite local crawler batch, with at most one recovery and one fresh batch.
//! Authors: Стефан Колесников and GPT-6.1 Sol.

use crate::docker::{Context, container_ids, required};
use crate::evidence::{self, DETECTION, RECOVERY, SUPERVISION};
use crate::recover;
use serde_json::{Value, json};
use std::fs::File;
use std::io::{self, BufRead, BufReader};
use std::path::Path;

#[derive(Default)]
struct Signals {
    transport: bool,
    application: bool,
}

/// Only exception records count. Robot policy/HTTP failures cannot establish
/// a SOCKS incident. Transport records can only invite a separate SOCKS probe.
fn inspect_log(path: &Path) -> io::Result<Signals> {
    let mut reader = BufReader::new(File::open(path)?);
    let mut signals = Signals::default();
    let mut prefix = Vec::with_capacity(128);
    loop {
        let bytes = reader.fill_buf()?;
        if bytes.is_empty() {
            classify(&prefix, &mut signals);
            return Ok(signals);
        }
        let newline = bytes.iter().position(|b| *b == b'\n');
        let length = newline.unwrap_or(bytes.len());
        let take = length.min(128 - prefix.len());
        prefix.extend_from_slice(&bytes[..take]);
        reader.consume(length + usize::from(newline.is_some()));
        if newline.is_some() {
            classify(&prefix, &mut signals);
            prefix.clear();
        }
    }
}

#[cfg(test)]
pub(crate) fn test_transport(path: &Path) -> io::Result<bool> {
    Ok(inspect_log(path)?.transport)
}

fn classify(prefix: &[u8], signals: &mut Signals) {
    let line = prefix.strip_suffix(b"\r").unwrap_or(prefix);
    let line = line.strip_prefix(b"Caused by: ").unwrap_or(line);
    if let Some(record) = line.strip_prefix(b"java.io.IOException: ") {
        let category = record
            .split(|b| *b == b' ' || *b == b':')
            .next()
            .unwrap_or_default();
        match category {
            b"SOCKS_FAILURE" | b"REQUEST_TIMEOUT" | b"ONION_UNREACHABLE" => {
                signals.transport = true
            }
            b"ROBOTS_UNAVAILABLE"
            | b"SCOPE_REJECTED"
            | b"BODY_LIMIT_EXCEEDED"
            | b"METADATA_ONLY" => {
                signals.application = true;
            }
            _ => {}
        }
    }
}

pub fn run(context: &Context<'_>) -> io::Result<Value> {
    let journal = context.dir.join(SUPERVISION);
    evidence::begin(&journal, &json!({ "passed": false, "state": "running" }))?;
    let mut result = json!({ "passed": false, "batches": [], "state": "running" });
    let mut launched = false;
    let name = format!("{}-runtime-crawler", context.project);
    if let Err(error) = execute(context, &name, &mut launched, &mut result) {
        result["state"] = json!("blocked");
        result["blocker"] = json!(error.to_string());
        if launched {
            match cleanup(context, &name) {
                Ok(Some(code)) => result["crawler_stop_exit_code"] = json!(code),
                Ok(None) => result["cleanup_blocker"] = json!("No owned crawler observed"),
                Err(error) => result["cleanup_blocker"] = json!(recover::error_name(&error)),
            }
        }
    }
    evidence::save(&journal, &result)?;
    Ok(result)
}

fn execute(
    context: &Context<'_>,
    name: &str,
    launched: &mut bool,
    result: &mut Value,
) -> io::Result<()> {
    let recovery = context.dir.join(RECOVERY);
    if evidence::exists(&recovery)? {
        let state = evidence::read(&recovery)?;
        if state["passed"] != true || state["state"] != "recovered" {
            return Err(io::Error::other("Existing quarantine prevents crawl"));
        }
    }
    for attempt in 0..2 {
        let name_filter = format!("name=^/{name}$");
        let preflight = required(
            context.docker.run(
                &["ps", "-aq", "--filter", &name_filter],
                &format!("runtime-crawler-preflight-{attempt}.log"),
                30,
                true,
                false,
            )?,
            "Crawler ownership preflight",
        )?;
        if !preflight.stdout.trim().is_empty() {
            return Err(io::Error::other(
                "Named crawler already exists; ownership is uncertain",
            ));
        }
        let log = format!("runtime-crawl-{attempt}.log");
        *launched = true;
        let outcome = context.docker.run(
            &context.compose(&["run", "--rm", "--no-deps", "--name", name, "crawler"]),
            &log,
            210,
            false,
            false,
        )?;
        let signals = inspect_log(&context.dir.join(&log))?;
        result["batches"].as_array_mut().ok_or_else(|| io::Error::other("Missing crawler batches"))?
            .push(json!({"exit_code": outcome.code, "transport_failed": signals.transport, "log": log}));
        context.docker.check_cancelled()?;
        if outcome.code == 0 && !signals.transport && !signals.application {
            result["passed"] = json!(true);
            result["state"] = json!("completed");
            return Ok(());
        }
        if !signals.transport || signals.application {
            return Err(io::Error::other(
                "Crawler batch failed without a qualifying transport observation",
            ));
        }
        if attempt != 0 || evidence::exists(&recovery)? {
            return Err(io::Error::other(
                "Runtime recovery budget exhausted; no further crawl",
            ));
        }
        let observation = recover::run(context, DETECTION, false)?;
        if observation["passed"] != true {
            return Err(io::Error::other(
                "Recovery failed; crawl remains quarantined",
            ));
        }
        *launched = false;
    }
    Err(io::Error::other(
        "Runtime recovery budget exhausted; no further crawl",
    ))
}

fn cleanup(context: &Context<'_>, name: &str) -> io::Result<Option<i32>> {
    let name = format!("name=^/{name}$");
    let project = format!("label=com.docker.compose.project={}", context.project);
    let observed = required(
        context.docker.run(
            &[
                "ps",
                "-aq",
                "--filter",
                &name,
                "--filter",
                &project,
                "--filter",
                "label=com.docker.compose.service=crawler",
            ],
            "runtime-crawler-cleanup-id.log",
            30,
            true,
            true,
        )?,
        "Crawler cleanup ownership lookup",
    )?;
    match container_ids(&observed.stdout)?.as_slice() {
        [] => Ok(None),
        [id] => Ok(Some(
            context
                .docker
                .run(
                    &["stop", "--time", "30", id],
                    "runtime-crawler-cleanup.log",
                    45,
                    false,
                    true,
                )?
                .code,
        )),
        _ => Err(io::Error::other("Ambiguous crawler cleanup ownership")),
    }
}
