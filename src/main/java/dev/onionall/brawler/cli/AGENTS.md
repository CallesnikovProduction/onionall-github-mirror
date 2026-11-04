# Terminal CLI

Inherited repository and ancestor AGENTS.md constraints remain in force.

## MODULE PURPOSE

Picocli commands and bounded terminal presentation for local crawler workflows.

## RESPONSIBILITIES

Parse arguments, present service results and manage terminal/input cleanup; keep crawl decisions in CrawlService and persistence in ArchiveStore.

## IMPLEMENTED ARCHITECTURE

Onionall is the entrypoint. Brand/CliPresentation render trusted presentation after clipping untrusted text. InteractiveSession owns supported-TTY alternate screen/input; Mascot renders headless Java2D with a text fallback.

## KEY COMPONENTS

`Onionall`, `ArchiveCommands`, `HtmlFiles`, `Brand`, `CliPresentation`, `InteractiveSession` and `Mascot`.

## DATA FLOW

Arguments -> service/catalog operations -> deterministic plain/JSON or supported-TTY output. inspect/discover read bounded local HTML; fetch uses the configured fixed smoke scope through TorProtocol. status TCP probing is local connectivity only.

## DEPENDENCIES

Picocli, JSoup, Java desktop/headless rendering, admission/archive services and Kotlin URL policy. Thin CLI packaging must retain required runtime classes.

## INVARIANTS

NO_COLOR/--no-color, JSON and non-TTY output stay plain with no terminal controls. Restore terminal/cursor on quit, EOF, interrupt and handled errors. Offline commands initiate no crawl; explicit exports are atomic and never overwrite unrelated files.

## CHANGE GUIDELINES

Preserve flags, JSON fields, exit codes, input/content bounds and control-character escaping. Verify both unit command behavior and packaged CLI/PTY paths for terminal or dependency changes.

## RELATED MODULES

[Crawl service](../admission/AGENTS.md); [Catalog](../archive/AGENTS.md); [Packaged CLI tests](../../../../../../../test/cli/AGENTS.md).

## KNOWN LIMITATIONS

TCP success is not Tor bootstrap/onion readiness. Interactive rendering depends on supported terminal capabilities; Java desktop is required for Java2D surfaces.
