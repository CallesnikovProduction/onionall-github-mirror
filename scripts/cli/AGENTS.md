# CLI build and installation

Inherited repository and ancestor AGENTS.md constraints remain in force.

## MODULE PURPOSE

Build the application and thin user-installable CLI distribution.

## RESPONSIBILITIES

Select documented tools, assemble explicit dependencies and manage only recognized CLI installation files.

## IMPLEMENTED ARCHITECTURE

build-project.ps1 is the Windows project build entrypoint. build-cli.sh uses the root wrapper and an allowlisted thin package; install/uninstall/launcher implement the managed XDG user installation.

## KEY COMPONENTS

`build-project.ps1`, `build-cli.sh`, `install-cli.sh`, `uninstall-cli.sh`, `cli-launcher.sh`.

## DATA FLOW

Verified JDK/wrapper/cache -> application package -> thin target/onionall bundle -> recognized managed installation -> launcher.

## DEPENDENCIES

Root Maven configuration/wrapper, Java application packages, Unix tools for the Bash distribution and test/cli contracts.

## INVARIANTS

No system configuration changes or silent tool downgrades. Thin CLI must not drag server/Flux/Tika implementations into runtime. Preserve managed manifests, unknown-file protection and user extras on uninstall.

## CHANGE GUIDELINES

Check build/installer failure propagation and package contents, then affected packaged CLI/installation/PTY tests. Never treat Windows Maven compilation as Linux distribution acceptance.

## RELATED MODULES

[Distribution tests](../../test/cli/AGENTS.md); [Script contracts](../../test/scripts/AGENTS.md); [Tool bootstrap](../../.mvn/AGENTS.md).

## KNOWN LIMITATIONS

Installer ownership covers its manifest, not arbitrary destination content. Tool/cache availability and shell portability are separate prerequisites.
