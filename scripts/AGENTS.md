# Operational scripts

Inherited repository and ancestor AGENTS.md constraints remain in force.

## MODULE PURPOSE

Operational build/install entrypoints, verification orchestration, recovery and dependency maintenance.

## RESPONSIBILITIES

Group by responsibility, not language: cli/, runners/, recovery/, security/, patches/. Search for an existing owner before adding tools/categories; do not recreate removed root wrappers or milestone aliases.

## IMPLEMENTED ARCHITECTURE

Public entrypoint -> focused runner/recovery/CLI component -> justified shared helper. Prefer shared Python logic behind thin Bash/PowerShell wrappers when it prevents duplicated business logic; keep unrelated small shell/build tools native.

## KEY COMPONENTS

Local AGENTS.md describe each subtree; README.md inventories supported entrypoints. Tests live under test/, not beside operational implementation.

## DATA FLOW

Explicit arguments/environment -> root-relative paths -> bounded operation -> typed/structured result and preserved exit code -> existing engineering/recovery ledger.

## DEPENDENCIES

JDK/Maven, Python, supported shells and Docker only where required. Runners reuse fixture helpers; do not introduce test-module dependencies as primary operational implementation, hidden cwd state or circular imports.

## INVARIANTS

Preserve CLI paths/arguments/exit codes, persistent data and acceptance assertions. No host network/security changes, destructive cleanup, unbounded retries or log suppression. Distinguish process success, persistence, assisted recovery and real acceptance.

## CHANGE GUIDELINES

Inspect callers in CI, Compose, Dockerfiles, tests and docs before moves. Do not remove compatibility entrypoints without an approved migration. Keep wrappers thin, propagate signals/exit codes and document side effects/prerequisites. Update README inventory when paths change. Prefer small explicit typed components; only directly related cleanup belongs in a scope.

## RELATED MODULES

[cli](cli/AGENTS.md); [runners](runners/AGENTS.md); [recovery](recovery/AGENTS.md); [security](security/AGENTS.md); [patches](patches/AGENTS.md); [Inventory](README.md).

## KNOWN LIMITATIONS

Platform-specific wrappers do not establish cross-platform runtime acceptance. Raw logs are diagnostic/untrusted, not an authenticated event bus; use structured results where available.
