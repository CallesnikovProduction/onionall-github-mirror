# Legacy controlled HTTP crawl

Inherited repository and ancestor AGENTS.md constraints remain in force.

## MODULE PURPOSE

Deterministic legacy HTTP crawling, pacing and persistent Frontier fixture.

## RESPONSIBILITIES

Prepare a derived root Flux graph and assert its exact crawl, filtering, timing and recreation behavior.

## IMPLEMENTED ARCHITECTURE

prepare.py derives fixture configuration; fixture.py provides bounded HTTP responses; verify.py and Java probes inspect results, scheduling and delays. resources/ holds fixture-only filter overrides.

## KEY COMPONENTS

`prepare.py`, `fixture.py`, `verify.py`, `FrontierProbe.java`, `FilterCheck.java`, `DelayRegressionCheck.java`, connectivity probes, test_m1.py and resources/.

## DATA FLOW

Controlled HTTP seed -> derived legacy graph/stock clients -> fixture Frontier -> pacing/count/persistence observations.

## DEPENDENCIES

Root Flux/config, runner shell/PowerShell entrypoints, Docker and exact baseline JARs.

## INVARIANTS

Keep stock-fixture and protected-product contracts separate. Preserve delayed-response assertions, finite runtime, owned project identity and retained persistent volume; no down -v.

## CHANGE GUIDELINES

Check preparation/unit cases first; run the controlled runtime to validate timing or graph changes. Do not substitute a shorter delay, exit zero or local HTTP result for Tor/product acceptance.

## RELATED MODULES

[Entrypoints](../../scripts/runners/AGENTS.md); [Graph distinction](../../docs/adr/0004-canonical-product-topology.md).

## KNOWN LIMITATIONS

This contour is neither Tor transport nor durable Admission qualification. resources/ overrides do not silently modify product scope.
