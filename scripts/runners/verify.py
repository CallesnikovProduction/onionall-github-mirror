#!/usr/bin/env python3
"""Offline-capable verification; logs/results only, no infrastructure cleanup.

Exit 0: every selected gate passed; 1: failure; 2: required work not run.
Default gates never contact Tor. Docker product E2E requires --product-e2e.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import time
import unittest
from urllib.parse import unquote, urlsplit
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
BASE_SHA256 = 'ff1b774bf81343234e6f34878f8b7c9a826371b3e305e96bf0e204895e3562df'
SUITES = ('m1', 'm2', 'm3', 'archive', 'security', 'scripts', 'cli')
CONFORMANCE = ('AdmissionConformance', 'AdmissionMigrationConformance',
               'AdmissionShutdownConformance', 'AdmissionRpcConformance',
               'RecoveryCountersConformance', 'AdmissionStateConformance')


def markdown_links(root, files):
    """Check local file targets outside fenced code; fragments aren't validated."""
    broken = []
    for name in files:
        page = root / name
        text = re.sub(r'^\s*(```|~~~).*?^\s*\1[^\n]*$', '',
                      page.read_text(encoding='utf-8-sig'), flags=re.M | re.S)
        targets = re.findall(r'!?\[[^\]\n]*\]\(\s*(<[^>]*>|[^\s)]+)', text)
        targets += re.findall(r'^\s*\[[^\]]+\]:\s*(<[^>]*>|\S+)', text, re.M)
        for raw in targets:
            target = raw.strip('<>')
            parsed = urlsplit(target)
            if parsed.scheme or parsed.netloc or not parsed.path:
                continue
            path = unquote(parsed.path)
            resolved = root / path.lstrip('/') if path.startswith('/') else page.parent / path
            if not resolved.exists():
                broken.append(dict(file=name, target=target))
    return broken


def aggregate(gates):
    if any(g['status'] == 'FAIL' for g in gates):
        return 'FAIL', 1
    if any(g['status'] == 'NOT RUN' for g in gates):
        return 'NOT RUN', 2
    return 'PASS', 0


def product_verdict(returncode, report):
    """Exit zero alone cannot qualify a product or forced-owner recovery gate."""
    valid = isinstance(report, dict) and type(report.get('result_version')) is int and report.get('result_version') == 1
    product = 'PASS' if returncode == 0 and valid and report.get('passed') is True else 'FAIL'
    attempted = valid and report.get('worker_crash_test_executed') is True
    worker = 'PASS' if product == 'PASS' and attempted and report.get('worker_crash_verified') is True else 'FAIL' if attempted else 'NOT RUN'
    return product, worker


def python_suite(directory, report):
    suite = unittest.defaultTestLoader.discover(str(ROOT / 'test' / directory))
    result = unittest.TextTestRunner(verbosity=2).run(suite)
    counts = dict(tests=result.testsRun, failures=len(result.failures),
                  errors=len(result.errors), skipped=len(result.skipped))
    report.write_text(json.dumps(counts) + '\n', encoding='utf-8')
    return 1 if not result.wasSuccessful() or not result.testsRun else 2 if result.skipped else 0


