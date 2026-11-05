# Default crawler resources

Inherited repository and ancestor AGENTS.md constraints remain in force.

## MODULE PURPOSE

Classpath defaults for parsing, URL filtering and normalization.

## RESPONSIBILITIES

Maintain resource names/JSON plugin identities consumed by legacy Flux and derived configurations; distinguish defaults from job-owned generated resources.

## IMPLEMENTED ARCHITECTURE

parsefilters.json configures domain/mime/keyword metadata filters; jsoupfilters.json extracts metadata/frame links. urlfilters.json chains basic/depth/host/regex/self/sitemap filters with regex resource files.

## KEY COMPONENTS

`parsefilters.json`, `jsoupfilters.json`, `urlfilters.json`, `default-regex-filters.txt` and `default-regex-normalizers.xml`.

## DATA FLOW

Crawler configuration selects classpath plugin resources -> parser/filter chain -> metadata and outlinks. CrawlResources publishes separate ordered job resources in a unique runtime JAR.

## DEPENDENCIES

StormCrawler plugin class names and parameters, root legacy configuration and generated CrawlExecution resources.

## INVARIANTS

Regex/host filters are not a security boundary or Tor transport. Preserve exact resource lookup names and distinguish legacy permissive/default limits from product admission/scope guards.

## CHANGE GUIDELINES

Inspect both root Flux and generated resources before edits; verify ordering, packaged resource resolution and fixture-specific overrides. Do not silently widen scope or change content handling.

## RELATED MODULES

[Resource publication](../java/dev/onionall/brawler/admission/AGENTS.md); [Legacy graph](../../../crawler.flux); [Legacy defaults](../../../crawler-conf.yaml).

## KNOWN LIMITATIONS

Default configuration alone neither selects TorProtocol nor supplies durable admission; product guarantees depend on generated wiring.
