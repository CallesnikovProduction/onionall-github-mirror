#!/usr/bin/env python3
"""Run canonical controller CLI contracts against an isolated fake Docker.
Authors: Стефан Колесников and GPT-6.1 Sol.
"""
import argparse
import json
import os
from pathlib import Path
import shutil
import signal
import stat
import subprocess
import sys
import tempfile
import time


FIXTURE_PROJECT = "onionall-runtime-fixture"
ARTIFACTS = (
    "target/onionall/bin/onionall",
    "target/onionall-brawler-0.1.0-SNAPSHOT.jar",
    "frontier-server/target/urlfrontier-admission.jar",
)
WORKER = "0123456789ab"
FAKE_DOCKER = r'''
import json, os, sys, time
from pathlib import Path

args = sys.argv[1:]
compose = len(args) > 7 and args[:2] == ["compose", "-p"]
action = args[7] if compose else args[0]
with Path("docker-trace.jsonl").open("a", encoding="utf-8") as trace:
    json.dump({"args": args, "runtime_dir": os.environ.get("ONIONALL_RUNTIME_DIR"),
               "runtime_user": os.environ.get("ONIONALL_RUNTIME_USER"),
               "bridge": os.environ.get("ONIONALL_BRIDGES_FILE")}, trace)
    trace.write("\n")
scenario = os.environ["RUNTIME_TEST_CASE"]
if scenario == "docker-failure" and action == "info":
    raise SystemExit(21)
if action == "image":
    print("cleanup-submit-lock-2")
if not compose and action == "ps":
    if "label=com.docker.compose.service=crawler" in args:
        print("bad-id" if scenario == "invalid-worker" else "0123456789ab")
    elif scenario == "other-mode":
        print("0123456789ab")
if scenario == "stop-failure" and action == "stop":
    raise SystemExit(17)
if compose and action == "run" and args[8:12] == ["--rm", "--no-deps", "crawler", "crawl"]:
    if scenario == "interrupt":
        Path("child.pid").write_text(str(os.getpid()), encoding="ascii")
        time.sleep(30)
    if scenario == "cli-child-exit":
        raise SystemExit(7)
'''


def observe(root, executable, argv, environment, scenario):
    with subprocess.Popen([*executable, *argv], cwd=root, env=environment,
                          encoding="utf-8", stdout=subprocess.PIPE, stderr=subprocess.PIPE) as controller:
        try:
            if scenario == "interrupt":
                pid_file = root / "child.pid"
                deadline = time.monotonic() + 8
                while not pid_file.is_file() or not pid_file.stat().st_size:
                    if controller.poll() is not None or time.monotonic() > deadline:
                        raise AssertionError("Docker child did not start")
                    time.sleep(0.025)
                pid = int(pid_file.read_text(encoding="ascii"))
                controller.send_signal(signal.SIGTERM)
            stdout, stderr = controller.communicate(timeout=20)
        except BaseException:
            controller.kill()
            controller.communicate()
            raise
    if scenario == "interrupt":
        try:
            os.kill(pid, 0)
        except ProcessLookupError:
            pass
        else:
            raise AssertionError("Interrupted Docker child remained alive")

    calls = root / "docker-trace.jsonl"
    trace = [json.loads(line.replace(str(root), "<checkout>")) for line in calls.read_text(
        encoding="utf-8").splitlines()] if calls.exists() else []
    state = root / (".runtime/local-fixture" if argv[0] == "--fixture" else ".runtime/local")
    descriptor = state / "runtime.json"
    token = state / "admission-token"
    if token.exists():
        value = token.read_text(encoding="ascii")
        assert len(value) == 64 and all(char in "0123456789abcdef" for char in value)
        assert stat.S_IMODE(token.stat().st_mode) & 0o077 == 0
    printable = stdout.splitlines()
    if printable and printable[0] == "{":
        document, offset = json.JSONDecoder().raw_decode(stdout)
        document["data"] = document["data"].replace(str(root), "<checkout>")
        printable = [document, *stdout[offset:].splitlines()[1:]]
    return {
        "exit": controller.returncode,
        "stdout": printable,
        "stderr": stderr.replace(str(root), "<checkout>").strip(),
        "trace": trace,
        "descriptor": json.loads(descriptor.read_text(encoding="utf-8")) if descriptor.exists() else None,
        "token_created": token.exists(),
    }


def exercise(binary, scenario, argv, python_reference=None):
    with tempfile.TemporaryDirectory(prefix="canonical CLI ") as temporary:
        root = Path(temporary)
        (root / "compose.runtime.yaml").touch()
        for name in ARTIFACTS:
            path = root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.touch()
        tools = root / "tools"
        tools.mkdir()
        docker = tools / "docker"
        docker.write_text(f"#!{sys.executable}\n{FAKE_DOCKER}", encoding="utf-8")
        docker.chmod(0o700)
        assert shutil.which("docker", path=str(tools)) == str(docker)
        environment = {
            "PATH": str(tools),
            "HOME": str(root),
            "ONIONALL_STORM_IMAGE": "test-cleanup2",
            "RUNTIME_TEST_CASE": scenario,
        }
        if scenario == "bad-descriptor":
            state = root / ".runtime/local-fixture"
            state.mkdir(parents=True)
            (state / "runtime.json").write_text('{"socks_host":"x.onion","socks_port":9050}', encoding="utf-8")
        if scenario == "managed-external-proxy":
            state = root / ".runtime/local"
            state.mkdir(parents=True)
            (state / "runtime.json").write_text('{"socks_host":"operator-proxy","socks_port":9150}', encoding="utf-8")
            bridge = root / ".secrets/m2/bridges.conf"
            bridge.parent.mkdir(parents=True)
            bridge.touch()
        if python_reference is not None:
            original = root / "scripts/runners/reference.py"
            original.parent.mkdir(parents=True)
            shutil.copyfile(python_reference, original)
            executable = [sys.executable, "-B", str(original)]
        else:
            executable = [str(binary), "runtime"]

        command = argv if scenario.startswith("managed-") else ["--fixture", *argv]
        return observe(root, executable, command, environment, scenario)


