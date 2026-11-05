# Tor transport and scope policy

Inherited repository and ancestor AGENTS.md constraints remain in force.

## MODULE PURPOSE

Fail-closed StormCrawler transport and pure onion URL/scope validation.

## RESPONSIBILITIES

Keep scope normalization, metadata checks, SOCKS calls, response policy, robots handling and cleanup in this boundary.

## IMPLEMENTED ARCHITECTURE

TorPolicy validates configured v3 origins without networking. TorProtocol uses a once-resolved/pinned numeric SOCKS endpoint, rejects local target DNS, disables redirects/transparent retries and idle pooling, bounds calls and bodies, and cancels active calls on cleanup. Robots use per-origin single-flight and a bounded TTL cache.

## KEY COMPONENTS

`TorPolicy`, `TorProtocol` and `TorScopeFilter`; Java-visible no-arg protocol construction is required by Storm.

## DATA FLOW

Configured scope + URL/metadata -> policy validation -> SOCKS remote-name request -> bounded response/redirect/content validation -> prefixed protocol metadata. Robots decoding precedes crawler policy use.

## DEPENDENCIES

OkHttp, StormCrawler protocol interfaces, JVM crypto/URL support; official Tor is provisioned separately. Java application-generated configuration supplies ONLY mode and approved scope.

## INVARIANTS

No DIRECT fallback, target DNS lookup, automatic redirect following, transparent request retries or credential/header overrides. Preserve finite deadlines/content limits, robots enforcement, scoped Location validation and strict robots decoding. No network I/O under the shared robots-cache monitor.

## CHANGE GUIDELINES

Use internal SOCKS negative controls for DNS, redirect, encoding, body, concurrency and cleanup changes. Preserve @JvmOverloads/reflection compatibility and explicit metadata prefixes. Real Tor validation is a separate authorized activity.

## RELATED MODULES

[Generated transport configuration](../../../../../java/dev/onionall/brawler/admission/AGENTS.md); [Transport fixtures](../../../../../../../test/m2/AGENTS.md); [Tor runtime](../../../../../../../docker/m2/AGENTS.md).

## KNOWN LIMITATIONS

Valid onion checksum proves address syntax, not service availability. Proxy TCP availability is not Tor readiness. Trace output must remain bounded and destination-redacted; transport does not configure host-wide network isolation.