class Verification:
    def __init__(self, evidence):
        self.evidence = evidence
        self.gates = []

    def record(self, name, status, **details):
        self.gates.append(dict(name=name, status=status, **details))
        print(f'{name}: {status}', flush=True)

    def run(self, name, command, timeout=900, cwd=ROOT):
        log = self.evidence / (name + '.log')
        try:
            with log.open('w', encoding='utf-8') as stream:
                result = subprocess.run(command, cwd=cwd, stdout=stream,
                                        stderr=subprocess.STDOUT, timeout=timeout)
            return result.returncode, log
        except (OSError, subprocess.TimeoutExpired) as exc:
            with log.open('a', encoding='utf-8') as stream:
                stream.write('\n' + type(exc).__name__ + '\n')
            return 1, log

    def frontier(self, baseline, java_home):
        if not baseline or not Path(baseline).is_file() or not java_home:
            self.record('frontier', 'NOT RUN', reason='Require JDK25 and URLFRONTIER_BASE_JAR')
            return
        baseline = Path(baseline).resolve()
        if hashlib.sha256(baseline.read_bytes()).hexdigest() != BASE_SHA256:
            self.record('frontier', 'FAIL', reason='Pinned base JAR checksum mismatch')
            return
        stage = self.evidence / 'frontier'
        stage.mkdir()
        suffix = '.exe' if os.name == 'nt' else ''
        java = str(Path(java_home) / 'bin' / ('java' + suffix))
        javac = str(Path(java_home) / 'bin' / ('javac' + suffix))
        classes, tests = stage / 'classes', stage / 'tests'
        for tree, dest, cp in [('main', classes, str(baseline)),
                               ('test', tests, os.pathsep.join((str(classes), str(baseline))))]:
            dest.mkdir()
            sources = sorted((ROOT / 'frontier-server/src' / tree / 'java').rglob('*.java'))
            args = stage / (tree + '.args')
            args.write_text('\n'.join('"' + str(p).replace('\\', '/') + '"' for p in sources), encoding='utf-8')
            rc, log = self.run('frontier-compile-' + tree,
                               [javac, '--release', '11', '-cp', cp, '-d', str(dest), '@' + str(args)])
            if rc:
                self.record('frontier', 'FAIL', log=str(log), phase='compile-' + tree)
                return
        cp = os.pathsep.join(map(str, (classes, tests, baseline)))
        for name in CONFORMANCE:
            command = [java, '--enable-native-access=ALL-UNNAMED', '-cp', cp,
                       'crawlercommons.urlfrontier.service.rocksdb.' + name, str(stage / (name + '-db'))]
            if name == 'AdmissionMigrationConformance':
                command.append(str(baseline))
            rc, log = self.run('frontier-' + name, command)
            self.record('frontier-' + name, 'PASS' if rc == 0 else 'FAIL', log=str(log))


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--offline', action='store_true', help='Maven offline; no downloads')
    parser.add_argument('--maven', help='Maven executable; defaults to checked-in wrapper')
    parser.add_argument('--maven-repository', type=Path)
    parser.add_argument('--links-only', action='store_true')
    parser.add_argument('--product-e2e', action='store_true', help='Explicit local Docker fixture; never real Tor')
    parser.add_argument('--python-suite', choices=SUITES, help=argparse.SUPPRESS)
    parser.add_argument('--suite-result', type=Path, help=argparse.SUPPRESS)
    args = parser.parse_args(argv)
    if args.python_suite:
        if not args.suite_result:
            parser.error('--python-suite requires --suite-result')
        return python_suite(args.python_suite, args.suite_result)
    evidence_root = ROOT / '.runtime/verification'
    evidence_root.mkdir(parents=True, exist_ok=True)
    evidence = Path(tempfile.mkdtemp(prefix='run-', dir=evidence_root))
    verify = Verification(evidence)
    tracked = subprocess.check_output(['git', 'ls-files', '-z', '*.md'], cwd=ROOT).decode().split('\0')
    broken = markdown_links(ROOT, filter(None, tracked))
    verify.record('markdown-links', 'FAIL' if broken else 'PASS', broken=broken)
    if not args.links_only:
        java_home = os.environ.get('JAVA_HOME')
        java = str(Path(java_home) / 'bin' / ('java.exe' if os.name == 'nt' else 'java')) if java_home else shutil.which('java')
        jdk25 = False
        if java:
            try:
                check = subprocess.run([java, '-version'], capture_output=True, text=True, timeout=30)
                jdk25 = check.returncode == 0 and bool(re.search(r'version "25(?:[.\"])', check.stderr + check.stdout))
            except (OSError, subprocess.TimeoutExpired):
                pass
        wrapper = ROOT / ('mvnw.cmd' if os.name == 'nt' else 'mvnw')
        maven = args.maven or (str(wrapper) if wrapper.exists() else shutil.which('mvn'))
        if maven and Path(maven).is_file():
            maven = str(Path(maven).resolve())
        if not jdk25 or not maven:
            verify.record('maven', 'NOT RUN', reason='Require JDK25 and Maven/wrapper')
        else:
            command = [maven, '-B']
            if args.offline:
                command.append('-o')
            if args.maven_repository:
                command.append('-Dmaven.repo.local=' + str(args.maven_repository.resolve()))
            started = time.time()
            rc, log = verify.run('maven', command + ['verify'])
            counts = dict(tests=0, failures=0, errors=0, skipped=0)
            if rc == 0:
                for report in (ROOT / 'target/surefire-reports').glob('TEST-*.xml'):
                    if report.stat().st_mtime < started:
                        continue  # Never promote historical reports to current PASS.
                    xml = ET.parse(report).getroot()
                    for key in counts:
                        counts[key] += int(xml.attrib.get(key, 0))
            status = 'FAIL' if rc or not counts['tests'] or counts['failures'] or counts['errors'] else 'NOT RUN' if counts['skipped'] else 'PASS'
            verify.record('maven', status, counts=counts, log=str(log))
        for directory in SUITES:
            report = evidence / ('python-' + directory + '.json')
            rc, log = verify.run('python-' + directory,
                                 [sys.executable, '-B', str(Path(__file__).resolve()), '--python-suite', directory,
                                  '--suite-result', str(report)])
            counts = json.loads(report.read_text(encoding='utf-8')) if report.exists() else None
            verify.record('python-' + directory, {0: 'PASS', 2: 'NOT RUN'}.get(rc, 'FAIL'), counts=counts, log=str(log))
        verify.frontier(os.environ.get('URLFRONTIER_BASE_JAR'), java_home if jdk25 else None)
        if args.product_e2e:
            result_file = evidence / 'product-result.json'
            rc, log = verify.run('product-e2e', [sys.executable, '-B', str(ROOT / 'test/admission/local_integration.py'),
                                                '--result-file', str(result_file)], timeout=1800)
            try:
                report = json.loads(result_file.read_text(encoding='utf-8'))
            except (OSError, ValueError):
                report = None
            product, worker = product_verdict(rc, report)
            verify.record('product-e2e', product, log=str(log), report=str(result_file))
            verify.record('worker-crash-acceptance', worker, os_process_containment=False,
                          reason='Actual generated Storm: live-child and UNKNOWN restart must fail closed')
        else:
            verify.record('product-e2e', 'NOT RUN', reason='Opt-in local Linux Docker fixture: --product-e2e')
            verify.record('worker-crash-acceptance', 'NOT RUN', reason='Requires actual generated Storm forced-owner fixture')
    status, exit_code = aggregate(verify.gates)
    result = dict(schema_version=1, observed_at=datetime.now(timezone.utc).isoformat(),
                  scope='links' if args.links_only else 'local-verification', status=status,
                  gates=verify.gates, real_tor='OUT OF SCOPE')
    (evidence / 'result.json').write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
    print(f'Result: {status}; evidence: {evidence}')
    return exit_code


if __name__ == '__main__':
    sys.exit(main())
