# External fixtures and acceptance boundaries

Inherited repository and ancestor AGENTS.md constraints remain in force.

## MODULE PURPOSE

Runtime, packaged CLI and script fixtures outside Maven JUnit.

## RESPONSIBILITIES

Keep scenario ownership, actual graph/server identity, network scope and evidence provenance explicit; group small helpers with their scenario.

## IMPLEMENTED ARCHITECTURE

admission/ exercises generated authenticated product; archive/ a synthetic archive graph; m1/ derived legacy HTTP; m2/ Tor/transport/recovery; m3/ local discovery; cli/ packaged terminal; scripts/ tooling; storm-lifecycle/ dependency probes. security/ contains the Tor candidate verifier unit test.

## KEY COMPONENTS

Each meaningful subtree has local AGENTS.md; package-only descendants inherit it. Java probes here are harness tools, not production entrypoints.

## DATA FLOW

Fresh owned inputs/resources -> selected graph/probe -> structured state assertions -> bounded owned cleanup -> ignored evidence.

## DEPENDENCIES

Built application/Frontier/images and platform tools as declared by each fixture; unit helper tests do not require the same runtime prerequisites as full scenarios.

## INVARIANTS

No real onion requests without explicit authorization. Internal fake SOCKS must never forward externally. No weakened assertions, broad kill selectors, volume deletion, secret uploads or substitution of legacy evidence for product guarantees.

## CHANGE GUIDELINES

Run affected unit/harness checks first, then the actual relevant runtime gate when available. Keep unavailable, skipped, failed and executed checks distinct in ledgers; avoid test results in AGENTS.md.

## RELATED MODULES

[admission](admission/AGENTS.md); [archive](archive/AGENTS.md); [m1](m1/AGENTS.md); [m2](m2/AGENTS.md); [m3](m3/AGENTS.md); [cli](cli/AGENTS.md); [scripts](scripts/AGENTS.md); [storm-lifecycle](storm-lifecycle/AGENTS.md).

## KNOWN LIMITATIONS

Synthetic SOCKS cannot prove official Tor routing; component/legacy tests cannot prove generated-product durability. test/security is bounded artifact verification, not a comprehensive security audit.
