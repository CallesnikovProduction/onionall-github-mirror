# Java 25 / Storm 3.1 migration — 2026-11-01

## Scope and compatibility

Candidate branch: `migration/java25-storm31`. Original main/checkpoint
`97b9da40f946b81775e310fa0d94004966c4737b` remains available unchanged.
The user's compiler source/target → release17 refactor was baseline-tested and
preserved in commit9860636 before introducing profiles and changing defaults.

| Component | Baseline | Candidate actually tested |
|---|---|---|
| Host Java | 17 | Temurin25.0.5+8 LTS, Linux x64 |
| Storm / Flux | 2.8.9 | 3.1.0 |
| Docker Storm | storm:2.8.9-jre17 | storm:3.1.0-jre25, Temurin25.0.5+8 |
| StormCrawler | 3.7.0 | 3.7.0, unchanged |
| URLFrontier CLI/client / server | 2.4 | 2.4, unchanged |
| Selected URLFrontier API | 2.5 transitively | 2.5 transitively |
| Kotlin | 2.3.21 | 2.3.21, unchanged |
| Compiler / Surefire / Shade | 3.11.0 / 3.6.0 / 3.5.0 | unchanged |
| Tor installed / candidate | 0.4.9.11 / 0.4.9.15 unbuilt | unchanged; security blocker |

