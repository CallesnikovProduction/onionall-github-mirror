# Onionall Brawler — Scripts Engineering Rules

## Purpose

The `scripts/` directory contains operational tooling, build entrypoints, acceptance orchestration, recovery utilities, and dependency maintenance tools.

Keep this directory organized, safe, cross-platform, and reproducible.

Follow the root `AGENTS.md`. These rules add requirements specific to `scripts/`.

## Directory Ownership

|Directory|Responsibility|
|---|---|
|`cli/`|CLI build, installation, uninstallation, packaging, and launch|
|`runners/`|M0/M1/M2/M3 scenario orchestration and acceptance execution|
|`recovery/`|Runtime supervision, Tor gateway recovery, quarantine, lifecycle handling|
|`lib/`|Reusable infrastructure functions with clearly defined contracts|
|`security/`|Artifact verification, Tor security checks, cryptographic verification|
|`patches/`|Reproducible third-party dependency patches and build procedures|
|`legacy/`|Historical tooling retained for compatibility or investigation|

**Rule:** Never create a new top-level category without demonstrating that existing categories are insufficient.

## Placement Rules

1. Place scripts according to responsibility, not programming language.
2. Keep public entrypoints in `scripts/` when existing users, tests, documentation, or CI depend on those paths.
3. Put implementation details in the appropriate subdirectory.
4. A public wrapper should be minimal: locate the repository, select the runtime, pass arguments, and preserve exit codes.
5. Do not introduce a new script when an existing module can reasonably own the functionality.
6. Shared functionality belongs in `lib/` only when reuse or separation of concerns is justified.
7. Tests belong in `test/`, not mixed with operational implementations.
8. Operational code must not depend on test modules as its primary implementation.
9. Preserve the existing public command-line interface unless an explicit migration is approved.

## Dependency Boundaries

Recommended dependency direction:

`public entrypoint → runner/recovery/cli component → shared library`

Avoid circular imports. Avoid hidden dependencies on the current working directory. Resolve paths relative to the repository root. Prefer structured results and typed failure categories over parsing arbitrary log messages.

Keep domain-level crawler logic in the JVM application rather than duplicating it in Python scripts.

## Cross-Platform Requirements

Maintain Windows and Linux compatibility for supported commands.

Prefer one platform-neutral Python implementation behind thin Bash/PowerShell wrappers when the same orchestration logic would otherwise be duplicated.

Do not force unrelated build tools or tiny shell utilities into Python.

Ensure wrappers propagate arguments and exit codes consistently.

## Recovery and Acceptance Invariants

- Never treat process exit code 0 as sufficient proof of successful recovery.
- Preserve existing M1/M2/M3 acceptance semantics and assertions.
- Distinguish execution success from verified acceptance.
- Verify persistent Frontier transitions when relevant.
- Preserve Tor-only routing, remote hostname resolution, and fail-closed behavior.
- Use bounded retries, explicit deadlines, and structured recovery states.
- Preserve persistent volumes, onion keys, and evidence.
- Do not change host VPN, DNS, routes, or firewall settings without explicit approval.
- Do not conceal failures by suppressing logs or weakening tests.

## Cleanup Safety

Forbidden without explicit approval:

- `docker compose down -v`
- `docker volume prune`
- Destructive Git resets or force-push
- Deleting `.runtime` evidence
- Deleting `.secrets`
- Removing Tor state or onion-service identity material

Temporary resources must be uniquely identified and cleaned up without affecting unrelated environments.

## Refactoring Policy

Before relocating or consolidating scripts:

1. Identify all callers: other scripts, Dockerfiles, Compose, tests, CI, README, and documentation.
2. Preserve existing public entrypoints.
3. Separate structural refactoring from behavior changes whenever practical.
4. Verify import paths, file permissions, argument forwarding, and exit codes.
5. Run relevant unit and integration tests.
6. Compare acceptance contracts before and after migration.
7. Document changed paths and compatibility behavior.
8. Commit verified increments.

## Documentation Requirements

Each nontrivial script or module must have a clear purpose and documented usage.
Document non-obvious contracts, side effects, dependencies, and failure behavior.
Avoid verbose comments that merely restate implementation.
Update the scripts inventory and root documentation when public behavior changes.

## Agent Decision Rule

Before creating any script, determine:

- What responsibility does it own?
- Is that responsibility already implemented?
- Which directory should own it?
- Is it a public entrypoint or internal implementation?
- What persistent state can it modify?
- How is its success verified?
- How will Windows/Linux compatibility be preserved?

Prefer predictable structure, explicit ownership, and verified correctness over additional files or abstractions.

---

## Agent-Native Maintainability and Autonomous Refactoring

### Primary Principle

The `scripts/` subsystem is designed for long-term autonomous maintenance by AI coding agents across different models, sessions, operating systems, and development environments.

Optimize for **machine-discoverable structure, explicit contracts, deterministic behavior, and safe automated modification**.

Human-oriented presentation is secondary to correctness and agent navigability. However, never intentionally introduce obfuscation, cryptic identifiers, compressed logic, or unnecessary complexity. These reduce maintainability for both humans and agents.

### 1. Self-Describing Components

Every nontrivial script must expose or document:

- Its responsibility and intended execution context.
- Required inputs, arguments, and environment variables.
- Outputs, exit codes, and generated artifacts.
- Dependencies on other scripts and external tools.
- Persistent state it reads or modifies.
- Side effects, cleanup behavior, and failure conditions.
- Relevant invariants and verification commands.

Prefer explicit interfaces and structured results over implicit conventions.

### 2. Agent Discoverability

Agents must be able to understand the scripts subsystem without relying on previous conversation history.

Maintain a concise `scripts/README.md` containing:

- A categorized inventory of supported scripts.
- Public entrypoints and example invocations.
- The dependency and execution flow between major components.
- The location of acceptance and recovery evidence.
- The distinction between operational tooling and test harnesses.

Update this inventory when adding, moving, replacing, or retiring scripts.

Avoid duplicating implementation details across documentation files.

### 3. Autonomous Refactoring Authority

AI coding agents are authorized and expected to refactor scripts when doing so improves:

- Correctness or reliability.
- Architectural consistency.
- Testability.
- Reusability.
- Cross-platform behavior.
- Dependency boundaries.
- Resource and lifecycle management.
- Machine discoverability.

Do not require manual human refactoring for routine structural improvements.

Before changing a component, inspect its callers, tests, contracts, and persistent-state effects.

Preserve public behavior unless the task explicitly authorizes a behavioral change.

Do not perform unrelated large-scale refactoring during active incident recovery.

### 4. Contract-Driven Changes

Treat documented interfaces and acceptance invariants as the source of truth.

For nontrivial changes:

1. Identify the existing behavioral contract.
2. Determine the smallest coherent modification.
3. Update implementation and affected interfaces.
4. Add or update regression tests.
5. Verify backward compatibility.
6. Update documentation only where behavior or ownership changed.
7. Commit the verified change as a coherent unit.

Do not change tests merely to make incorrect behavior pass.

### 5. Structured Machine-Readable Results

Prefer structured JSON output for complex orchestration, recovery, and acceptance results.

Where practical, define stable schemas with explicit versioning.

Include:

- Scenario or operation identifier.
- Execution state.
- Failure category.
- Start and completion timestamps.
- Evidence locations.
- Relevant resource identifiers.
- Verification outcome.

Keep human-readable logs for diagnosis, but do not use arbitrary log-text matching as the primary correctness contract when structured evidence is available.

Never include credentials, onion private keys, bridge secrets, or sensitive content in structured results.

### 6. Deterministic Verification

Design scripts so agents can verify changes without unnecessarily launching expensive end-to-end environments.

Prefer a verification hierarchy:

- Syntax and static checks.
- Unit tests.
- Contract and integration tests.
- Targeted acceptance.
- Full acceptance when required.

Separate deterministic application tests from experiments dependent on external Tor-network availability.

Do not claim successful recovery based solely on exit code zero.

### 7. Safe Autonomous Consolidation

Before creating a new script, search for existing implementations of the same responsibility.

Prefer extending or extracting a coherent component over creating duplicate utilities.

Consolidate repeated logic when doing so reduces maintenance burden without coupling unrelated workflows.

Do not consolidate components solely to minimize file count.

Preserve existing public entrypoints through compatibility wrappers when necessary.

Do not delete historical scripts without verifying their callers and documenting their replacement or retirement.

### 8. Agent Handoff and Context Recovery

Maintain durable engineering context in the repository rather than relying on chat history.

For substantial or interrupted work, record:

- Current objective.
- Verified changes.
- Remaining blockers.
- Relevant source paths.
- Tests executed and their outcomes.
- Evidence paths.
- Next executable step.

Use the existing engineering execution ledger when available.

Keep handoff notes concise, factual, and actionable.

Do not repeat completed investigations without new contradictory evidence.

### 9. Complexity Discipline

Optimize for **minimum total maintenance complexity**, not minimum line count.

Prefer:

- Clear module boundaries.
- Explicit dependencies.
- Small coherent functions.
- Stable interfaces.
- Typed states and failure categories.
- Predictable error propagation.
- Idempotent operations where appropriate.
- Bounded resource usage.
- Reproducible execution.

Avoid:

- Clever but opaque one-liners.
- Hidden global state.
- Dynamic imports without justification.
- Undocumented environment assumptions.
- Duplicate Bash/PowerShell business logic.
- Circular dependencies.
- Unbounded retries.
- Silent exception handling.
- Excessive abstraction layers.

### 10. Continuous Architectural Hygiene

When working in `scripts/`, agents should opportunistically correct small, directly related structural issues.

Examples include:

- Removing obsolete imports.
- Consolidating duplicate validation.
- Correcting inconsistent argument forwarding.
- Updating stale references.
- Clarifying error contracts.
- Removing resolved TODO markers.

For broader changes, create a separate verified refactoring checkpoint rather than expanding an unrelated task.

Do not sacrifice recovery correctness, security, or acceptance coverage for architectural neatness.

### Success Criterion

A new AI coding agent must be able to:

1. Locate the relevant script using repository documentation.
2. Understand its contract and dependencies.
3. Identify the affected tests.
4. Make a bounded modification.
5. Verify the result.
6. Preserve persistent state and public compatibility.
7. Explain the change using repository evidence.

**The codebase must remain maintainable without dependence on any particular AI model, prompt, chat session, or individual developer.**