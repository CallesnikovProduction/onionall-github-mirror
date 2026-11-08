# M4.2-P portable toolchain verification

2026-11-08. Includes the pre-existing canonical runtime and paired Admission
reliability changes with explicit owner authorization. No schema/protobuf changes.

## Architecture

Root Bash launcher -> pinned Temurin JDK25 build container -> checked Maven
Wrapper -> sequential root/crawler verification and CLI packaging -> prepared
SHA256-pinned Frontier dependency -> independent Frontier Maven verification.
`test` additionally runs the established seven server conformance drivers and
Python tooling contracts. Build outputs and dependency caches retain caller UID.

`build --images` additionally builds the existing cleanup2 Storm patch using a
multi-stage image and the same lock/drain negative controls, then the existing
Tor image. The upstream Frontier image is input only; runtime uses our overlay.
Runtime Python/Compose controller runs in the toolchain with the local Unix
Docker socket and identical absolute checkout mount. Runtime volumes, token,
H2/catalog, HTML objects, execution state and Tor identity remain retained.

## Executed evidence

- Registry inspection: Temurin 25.0.5_8 JDK noble index
  `sha256:95e350ad1eb738f943e141640f7e3d797e483359a3b33c3864255bfd1ad71f8a`;
  contains linux/amd64 and linux/arm64. Toolchain/runtime selects linux/amd64
  because the existing pinned Storm/Frontier contour is amd64.
- Docker CLI 28.5.2 and Python fixture manifests verified and digest-pinned.
- Bash syntax checks: launcher, toolchain build and modified Storm builder PASS.
- Python tooling: 32 tests PASS. Includes actual launcher execution with poisoned
  host java/javac/mvn/python3, spaced checkout path, container failure propagation,
  missing-daemon guard and controller socket/caller forwarding. Docker is mocked:
  this proves dispatch contracts, not container execution.
- Compose config validation: canonical Tor/fixture plus M0/M1/M2 PASS with each
  fixture's required run-directory variables; both CI workflow YAML files parse.
- Markdown links and git whitespace checks PASS.
- Auxiliary native JDK25 checks (not Docker evidence): root Maven verify PASS,
  253 tests, zero failures/errors/skips; crawler verify and CLI packaging PASS;
  independent Frontier Maven verify PASS; all seven shell Frontier conformance
  drivers PASS. Existing dependency caches reused; missing Maven plugin inputs
  fetched from Maven Central without changing dependency versions.
- Storm patch-only build from local pinned libraries: original source/JAR SHA256
  validation, baseline-fails/patched-passes lock and drain probes PASS.

## Unverified boundaries

Docker CLI/Compose are present, but Engine socket is unavailable even outside
execution isolation. `./onionall build` exits 69 with an actionable Engine error.
Docker image assembly, clean/incremental container builds, canonical up/status/
down, live persistent-volume retention, image sizes and real Tor were NOT RUN.
The added portable CI workflow runs the same build/test path and internal-only
fixture readiness/start/stop; no real Tor or registry publication is required.
No CI success is asserted before observing that workflow. The historical broader
verification workflow retains its existing acceptance/NOT RUN policy.

Local raw logs stay under ignored `.runtime/m42-*`; no logs, state or secrets are
part of this commit. Clean only targets generated Maven outputs; down has no
volume-removal flags. ARM emulation, WSL2 and Docker Desktop are documented
requirements/limitations, not executed platform qualifications.
