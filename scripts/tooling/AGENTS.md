# Onionall operational tooling

The shared Docker toolchain builds this crate with pinned Rust and
`Cargo.lock`. The root launcher owns Docker mounts and dispatch.

- `src/runtime.rs` owns local Compose lifecycle and JVM CLI forwarding.
  Preserve project labels, configured proxy, private capability and worker-first
  shutdown. Approval, scheduling and crawl execution belong to the JVM/Frontier.
- `src/candidate.rs` verifies one pinned Tor APT candidate in an offline
  container. Preserve the GnuPG key check, every link of the signature/hash
  chain and the exclusive output directory. Publish the Dockerfile last.
- `src/process.rs` bounds and reaps directly owned child processes.
  Killing a Docker CLI does not prove daemon-owned container teardown.

Reuse the existing launcher rather than introducing another Docker socket
consumer. The offline verification route must have no socket and no network.
Use the `tooling-test` Docker stage and isolated fixtures for verification.
