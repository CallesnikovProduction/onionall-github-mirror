# Kotlin TODO resolution — 2026-11-01

Scope: production Kotlin only, baseline42ddd7b, migration/java25-storm31.
23 comment occurrences (including duplicate TPR-01), no Kotlin TODO() placeholders.
RESOLVED1 / OBSOLETE7 / DEFERRED3 / BLOCKED12. Counts refer to original occurrences.

Location below is at baseline42ddd7b; source line numbers move after removal.

| File / location | Original concern | Resolution | Evidence / closure criterion | Commit |
|---|---|---|---|---|---|
| TorPolicy.kt:143 (TP-01) | * TODO(TP-01): Проверить нестандартные формы URI, | OBSOLETE | malformedOrOutOfScopeUrlsAreRejected; encodedPathQueryArePreservedAndNormalizationIsIdempotent; strict authority rejects opaque/encoded authority before path access | 83ed4d6 |
| TorPolicy.kt:146 (TP-03) | * TODO(TP-03): Подтвердить тестами, что нормализация | OBSOLETE | malformedOrOutOfScopeUrlsAreRejected; encodedPathQueryArePreservedAndNormalizationIsIdempotent; strict authority rejects opaque/encoded authority before path access | 83ed4d6 |
| TorPolicy.kt:201 (TP-04) | * TODO(TP-04): Сопоставить проверки с реальными ключами, | BLOCKED | Need exhaustive metadata consumer inventory, beyond proxy-key regressions | 83ed4d6 |
| TorPolicy.kt:243 (TP-02) | * TODO(TP-02): Проверить алгоритм по независимым тестовым векторам. | BLOCKED | Need independent externally sourced version/checksum vectors; current generated vectors insufficient | 83ed4d6 |
| TorProtocol.kt:77 (TPR-01) | * TODO(TPR-01): Подтвердить интеграционными тестами, | BLOCKED | Need controlled real Tor E2E+resolver guard+outage; unit SOCKS ATYP3 is scoped evidence only | 83ed4d6 |
| TorProtocol.kt:116 (TPR-02) | * TODO(TPR-02): Проверить поведение кеша при повторной | BLOCKED | Need deterministic TTL-expiry/concurrent cache-miss test; current synchronized cache serializes misses | 83ed4d6 |
| TorProtocol.kt:141 (TPR-03) | * TODO(TPR-03): Проверить контракт Storm при частичной ошибке инициализации. | BLOCKED | Need actual partial topology prepare failure/ProtocolFactory ownership test | 83ed4d6 |
| TorProtocol.kt:143 (TPR-04) | * TODO(TPR-04): Проверить, что запрет небезопасных | BLOCKED | Need enumerated configuration aliases and negative fixtures | 83ed4d6 |
| TorProtocol.kt:193 (TPR-05) | // TODO(TPR-05): Проверить разрешение имени SOCKS gateway. | BLOCKED | Need gateway replacement/new-IP test; existing instance pins resolved endpoint | 83ed4d6 |
| TorProtocol.kt:237 (SOCKS-isolation) | // todo: потенциально нужна изоляция (SOCKS-аутентификация и подтврждение поведения в тестах) | DEFERRED | Backlog below; current timeout bound and zero idle pool retained; no promise of per-request circuits | 83ed4d6 |
| TorProtocol.kt:276 (TPR-01) | // TODO(TPR-01): Интеграционно подтвердить SOCKS-only маршрутизацию. | BLOCKED | Need controlled real Tor E2E+resolver guard+outage; unit SOCKS ATYP3 is scoped evidence only | 83ed4d6 |
| TorProtocol.kt:280 (TPR-12) | // TODO(TPR-12): Проверить стратегию переиспользования соединений. | DEFERRED | Backlog below; current timeout bound and zero idle pool retained; no promise of per-request circuits | 83ed4d6 |
| TorProtocol.kt:284 (TPR-13) | // TODO(TPR-13): Разделить таймауты при необходимости. | DEFERRED | Backlog below; current timeout bound and zero idle pool retained; no promise of per-request circuits | 83ed4d6 |
| TorProtocol.kt:287 (TPR-14) | // TODO(TPR-14): Проверить сетевые follow-up запросы OkHttp. | BLOCKED | Need controlled TLS/HTTP2/auth follow-up fixtures | 83ed4d6 |
| TorProtocol.kt:321 (TPR-07) | * TODO(TPR-07): Проверить классификацию исключений. | BLOCKED | Need exhaustive SOCKS reply/TLS taxonomy tests; causes preserved, categories coarse | 83ed4d6 |
| TorProtocol.kt:327 (TPR-08) | * TODO(TPR-08): Проверить ограничения размера заголовков на уровне OkHttp. | BLOCKED | Need executable aggregate oversized-header tests for pinned OkHttp | 83ed4d6 |
| TorProtocol.kt:465 (TPR-10) | * TODO(TPR-10): Определить политику повреждённых кодировок/прочих ошибок parser. | RESOLVED | strict decoder REPORT; parser RuntimeException wrapped with cause; malformed/BOM/cache retry regressions; failing-before log todo-before.log | 83ed4d6 |
| TorProtocol.kt:543 (TPR-11) | * TODO(TPR-11): Подтвердить владение lifecycle при остановке Storm topology. | BLOCKED | Need real Storm cleanup ownership verification, not only local cancellation | 83ed4d6 |
| TorScopeFilter.kt:44 (scope:44) | // todo: проверить гарантии жизненного цикла URLFilter. | OBSOLETE | lateinit fails before use; badConfigurationAndUseBeforeConfigureFailFast; no universal framework-order claim | 83ed4d6 |
| TorScopeFilter.kt:70 (scope:70) | // TODO: Проверить валидацию конфигурации TorPolicy. | OBSOLETE | TorPolicy constructor rejects missing/malformed settings; configurationCannotSelectDirectOrIncompleteProxy | 83ed4d6 |
| TorScopeFilter.kt:99 (scope:99) | // TODO: Определить контракт для metadata == null. | OBSOLETE | null metadata never skips target policy; nullMetadataStillEnforcesScopeAndSourceDoesNotGrantAuthority | 83ed4d6 |
| TorScopeFilter.kt:106 (scope:106) | // TODO: Проверить нормализацию URL. | OBSOLETE | wireDotSegmentsCannotBypassRobotsPath; encodedPathQueryArePreservedAndNormalizationIsIdempotent | 83ed4d6 |
| TorScopeFilter.kt:113 (scope:113) | // TODO: Проверить контракт исключений TorPolicy. | OBSOLETE | IOException becomes null; uninitialized/configuration errors propagate; TorPolicyTest scopeFilterRejectsInvalidConfigurationAndKeepsScopeWithNullMetadata | 83ed4d6 |

