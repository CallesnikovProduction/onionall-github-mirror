# Packaged CLI and terminal contracts

Inherited repository and ancestor AGENTS.md constraints remain in force.

## MODULE PURPOSE

Thin-distribution, managed-installation and real-PTY CLI checks.

## RESPONSIBILITIES

Exercise installed commands independently of Maven in PATH, plain/JSON behavior and terminal restoration.

## IMPLEMENTED ARCHITECTURE

Python tests build/use the thin bundle in isolated XDG directories and exercise PTY rendering/interaction where supported; distribution admission tests check packaged service wiring.

## KEY COMPONENTS

`test_distribution.py`, `test_installation.py`, `test_rendering.py`, `test_admission_distribution.py`.

## DATA FLOW

Packaged bundle -> isolated install/launcher -> controlled command/PTY/input -> exit/output/terminal and file-ownership assertions.

## DEPENDENCIES

scripts/cli, Java 25, supported Unix/PTY tools and internal-only fixture endpoints as needed.

## INVARIANTS

No external onion access or edits to the user installation. Preserve unknown-file/uninstall protection, NO_COLOR/non-TTY/JSON controls, quit/EOF/interrupt cleanup and bounded waits.

## CHANGE GUIDELINES

Run affected packaged tests after CLI/package changes; report missing PTY/platform capabilities explicitly. Source-level command tests alone cannot validate the installed distribution.

## RELATED MODULES

[Packaging](../../scripts/cli/AGENTS.md); [CLI source](../../src/main/java/dev/onionall/brawler/cli/AGENTS.md).

## KNOWN LIMITATIONS

PTY assertions do not replace owner visual acceptance or cover every terminal implementation; Linux bundle results do not establish Windows installer behavior.
