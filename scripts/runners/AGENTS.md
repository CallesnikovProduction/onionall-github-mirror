# Verification and scenario orchestration

Inherited repository and ancestor AGENTS.md constraints remain in force.

## MODULE PURPOSE

Bounded local verification and explicitly selected runtime scenarios.

## RESPONSIBILITIES

Dispatch checks, retain unique evidence and preserve each contour's acceptance requirements without silently substituting a weaker fixture.

## IMPLEMENTED ARCHITECTURE

verify.py aggregates JVM, Python, Frontier and local-link checks; product end-to-end is explicit opt-in. Startup/controlled-crawl/Tor runners own separate scenario lifecycles and use corresponding test helpers.

## KEY COMPONENTS

`verify.py`, `tor_acceptance_runner.py`, startup/controlled-crawl/Tor shell and PowerShell entrypoints, container launchers and frontier-volume-init.sh.

## DATA FLOW

Prerequisite checks -> isolated scenario -> actual observations -> structured summary -> owned process/container cleanup with persistent evidence retained.

## DEPENDENCIES

Application/server builds, test/ fixtures, Docker and shells as required. Container image/JAR provenance must match the selected contour.

## INVARIANTS

Missing/skipped checks are not successful verification. Product generated graph, legacy HTTP fixture and real Tor are distinct. Keep real network opt-in, unique owned project names, quotas/deadlines and no-volume-delete cleanup.

## CHANGE GUIDELINES

Use --links-only for documentation link changes. Exercise negative controls when changing aggregation, exit propagation or prerequisite detection. Do not rerun expensive unavailable Tor scenarios without new evidence.

## RELATED MODULES

[Fixture map](../../test/AGENTS.md); [Product runtime](../../test/admission/AGENTS.md); [Recovery](../recovery/AGENTS.md); [CI](../../.github/AGENTS.md).

## KNOWN LIMITATIONS

Default local verification does not perform real onion acceptance. Runtime logs can include credentials; publish only sanitized diagnostics.
