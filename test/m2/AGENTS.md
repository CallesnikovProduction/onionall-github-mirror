# Tor and recovery fixture family

Inherited repository and ancestor AGENTS.md constraints remain in force.

## MODULE PURPOSE

Transport, official-Tor integration and recovery diagnostics with separate deterministic controls.

## RESPONSIBILITIES

Attribute bootstrap, SOCKS connect, HTTP readiness, policy, lease recovery and consumer behavior without collapsing them into one success signal.

## IMPLEMENTED ARCHITECTURE

scenarios.py, prepare.py, verify.py, tor_preflight.py and tor_observer.py select actual Tor contours. deterministic_recovery.py and recovery_socks_fixture.py use internal synthetic SOCKS. Incident/cold/restart programs retain explicit unique evidence; dns-provider is test-only resolver instrumentation.

## KEY COMPONENTS

Python scenario/probe/verification modules; Java policy/DNS/scenario/lease probes; dns-provider/META-INF/services registers only fixture instrumentation.

## DATA FLOW

Explicit scenario -> readiness observations -> controlled outage/restart -> consumer/persistent readback -> structured attribution and owned cleanup.

## DEPENDENCIES

Tor Docker helpers, configured private bridges, official Tor for real-network scenarios, matching JVM artifacts and recovery scripts.

## INVARIANTS

Real Tor/onion checks require explicit authorization. Preserve no-DIRECT/DNS/redirect controls, quarantine, bounded retries and retained identities/evidence. Bootstrap is not target readiness; assisted recovery is not spontaneous. Test resolver SPI must never enter production packaging.

## CHANGE GUIDELINES

Select affected deterministic/unit checks before expensive runtime probes. Inspect exact namespace/client/context and held-lease timing. Do not repeat failed external experiments without a new cause or alter host routing/settings.

## RELATED MODULES

[Recovery implementation](../../scripts/recovery/AGENTS.md); [Tor runtime](../../docker/m2/AGENTS.md); [Transport](../../src/main/kotlin/dev/onionall/brawler/tor/AGENTS.md).

## KNOWN LIMITATIONS

Synthetic controls do not prove real Tor availability or system-wide DNS behavior. A recovered gateway does not prove consumers resumed correctly; each attribution requires its own evidence.