## Deferred backlog

- M3 deployment policy: TorProtocol client/pool isolation and TPR-12 share one requirement. Define whether per-source or per-request circuit isolation is needed before adding SOCKS credentials. Test transport isolation through owned Tor with isolation flags; TCP pool policy is not circuit isolation.
- M3 latency qualification: TPR-13 separate connect/read/call timeouts only after bounded representative Tor latency measurements; preserve present finite total deadline until evidence justifies tuning.

## Verified boundary and operational change

TPR-10 now rejects malformed UTF-8 and BOM-selected UTF-16 rather than relying on crawler-commons replacement decoding. A corrupted rule could change match semantics. Empty files, valid BOMs, wildcard/agent selection and parser grammar remain supported. Parser RuntimeException is wrapped as ROBOTS_UNAVAILABLE with cause, never cached as ALLOW_ALL. No invented robots grammar or parser replacement.

The updated existing test failed before fix (1 failure), then verify47 passed. Final expanded verification and Python results recorded below. Loopback SOCKS tests exercise real TorProtocol without Tor or public network. No Tor, Docker, full M2/M3 gate or Windows execution in this pass. No new supervisors, dependency upgrades, frontier changes or network configuration changes.

M2 recovery, live Tor A/B/C, durable global admission (ADR0002), Tor security delivery and untested StormCrawler modules remain blocked. BLOCKED rows are unfulfilled evidence contracts, not claims of demonstrated vulnerabilities or impossibility. Source TODOs retain responsibility and explicit closure criteria.

Final Java25 offline Maven verify:48 tests PASS; loopback SOCKS malformed UTF-8/UTF-16 rejection and valid retry/no-cache PASS. Python M1:31 passed/1 skipped, M2:67 passed, M3:2 passed. git diff --check PASS. Evidence is ignored in .runtime/platform-migration/todo-*.log.
