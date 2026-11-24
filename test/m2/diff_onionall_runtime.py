#!/usr/bin/env python3
"""Replay the characterized M2 controller behavior against synthetic Docker traces.

The mock is the only executable named docker on PATH. No Compose daemon,
onion names, original runtime evidence, or credentials enter the fixture.
Authors: Стефан Колесников and GPT-6.1 Sol.
"""

import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import time

PROJECT = "onionall-brawler-m2-123456abcdef"
GATEWAY = "0123456789ab"
CONSUMERS = ["aaaaaaaaaaaa", "bbbbbbbbbbbb"]
SERIES = "dev.onionall.brawler.m2.RecoverySeriesProbe"


def synthetic_docker(args):
    path = Path(os.environ["M2_TEST_EVIDENCE"])
    scenario = os.environ["M2_TEST_SCENARIO"]
    counts = path / "docker-trace.jsonl"
    trace = [json.loads(line) for line in counts.read_text().splitlines()] if counts.exists() else []
    with counts.open("a") as output:
        output.write(json.dumps(args) + "\n")
    compose = args[0] == "compose"
    if compose:
        assert args[:5] == ["compose", "-p", PROJECT, "-f", "compose.m2.yaml"], args
    verb = args[5] if compose else args[0]
    assert not any(arg in {"down", "seed", "-v", "frontier"} for arg in args), args
    if verb == "ps":
        if compose:
            identities = [line for line in trace if line[:7] == args[:7]]
            print(CONSUMERS[0] if scenario == "changed-id" and identities else GATEWAY)
        elif "-aq" not in args:
            print(*CONSUMERS, sep="\n")
    elif verb == "inspect":
        assert args[-1] == GATEWAY
        print("running healthy")
    elif verb in {"stop", "up"}:
        assert args[-1] in {GATEWAY, "tor", PROJECT + "-runtime-crawler"}
    elif verb == "pause":
        assert args[-1] in CONSUMERS
        return int(scenario == "partial-pause" and args[-1] == CONSUMERS[1])
    elif verb == "restart":
        assert args[-1] in {GATEWAY, "tor"}
        return int(scenario == "restart-failure")
    elif verb == "unpause":
        assert args[-1] in CONSUMERS
        return int(scenario == "partial-resume" and args[-1] == CONSUMERS[1])
    elif verb == "run" and "--single-health" in args:
        filename = args[-1]
        if filename == "recovery-detection.json":
            phase = {"http": "http_response", "policy": "socks_authentication"}.get(
                scenario, "socks_connect_reply"
            )
            document = {"passed": False, "phase": phase, "error_type": "TimeoutError"}
        else:
            assert filename == "recovery-health.json"
            document = {"passed": scenario != "health-failure", "health": {
                "http_status": 200, "health_body": "ready", "socks_atyp": 3, "remote_hostname_sent": True,
            }}
        (path / filename).write_text(json.dumps(document))
        return int(not document["passed"])
    elif verb == "run" and args[-1] == SERIES:
        (path / "recovery-series.json").write_text(json.dumps({
            "passed": scenario != "series-failure", "completed": ["/robots.txt", "/health"] * 3,
        }))
        return int(scenario == "series-failure")
    elif verb == "run" and "--name" in args:
        previous = sum("--name" in call and "run" in call for call in trace)
        if scenario == "unrelated-exit":
            print("synthetic non-transport application failure")
            return 2
        if scenario == "interrupt":
            time.sleep(20)
        if scenario == "budget" or scenario == "retry" and previous == 0:
            print("java.io.IOException: SOCKS_FAILURE")
    else:
        raise AssertionError(args)
    return 0