[Storm's official release notes](https://storm.apache.org/2026/09/12/storm310-released.html)
require Java25 for3.x and describe2.8.9 as EOL. The new scheduler strategy
allowlist, state serializer, Nimbus groups and JSONP defaults were reviewed;
this local Flux topology does not configure custom scheduler/state providers,
Nimbus service groups or HTTP JSONP. This is not a distributed deployment audit.

[StormCrawler3.7 upstream POM](https://raw.githubusercontent.com/apache/stormcrawler/stormcrawler-3.7.0/pom.xml)
targets Storm2.8.9/Java17; therefore compatibility here is established by the
executed topology, not by claiming upstream certification for every module.
[Kotlin2.3 supports JVM25](https://kotlinlang.org/docs/whatsnew23.html).
Compiler, Surefire and Shade succeeded without upgrades; application classes in
the shaded JAR have major version69. No removed JVM flags were needed by local
Storm startup. Windows11 native execution and ARM64 were NOT RUN.

## Reproducible inputs and JDK selection

JDK came from the official Adoptium API (latest25/hotspot, linux/x64, jdk,
eclipse). Selected archive: OpenJDK25U-jdk_x64_linux_hotspot_25.0.5_8.tar.gz,
[official release](https://github.com/adoptium/temurin25-binaries/releases/tag/jdk-25.0.5%2B8).
SHA256 checked against official API metadata before extraction:
`68982529869257d0ee6b824ec5aa270ff8b118bb438670da0b97ebdab178d7fb`.
Archive signature was not independently verified. Exact API selection/checksum
and logs are ignored under .runtime/platform-migration. Host alternatives and
system Java configuration were not changed.

Use one selected JDK25 directory for JAVA_HOME and PATH in the command's shell;
check `java -version`, `javac -version`, `jar --version` and `mvn -version`.
On Windows use scripts/build.ps1 -JdkHome with the installed JDK25 directory.
M2 checks that java/javac/jar come from one JDK25+; helpers compile with release25.

The [official Docker image definition](https://raw.githubusercontent.com/docker-library/official-images/master/library/storm)
lists3.1.0-jre25 for amd64/arm64v8. Pulled manifest digest:
`storm@sha256:3f8c17e6ce43ac9ca6106458eb060ecf3096ba22422c4a04687b98e89cb68244`.
Compose uses a version tag, not an immutable digest pin; inspect images.json for
the actual image on each run. The official container has storm-client under
lib-common; probes now include both lib/* and lib-common/* on the classpath.
The first M1 migration run failed ClassNotFoundException(Config) before crawl;
its failed evidence is retained (69d3e85930ba). No assertions were weakened.

## Executed gates

| Gate | Result | Evidence / boundary |
|---|---|---|
| G0 baseline | PASS | offline Java17 verify47 JVM tests; baseline-verify.log |
| G1 Java25 | PASS | profile and default offline verify47 tests, shaded major69, actual JVM25 |
| G2 Storm3.1 | PASS | actual LocalCluster/Flux startup, parser/status/spout, clean shutdown |
| G3 local M3 | PASS | 5f37e310b5 (profile),91c6025d49 (final defaults): A→B→C, roots once, depth/provenance, robots,503 retry, five gRPC records, cleanup, retained volume |
| G4 real Tor M3 | BLOCKED | read-only TCP check127.0.0.1:19050: ConnectionRefusedError; no available controlled A/B/C Tor prerequisite; no live Tor scenario launched |
| G5 durable admission | BLOCKED | current API lacks required atomic global reservation; ADR0002; production admission remains rejected |

G3 uses isolated local HTTP through existing M1 infrastructure, explicitly not
Tor and not a production fallback. Its JSON correctly keeps m3_verified=false.
M2 remains m2_verified=false. No live onion recovery experiments were repeated.

Additional checks: Python M2 67 / M3 2 PASS; M0/M1/M2 Compose config validation
PASS with required run-directory variables supplied. Native Windows and a real
distributed Storm cluster were NOT RUN. VPN/host networking were not changed.
M1 regression PASS: e1c1f2931a8f, assertions_passed/crawl_evidence_passed/
runtime_verified=true, six records, minimum gap2.003810683s, pending resumed,
containers/network removed and frontier volume retained. Python M1:31 passed,
one optional smoke skipped. All M2 Java helper sources compile with release25.

## Dependency assessment and limitations

Storm-client3.1 is provided by runtime, Flux3.1 is packaged. The verbose tree
contains77 conflict-mediated entries: this is not a strict dependency-convergence
PASS. Shade reports overlaps, including URLFrontier API classes embedded in the
client2.4 JAR and standalone API2.5, and protobuf/logging classes. No speculative
exclusions were introduced. The actual gRPC snapshot/put/retry/persistence calls
worked with server2.4; API2.5-only operations against2.4 remain unverified.
App selects Jackson2.22.0, image contains2.22.2: exercised serialization works,
not proof of compatibility for every API. These existing packaging risks require
focused tests before extending API usage, not unrelated dependency upgrades.

## Java25 DNS guard

The old JDK17 private InetAddress.nameService reflection cannot serve the new
runtime. TorDnsGuardProbe now uses the public InetAddressResolverProvider SPI.
Only the explicitly built test-provider JAR enables it; no service registration
is added to application resources or the crawler image. No add-opens is needed.
Run after Maven verify, with JDK25 on PATH (POSIX shell example):

```sh
mvn -o -B -Dmaven.repo.local=.runtime/maven-linux-repository dependency:build-classpath -Dmdep.outputFile=.runtime/platform-migration/classpath.txt
mkdir -p .runtime/platform-migration/dns-classes
javac --release 25 -cp "target/classes:$(cat .runtime/platform-migration/classpath.txt)" -d .runtime/platform-migration/dns-classes test/m2/TorDnsGuardProbe.java
jar --create --file .runtime/platform-migration/dns-provider.jar -C test/m2/dns-provider META-INF
java -cp ".runtime/platform-migration/dns-provider.jar:.runtime/platform-migration/dns-classes:target/classes:target/test-classes:$(cat .runtime/platform-migration/classpath.txt)" dev.onionall.brawler.m2.TorDnsGuardProbe .runtime/platform-migration/dns-guard25.json
```

Executed:35 tests,0 failures; positive controls for plain and uppercase/trailing-dot hosts true; application onion resolver
attempts0. This proves the fresh test JVM's behavior with loopback SOCKS fixtures,
not system-wide absence of DNS leaks. Without an active provider, positive
control fails and cannot report PASS. Windows classpath uses `;`, not `:`.

## Rollback and next boundary

Do not run Java25 bytecode in the old JRE17 image. To use the previous complete
baseline, create a separate checkout at97b9da4 and rebuild with Java17; do not
rewrite migration history, reset user changes or reuse its generated JAR.
Profiles java25/storm31 are explicit aliases after promotion on this branch;
they are not a Java17 rollback switch. No main update is implied by branch push.

Next useful work is a separately scoped frontier-server atomic admission change
per [ADR0002](adr/0002-durable-discovery-admission.md), with concurrent boundary,
restart and lost-ACK tests. Tor security/delivery and live integration prerequisites
remain independent blockers. The JVM migration does not make M2/M3 production-ready.
