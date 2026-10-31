# ADR 0001: fixed SOCKS transport and independent network boundary

Status: implemented; M2 acceptance blocked pending actual Docker/Tor verification.

## Existing fetch path and findings

The repository is one Maven module, not a multi-module application. Flux creates
StormCrawler 3.7.0 SimpleFetcherBolt for the controlled M1 run, ProtocolFactory
constructs protocols selected by `http.protocol.implementation` and
`https.protocol.implementation`. Both currently select OkHttp HttpProtocol.
Fetcher obtains robots through the same protocol before document fetching.
StatusUpdater writes scheduling outcomes/metadata to URLFrontier 2.4/RocksDB.
M0/M1 retain their existing files and contract.

Inspection of installed 3.7.0 bytecode and tagged source established:

- Stock OkHttp protocol uses ProxyManager with per-request metadata. An absent
  proxy result can retain the base client; global configuration is insufficient.
- The stock protocol supports configurable trust-all TLS (default true).
- Automatic redirects are configurable; fetching redirect destinations inside
  the client bypasses crawler-level checks.
- Stock HttpRobotRulesParser catches protocol failures and can return permissive
  rules. Throwing in a wrapper alone does not repair that failure mode.
- SimpleFetcherBolt catches failures from getRobotRules and emits FETCH_ERROR;
  allowing the exception to reach that handler preserves safe retry scheduling.

References: [StormCrawler tagged HttpProtocol](https://github.com/apache/stormcrawler/blob/stormcrawler-3.7.0/core/src/main/java/org/apache/stormcrawler/protocol/okhttp/HttpProtocol.java),
[robots parser](https://github.com/apache/stormcrawler/blob/stormcrawler-3.7.0/core/src/main/java/org/apache/stormcrawler/protocol/HttpRobotRulesParser.java).

## Decision

Implement the existing Protocol interface in a focused Kotlin TorProtocol.
Use the existing OkHttp dependency, fixed explicit SOCKS5 Proxy and no dynamic
proxy manager. Resolve only the gateway; destination resolution is forbidden via
OkHttp Dns and SOCKS transmits the unresolved destination hostname. Wire tests
inspect SOCKS ATYP=3 and exact hostname; they are not real Tor acceptance.
Default TLS trust and hostname verification remain enabled. No automatic retry,
redirect, cookie or per-URL header/proxy customization is permitted. All metadata
proxy keys are rejected. URL validation checks canonical v3 base32, version and
SHA3 checksum, scheme/port/userinfo and explicit approved hosts without DNS.
TorScopeFilter applies the same validation before discovered links are scheduled.

Responses retain bounded bodies and deadlines. Redirects are returned to the
crawler only after Location validation, so destination robots/scheduling/filtering
remain in the existing pipeline. Robots requests use this same fixed client;
only a successfully retrieved HTTP 200 robots file is parsed. Failure throws
ROBOTS_UNAVAILABLE rather than granting permission. Positive robots cache is
bounded to 16 origins and ten minutes. This conservative policy refuses crawling
when robots is absent, redirected or unavailable.

Crawler/probes/frontier attach only to an internal Docker network. The Tor
SOCKS gateway alone on that network also has outbound access. A separate onion
service Tor daemon has outbound access and a private fixture network; neither
fixture nor its network is available directly to the crawler. Both Tor daemons
need external Tor connections. SOCKS is OnionTrafficOnly with SafeSocks; no host
ports or TCP control interfaces are published. Private keys stay in a named
volume. Control-cookie bootstrap health checks stay local to each Tor container.
Crawler external DNS forwarding is disabled; runtime probes must verify actual
routing, network membership, DNS refusal and denied direct sockets.

## Alternatives and limits

A wrapper around stock HttpProtocol would retain hidden metadata and robots
behavior. Global proxy flags alone cannot protect against direct network egress.
A parallel crawler or full HTTP implementation would duplicate existing machinery.
The selected adapter changes only M2 config and adds Kotlin compilation to the
single Maven module. It intentionally supports a narrow controlled scope.

Docker internal networking is a required independent boundary, not a production
firewall guarantee. Compromised dual-network Tor services and hostile Docker host
are outside this test model. Security acceptance requires actual network probes,
real onion crawling and complete negative scenarios. Config inspection, local
SOCKS stubs, package builds and bootstrap progress alone never establish M2.
