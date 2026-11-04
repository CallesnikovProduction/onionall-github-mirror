# Synthetic archive runtime

Inherited repository and ancestor AGENTS.md constraints remain in force.

## MODULE PURPOSE

Controlled archive pipeline and reusable internal SOCKS fixture.

## RESPONSIBILITIES

Exercise HTML archive/catalog behavior with bounded fixture pages, retry and reopen checks.

## IMPLEMENTED ARCHITECTURE

local_integration.py creates its explicit synthetic graph and stock-fixture clients; socks_fixture.py serves fake onion responses internally. test_socks_fixture.py checks fixture behavior.

## KEY COMPONENTS

`local_integration.py`, `socks_fixture.py`, `test_socks_fixture.py`.

## DATA FLOW

Synthetic page/link/retry input -> controlled Storm graph/TorProtocol -> archive objects/catalog -> readback.

## DEPENDENCIES

Docker/app image, archive classes, configured fixture Frontier and owned temporary evidence/state.

## INVARIANTS

Never forward SOCKS to external destinations or treat stock clients as valid for protected product mutations. Preserve fixture ownership, content limits and immutable archive assertions.

## CHANGE GUIDELINES

Run SOCKS helper tests for fixture changes and actual archive runtime when available. Keep graph/client distinctions visible when reusing helpers in product tests.

## RELATED MODULES

[Archive](../../src/main/java/dev/onionall/brawler/archive/AGENTS.md); [Product graph](../admission/AGENTS.md).

## KNOWN LIMITATIONS

Archive fixture behavior alone does not establish durable Admission or generated-product authorization.
