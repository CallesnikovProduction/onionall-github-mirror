# Operational tooling contracts

Inherited repository and ancestor AGENTS.md constraints remain in force.

## MODULE PURPOSE

Fast deterministic checks for wrappers, builds, verification aggregation and script callers.

## RESPONSIBILITIES

Use controlled fake tools/inputs to verify failure propagation, checksum rejection, argument forwarding and cleanup boundaries.

## IMPLEMENTED ARCHITECTURE

Python unit tests inspect/invoke script surfaces and generated fixture configuration without substituting for real infrastructure runs.

## KEY COMPONENTS

`test_wrapper.py`, `test_verification.py`, `test_cli_build.py`, `test_install_shell.py`, `test_milestone_shell.py`, `test_product_fixture.py`.

## DATA FLOW

Fake tool/environment/config -> script operation -> exact exit/result/path/ownership assertions.

## DEPENDENCIES

Python and available shell tooling; operational scripts and selected fixture preparers.

## INVARIANTS

No mutation of host tools/user caches/configuration or real Docker state through fake-tool tests. Negative controls must expose failure, not merely match optimistic output.

## CHANGE GUIDELINES

Cover moved entrypoints and CI/Compose callers when scripts change. Keep tests independent of arbitrary cwd and distinguish syntax/contract checks from executed runtime acceptance.

## RELATED MODULES

[Operational source](../../scripts/AGENTS.md); [Verification](../../scripts/runners/AGENTS.md).

## KNOWN LIMITATIONS

Fake-tool success does not prove Docker, Maven dependency availability, network isolation or Tor behavior.
