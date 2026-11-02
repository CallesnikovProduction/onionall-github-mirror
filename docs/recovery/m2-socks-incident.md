# M2 SOCKS incident — 2026-11-02

## Current continuation — 2026-11-03

Strict original target9560fd480748 fails at CONNECT reply after the single
gateway restart (30s), before HTTP/JVM series. Same-context52b269ed5fb1 passes
all four clients and actual Storm robots/pending, but8f2036152884 fails all four
after restart even in the original namespace. Fresh namespace change is not
necessary for failure; exact Tor/path cause remains unproven. The optional cold
directory and fresh-context B comparisons were not exercised live. Four local
A/B/A branch tests cover control flow only; original M2 assertions are unchanged.
All three runs retain volumes, remove containers/networks and match protected
host snapshots. No further identical real-Tor retry/full gate this pass.

TPR-02 robots cache isolation is independently fixed (`6fc4597`); it cannot cure
Python/native-JDK failures before HTTP. Original full M2 remains blocked, while
fresh strict M136bf86224d74 and local HTTP M3f90ef8e542 pass. Tor0.4.9.11 remains;
authenticated candidate delivery is blocked on ordinary official-host DNS.
The historical NEXT EXPERIMENT below is superseded by the current
[execution ledger](../engineering/recovery-execution-ledger.md): preserve evidence,
qualify signed transport update separately and implement network-independent
durable admission conformance, rather than repeat descriptor/circuit experiments.

## CONFIRMED

Baseline f14a96d; M1 cleanup2 and existing acceptance assertions are unchanged.
Read-only route/rule/resolver/Docker snapshots are retained under
`.runtime/m2/incident-20261102/`. Each experiment also compares protected host
state before/after and checks container/network removal and volume retention.
No bridge configuration, host networking or Tor isolation is modified.

The old HTTP canary and Java request used different containers and different
SOCKS method offers. Python offered NO_AUTH only. Installed JDK25
`java.base/java/net/SocksSocketImpl.java` offers NO_AUTH+USER_PASSW; its fallback
identity is the process OS account. Tor may select USER_PASSW for SOCKS isolation.
The native stack's readSocksReply is shared by greeting/auth/CONNECT, so the old
stack trace alone cannot identify the subphase.

New `test/m2/socks_incident.py` retains ONE crawler container through initial,
service restart and gateway restart. All controls use that namespace, proxy9050,
owned destination80 and20s network budgets: Python NO_AUTH, Python with the JDK
method offer/account fallback, native JDK Socket(SOCKS), production TorProtocol.
Only approved /health is requested. Actual Storm runs there only after comparison
prerequisites pass, using existing robots/frontier/pending assertions. No new
crawler loop or queue. This incident diagnostic does not set m2_verified=true.

Evidence:

- d5cd6d833c57: same namespace, Python health9.76s PASS; native JDK20.09s timeout;
  subsequent TorProtocol9.18s PASS. Storm never started. No restart attempted.
- acebe3e5c77d: all four initial clients PASS. After owned service restart all
  four fail before HTTP (Python observes CONNECT reply timeout after successful
  greeting/auth). After gateway restart all four PASS. Storm absent throughout
  this first comparative variant. Thus Storm/OkHttp are not necessary causes.
- c5f9469f6555: initial Python controls time out, later native JDK14.70s and
  TorProtocol2.51s succeed. Cold publication/stream availability varies over time;
  this observation is not evidence of a permanent protocol incompatibility.
- a3f5c6720038: first runtime validation with the numeric endpoint correction;
  initial controls PASS, service restart fails before HTTP, gateway restart
  restores all four controls. Actual Storm robots + pending fetch PASS in the
  same namespace; frontier has HTTP200, HTTP_OK, no error count and future refetch.
  incident.passed=true; m2_verified=false. Cleanup/retention/host comparison pass.
- b66bf31c112f: repeat with corrected endpoint. Initial controls PASS, service
  restart fails; gateway restart does NOT restore any of the four clients within
  their20s budgets. Python methods0 and2 both time out at CONNECT reply. Native
  JDK and TorProtocol also fail before HTTP; crawler deliberately not started.
  Cleanup/retention/host comparison pass. Recovery repeatability is1/2, not proven.

The strongest supported failure domain is Tor stream/path availability across
owned-service lifecycle, not Storm integration alone. Exact Tor internal cause
(descriptor/circuit/path/upstream network) remains unproven. A one-shot control
stream-status query observed no active streams; it establishes no attribution.

## FIXED

A separate, demonstrated lifecycle defect contradicted the intended pinned proxy
contract. Resolved InetSocketAddress("tor",port) retains a hostname hostString;
JDK SOCKS retains that spelling and can resolve it again on connect/authentication.
TorProtocol now passes the numeric address selected at configure to Proxy. This
preserves one endpoint until a new protocol instance is created; gateway
replacement still requires new clients. Only proxy resolution changes; onion
hostnames remain unresolved ATYP3 destinations, with explicit SOCKS and no DIRECT.
This correction is NOT claimed to cure Tor descriptor/stream stalls.

Deterministic regression first failed (expected127.0.0.1, actual localhost), then
passed after the fix. A controlled USER_PASSW SOCKS fixture also proves native JDK
and TorProtocol support that method, remote hostname forwarding and two fresh
connections. Authentication payload is discarded, never recorded. Python's
opt-in auth probe has a separate phase and rejects failed authentication before
CONNECT/HTTP. Existing NO_AUTH acceptance behavior is unchanged.

## Verification and reproduction

Select JDK25 and existing approved local images/private bridge file, then:

```sh
ONIONALL_OFFLINE=1 bash scripts/build-cli.sh
python3 -B -m unittest discover -s test/m2
M2_STORM_IMAGE=onionall-storm:3.1.0-jre25-cleanup2 python3 -B test/m2/socks_incident.py
```

Java25 offline Maven verify:84 PASS. Python M2:83 PASS. CLI/PTY16 PASS; results are in
`.runtime/m2/incident-20261102/cli.log`. Probes persist sanitized JSON phase,
method-selection, namespace and timing evidence; no credentials or target URL
in diagnostic messages. Raw service/crawler logs remain ignored and private.
Baseline and failed experiments are preserved. Original full M2 and three-run
recovery acceptance are not substituted by this diagnostic.

## STILL BLOCKED

The two corrected-endpoint runs disagree on gateway restoration; proxy pinning
is a real contract fix but not a demonstrated cure for this incident. No
additional identical retry or full M2 gate was launched after the failed repeat.

Real Tor stream recovery is not guaranteed by a successful canary; it can fail
across Python and Java, even within one namespace. Tor remains0.4.9.11; its
independent signed security delivery gate is not changed here. Full M2/M3 remain
BLOCKED until original required gates pass. No new direct path, isolation flag,
retry policy, network timeout or acceptance exception is introduced.

## NEXT EXPERIMENT

If recovery remains unstable, correlate the SAME client's outstanding SOCKS
CONNECT with read-only Tor STREAM events during the failure, recording only
stream state/circuit ID and hashed target. Compare authenticated and NO_AUTH
contexts without disabling isolation. Capture a fresh read-only host baseline
first. Do not repeat identical full gates or infer a Java bug from timeout alone.
