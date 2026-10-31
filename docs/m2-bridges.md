# M2 bridge client setup and portability

The working host-only reference uses Tor0.4.9.14 and obfs4proxy with a private
host torrc. User-observed host bootstrap100 and IsTor=true do not establish
Docker or StormCrawler acceptance. The container implementation uses one shared
Docker Tor CLIENT image on Linux and Docker Desktop; it is not a bridge relay.

## Transport boundaries

Crawler → SOCKS5 at `tor:9050` on the internal crawler network → Tor daemon →
obfs4 transport → private bridge → Tor network → controlled onion service.
The onion-service daemon also needs bridges in this environment. It has a
separate private fixture network. Crawler never joins that network or outbound.
The host test's `127.0.0.1:19050` is not the container endpoint.
No SOCKS port is published to the host. Production SOCKS remains OnionTrafficOnly.
Preflight alone uses container-loopback SOCKS for the fixed public Tor check URL.

## Prerequisites and private configuration

Install Docker Engine/Compose, Python3.12+, a consistent JDK17+ (`java`, `javac`,
`jar` from the same JDK), Maven and Git. Linux additionally needs Bash; Windows
uses the supplied PowerShell wrappers and Docker Desktop Linux containers.
Entire hooks need `sh` and `entire` on the hook process PATH (Git for Windows
can supply sh). Windows execution remains to be validated on an actual machine.

Obtain private obfs4 bridge lines through Tor Project. The committed example
contains placeholders only and deliberately cannot bootstrap. Put one or more
real `Bridge obfs4` lines in `.secrets/m2/bridges.conf` using a local editor.
Do not put the full host torrc there; the file permits bridge lines/comments only.
Do not paste private values into shell arguments, shared logs or tracked files.

Linux, from the repository root:

```sh
install -d -m 700 .secrets/m2
cp -n docker/m2/bridges.example.conf .secrets/m2/bridges.conf
chmod 600 .secrets/m2/bridges.conf
```

For a fresh Windows checkout, create the directory and copy the placeholder
file only if no private file exists:

```powershell
New-Item -ItemType Directory -Force .secrets/m2 | Out-Null
if (-not (Test-Path .secrets/m2/bridges.conf)) {
    Copy-Item docker/m2/bridges.example.conf .secrets/m2/bridges.conf
}
```

Replace placeholders privately before testing. Parent directory700 and file600
are intentional. Windows: create the same local file, restrict its NTFS ACL to
your account and retain Docker Desktop read access. CRLF and UTF-8 BOM are
accepted. An external file can be selected with `M2_BRIDGES_FILE`; no user-specific
path is committed. Keep the file outside evidence directories and build context.

## Build and connectivity preflight

Build once without any bridge build arguments or mounted secret:

```sh
docker build -f docker/m2/Dockerfile -t onionall-brawler-tor:0.4.9.11-obfs4-deb12u1 .
```

Linux:

```sh
bash scripts/m2-preflight.sh
```

Windows PowerShell:

```powershell
./scripts/m2-preflight.ps1 -Python python
```

The shared preflight requires valid config, current authenticated bootstrap100,
a running obfs4 process, SOCKS hostname forwarding, normal HTTPS validation and
`IsTor=true` from the fixed public Tor check endpoint. It has a180s deadline,
retains sanitized result/logs and removes its unique container. No full Maven or
crawler stack starts on failure; no direct-Tor fallback is permitted.
Private bridges are mounted read-only at runtime; only a mode600 generated torrc
in private tmpfs contains their values. Only fixed diagnostic categories and
bootstrap percentages leave the launcher. The private source never enters an
image layer or verification JSON. Both secret directories are Git/Docker ignored.

## Full M2

After connectivity succeeds:

```sh
bash scripts/m2.sh
```

Or native Windows PowerShell:

```powershell
./scripts/m2.ps1 -Python python
```

Both delegate to `scripts/m2.py`; there are no duplicated platform orchestration
implementations. Java classpaths use the host separator; M1 regression selects
the existing Bash or PowerShell runner. `JAVA_HOME` selects the JDK; optional
`M2_MAVEN_REPOSITORY` selects a writable dependency cache.
Windows Git Bash can use `PYTHON=python bash scripts/m2.sh` when python3 is not
available; native PowerShell is the preferred Windows entrypoint. This documents
implementation support, not a claim that Windows runtime tests were executed.

The runner prepares only named-volume directories to the runtime UID/GID, never
recursively rewrites keys, and keeps Tor non-root. The crawler is read-only with
an executable /tmp tmpfs required by RocksDB JNI. Existing scope, TLS validation,
robots, request bounds and network isolation remain mandatory.

Cleanup retains frontier-data, tor-state and onion-state. Never prune them as
ordinary cleanup. Full acceptance also requires controlled onion HTML/discovery,
robots/scope/dedup/delay, persisted recovery, actual blocked direct egress,
metadata bypass rejection, Tor outage/retry and M1 regression. Bootstrap or curl
alone can never produce M2 PASSED. See m2-hardening.md for audit findings.
