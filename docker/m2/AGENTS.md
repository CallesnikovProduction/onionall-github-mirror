# Tor gateway and onion fixture runtime

Inherited repository and ancestor AGENTS.md constraints remain in force.

## MODULE PURPOSE

Official packaged Tor/obfs4 client and controlled onion-service container helpers.

## RESPONSIBILITIES

Validate private bridge input, initialize only designated volume directories and expose sanitized readiness.

## IMPLEMENTED ARCHITECTURE

entrypoint.py accepts allowlisted runtime overrides, validates bounded obfs4 Bridge lines, writes a private temporary torrc and verifies it before launch. health.py uses the local cookie-authenticated control socket and requires bootstrap plus bridges. state_init.py prepares named-volume directory ownership without rewriting keys.

## KEY COMPONENTS

`Dockerfile`, `Dockerfile.security-candidate`, `entrypoint.py`, `health.py`, `state_init.py`, `torrc.gateway`, `torrc.onion`, `bridges.example.conf`.

## DATA FLOW

Read-only private bridge mount + pinned image -> private runtime config -> Tor/obfs4 -> SOCKS endpoint/control health; identity remains on designated named volumes.

## DEPENDENCIES

Distribution Tor/obfs4 packages, Python and Compose user/mount/network configuration.

## INVARIANTS

No direct fallback or bridge/key logging. Preserve restricted permissions, override allowlist, exact secret path, persistent state and signal/exit propagation. Example bridge files contain placeholders, never operational secrets.

## CHANGE GUIDELINES

Validate malformed bridge/config/override handling and health failures locally before authorized real-Tor checks. Changing UID or mount handling must preserve existing keys/data and be reviewed with callers.

## RELATED MODULES

[Recovery](../../scripts/recovery/AGENTS.md); [Tor fixtures](../../test/m2/AGENTS.md); [Security evidence](../../docs/security/AGENTS.md).

## KNOWN LIMITATIONS

Bootstrap/control health does not prove target onion HTTP availability. Gateway and onion fixture have separate state; never reset either to simulate spontaneous recovery.
