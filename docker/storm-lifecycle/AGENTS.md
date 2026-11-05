# Nimbus cleanup image

Inherited repository and ancestor AGENTS.md constraints remain in force.

## MODULE PURPOSE

Narrow source-pinned Storm dependency correction for cleanup/shutdown ordering.

## RESPONSIBILITIES

Build the existing Nimbus lock/drain changes without altering crawler behavior or hiding shutdown faults.

## IMPLEMENTED ARCHITECTURE

Dockerfile verifies exact upstream source/JAR inputs, applies nimbus-cleanup.patch and nimbus-cleanup-drain.patch without fuzz and rebuilds the matching classes.

## KEY COMPONENTS

`Dockerfile`, `nimbus-cleanup.patch`, `nimbus-cleanup-drain.patch`, `README.md`.

## DATA FLOW

Exact cached Storm inputs -> verified patch -> compiled replacement classes -> image consumed by runtime probes.

## DEPENDENCIES

Matching Storm/JDK build base and scripts/patches/build-storm-lifecycle.sh.

## INVARIANTS

Retain submit-lock serialization and waiting for running cleanup before Timer/ZooKeeper shutdown. No regex/log exclusions or unrelated dependency modifications.

## CHANGE GUIDELINES

Compare changed Nimbus symbols with the pinned source and run both lock and drain probes against baseline/candidate. Keep provenance and offline-build assumptions explicit.

## RELATED MODULES

[Build entrypoint](../../scripts/patches/AGENTS.md); [Probes](../../test/storm-lifecycle/AGENTS.md).

## KNOWN LIMITATIONS

This image does not supervise arbitrary child processes or replace archive ownership guards.
