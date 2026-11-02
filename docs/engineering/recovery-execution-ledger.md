# Recovery execution ledger

## 2026-11-02: baseline39257ef

Working tree initially clean. Numeric proxy endpoint bug fixed, independently
from Tor stream availability. Last same-namespace incident a3f5c6720038 passed
actual Storm pending/robots; repeat b66bf31c112f failed across Python0/2, native
JDK and TorProtocol. Storm is not a necessary cause. Strict M1 cleanup2 has two
prior fresh passing runs; no framework/CLI changes needed for this baseline.

Current experiment: preserve original incident variant; optional one cold gateway
replacement after the original restart fails, with a new client directory inside
the existing retained tor-state volume. Old data and onion keys remain untouched.
Replacement changes gateway IP, so ONLY newly configured clients participate;
no live configured crawler resumes. Actual Storm runs only after all four controls
pass, in the same namespace. No host networking, isolation or timeout change.
This diagnostic does not claim production recovery or m2_verified=true.

Next command (after fresh offline Maven verify):

```sh
JAVA_HOME="$PWD/.runtime/platform-migration/jdk25/jdk-25.0.5+8" \
M2_STORM_IMAGE=onionall-storm:3.1.0-jre25-cleanup2 \
python3 -B test/m2/socks_incident.py --cold-gateway-on-failure
```

Evidence root: .runtime/autonomous-recovery/; each incident keeps its unique
.runtime/m2 project. Security delivery is separately blocked: official host DNS
resolution curl6; existing negative verifier4 tests pass. No retry/bypass.

## Checkpoint1: controlled incident c8d4cc4db8e8

Command above executed against fresh Maven84-test artifact: incident PASS,
actual Storm robots+pending marker and durable HTTP200 future-refetch confirmed,
no ERROR records. All four initial controls pass; after service restart all fail;
normal gateway restart restores all four. Cold fallback NOT EXERCISED. This
positive does not erase previous failed repeats or set m2_verified=true.
Own containers/networks removed, three volumes retained, protected host baseline
unchanged within the run. No larger timeouts or isolation changes.

Next: finish regression for confirmed cross-origin robots head-of-line blocking,
then fresh Maven verify and run original targeted restart recovery. Stop on first
failure and retain evidence; full M2 remains conditional on targeted success.