def actions(observed):
    return [row["args"][7] if row["args"][:2] == ["compose", "-p"] else row["args"][0]
            for row in observed["trace"]]


def run(binary, reference):
    cases = {
        "fixture-up": (["up"], 0, ["info", "compose", "ps", "image", "up", "info", "compose", "ps", "run", "run", "run", "run"]),
        "bad-descriptor": (["up"], 1, ["info", "compose", "ps"]),
        "managed-missing-bridge": (["up"], 1, ["info", "compose", "ps", "image"]),
        "managed-external-proxy": (["up"], 0, ["info", "compose", "ps", "image", "up", "info", "compose", "ps", "run", "exec", "run", "run", "run"]),
        "docker-failure": (["up"], 1, ["info"]),
        "other-mode": (["up"], 1, ["info", "compose", "ps"]),
        "cli-normal": (["cli", "research", "thread", "crawl", "session", "--total-seconds", "900"], 0, ["info", "compose", "run"]),
        "cli-child-exit": (["cli", "crawl", "start", "--plan", "job"], 7, ["info", "compose", "run"]),
        "scoped-down": (["down"], 0, ["info", "compose", "ps", "stop", "down"]),
        "stop-failure": (["down"], 17, ["info", "compose", "ps", "stop"]),
    }
    for scenario, (argv, code, expected) in cases.items():
        result = exercise(binary, scenario, argv)
        assert result["exit"] == code and actions(result) == expected, (scenario, result)
        for row in result["trace"]:
            args = row["args"]
            assert "-v" not in args and "--volumes" not in args and "--approve" not in args
            if args[:2] == ["compose", "-p"]:
                managed = scenario.startswith("managed-")
                assert args[2] == ("onionall-runtime" if managed else FIXTURE_PROJECT)
                assert row["runtime_dir"] == ("<checkout>/.runtime/local" if managed else "<checkout>/.runtime/local-fixture")
                assert row["runtime_user"] == f"{os.getuid()}:{os.getgid()}"
        if scenario == "fixture-up":
            assert result["token_created"] and result["descriptor"]["socks_host"] == "socks-fixture"
            assert result["stdout"][0]["tor"] == "NOT_RUN_FIXTURE"
            assert "socks-fixture" in result["trace"][4]["args"] and "tor" not in result["trace"][4]["args"]
        if scenario == "managed-missing-bridge":
            assert "No direct Tor fallback" in result["stderr"]
        if scenario == "managed-external-proxy":
            assert result["stdout"][0]["tor"] == "MANAGED_TOR_BOOTSTRAP_100_CONFIGURED_PROXY_IDENTITY_UNVERIFIED"
            assert result["trace"][4]["bridge"] == "<checkout>/.secrets/m2/bridges.conf"
            assert "operator-proxy" in result["trace"][10]["args"]
        if scenario == "cli-normal":
            assert result["trace"][-1]["args"][-4:] == [
                "--runtime", "/state/runtime.json", "--admission-token-file", "/state/admission-token"]
        if scenario == "scoped-down":
            assert result["trace"][3]["args"] == ["stop", "--time", "120", WORKER]
        if reference:
            original = exercise(binary, scenario, argv, reference)
            assert result == original, (scenario, result, original)
        print(f"canonical runtime {scenario}: PASS")

    scenario, argv = "leading-jvm-option", ["cli", "--json", "crawl", "status", "--plan", "job"]
    result = exercise(binary, scenario, argv)
    assert result["exit"] == 0 and actions(result) == ["info", "compose", "run"], result
    assert result["trace"][-1]["args"][-9:] == [
        "--json", "crawl", "status", "--plan", "job", "--runtime", "/state/runtime.json",
        "--admission-token-file", "/state/admission-token"]
    if reference:
        original = exercise(binary, scenario, argv, reference)
        assert original["exit"] == 2 and not original["trace"], original
    print("canonical runtime leading-jvm-option: PASS (previous parser rejected this JVM option)")

    result = exercise(binary, "invalid-worker", ["down"])
    assert result["exit"] == 1 and actions(result) == ["info", "compose", "ps"], result
    assert "invalid crawler container ID" in result["stderr"]
    if reference:
        original = exercise(binary, "invalid-worker", ["down"], reference)
        assert original["exit"] == 0 and actions(original)[-2:] == ["stop", "down"], original
    print("canonical runtime invalid-worker: PASS (invalid Docker identity now blocks teardown)")

    result = exercise(binary, "interrupt", ["cli", "crawl", "start", "--plan", "job"])
    assert result["exit"] == 130 and actions(result) == ["info", "compose", "run"], result
    print("canonical runtime interrupt: PASS (Docker client killed and reaped)")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("binary", type=Path)
    parser.add_argument("--python-reference", type=Path)
    args = parser.parse_args()
    run(args.binary.resolve(strict=True),
        args.python_reference.resolve(strict=True) if args.python_reference else None)
