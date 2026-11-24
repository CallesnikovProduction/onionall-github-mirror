# Security artifact and connectivity utilities

Inherited repository and ancestor AGENTS.md constraints remain in force.

## MODULE PURPOSE

Explicit Tor connectivity diagnostics and pinned candidate artifact verification.

## RESPONSIBILITIES

Verify provenance and expose bounded diagnostics without altering routing or treating connectivity as full acceptance.

## IMPLEMENTED ARCHITECTURE

The shell and PowerShell connectivity entrypoints launch the shared M2
preflight. `./onionall verify-tor-candidate` runs the Rust verifier from
the shared toolchain image with networking disabled and no Docker socket.
GnuPG checks the pinned signing key and signed index. The verifier stages
the exact validated package and metadata bytes.

## KEY COMPONENTS

`check-tor-connectivity.sh`, `check-tor-connectivity.ps1` and
[`candidate.rs`](../tooling/src/candidate.rs).

## DATA FLOW

Explicit target/artifact configuration -> bounded inspection/probe -> sanitized structured diagnostics.

## DEPENDENCIES

Documented Tor endpoint or artifact tooling; test/security and M2 security fixture coverage.

## INVARIANTS

No secret printing, host proxy/firewall changes, unverified binaries or automatic real onion probing. A candidate artifact review is not deployment or full audit acceptance.

## CHANGE GUIDELINES

Keep hash/version/source checks and negative cases. Run the matching utility tests before changing verification criteria; retain clear unavailable prerequisites.

## RELATED MODULES

[Security contracts](../../docs/security/AGENTS.md); [Candidate verifier tests](../../test/security/test_tor_candidate.py); [Runtime checks](../../test/m2/AGENTS.md).

## KNOWN LIMITATIONS

Static artifact provenance and socket connectivity cannot prove runtime isolation, remote availability or every dependency vulnerability.