def exercise(binary, mode, scenario):
    with tempfile.TemporaryDirectory() as temporary:
        root = Path(temporary)
        path = root / ".runtime" / "m2" / PROJECT
        path.mkdir(parents=True)
        (root / "compose.m2.yaml").touch()
        (root / "test/m2").mkdir(parents=True)
        (root / "test/m2/network_probe.py").touch()
        fake_bin = root / "fake-bin"
        fake_bin.mkdir()
        docker = fake_bin / "docker"
        docker.write_text("#!" + sys.executable + "\n" + Path(__file__).read_text().split("\n", 1)[1])
        docker.chmod(0o700)
        # Whitelist environment. No credential forwarding or real Docker context.
        env = {
            "PATH": str(fake_bin),
            "PYTHONDONTWRITEBYTECODE": "1",
            "M2_TEST_EVIDENCE": str(path),
            "M2_TEST_SCENARIO": scenario,
        }
        assert shutil.which("docker", path=env["PATH"]) == str(docker)
        with subprocess.Popen([str(binary), mode, PROJECT, str(path)], cwd=root, env=env, text=True,
                              stdout=subprocess.PIPE, stderr=subprocess.PIPE) as controller:
            try:
                if scenario == "interrupt":
                    trace = path / "docker-trace.jsonl"
                    deadline = time.monotonic() + 8
                    while not trace.exists() or "--name" not in trace.read_text():
                        if controller.poll() is not None or time.monotonic() > deadline:
                            raise AssertionError("Crawler operation did not start")
                        time.sleep(0.025)
                    controller.terminate()
                # Capture bounded synthetic evidence only.
                stdout, stderr = controller.communicate(timeout=30)
            except BaseException:
                controller.kill()
                controller.communicate()
                raise
        document = json.loads(stdout)
        assert controller.returncode == int(not document["passed"]), (stdout, stderr)
        journal = path / ("gateway-recovery.json" if mode == "recover" else "runtime-supervision.json")
        assert json.loads(journal.read_text()) == document
        trace = [json.loads(line) for line in (path / "docker-trace.jsonl").read_text().splitlines()]
        verbs = [line[5] if line[0] == "compose" else line[0] for line in trace]
        return {
            "passed": document["passed"],
            "state": document["state"],
            "events": [event["state"] for event in document.get("events", [])],
            "batches": document.get("batches", []),
            "restarts": verbs.count("restart"),
            "resumes": verbs.count("unpause"),
        }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("binary", type=Path)
    args = parser.parse_args()
    binary = args.binary.resolve(strict=True)
    recovery_stages = ["quarantining", "quarantined", "replacing", "verifying", "resuming"]
    for scenario, (stages, restarts, resumes) in {
        "ok": (5, 1, 2), "http": (0, 0, 0), "policy": (0, 0, 0),
        "changed-id": (3, 1, 0), "partial-pause": (1, 0, 0), "restart-failure": (3, 1, 0),
        "health-failure": (4, 1, 0), "series-failure": (4, 1, 0), "partial-resume": (5, 1, 2),
    }.items():
        passed = scenario == "ok"
        state = "recovered" if passed else "blocked"
        expected = dict(passed=passed, state=state, events=[*recovery_stages[:stages], state],
                        batches=[], restarts=restarts, resumes=resumes)
        actual = exercise(binary, "recover", scenario)
        assert actual == expected, (scenario, expected, actual)
        print(f"matched recover/{scenario}")

    for scenario, (codes, failures, restarts) in {
        "ok": ([0], [False], 0), "retry": ([0, 0], [True, False], 1),
        "budget": ([0, 0], [True, True], 1), "interrupt": ([], [], 0),
        "unrelated-exit": ([2], [False], 0),
    }.items():
        passed = scenario in ("ok", "retry")
        batches = [dict(exit_code=code, transport_failed=failure, log=f"runtime-crawl-{index}.log")
                   for index, (code, failure) in enumerate(zip(codes, failures, strict=True))]
        expected = dict(passed=passed, state="completed" if passed else "blocked", events=[],
                        batches=batches, restarts=restarts, resumes=restarts * len(CONSUMERS))
        actual = exercise(binary, "supervise", scenario)
        assert actual == expected, (scenario, expected, actual)
        print(f"matched supervise/{scenario}")
    return 0


if __name__ == "__main__":
    if Path(sys.argv[0]).name == "docker":
        raise SystemExit(synthetic_docker(sys.argv[1:]))
    raise SystemExit(main())
