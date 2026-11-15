# Archive research export example

[Bundle index](research-export/research-index.md) сформирован реальным
`ArchiveDocumentWorkflowTest.actualBundleKeepsPostsEvidenceAndJsonMarkdownInAgreement`:
approved fixture plan → manual HTML/text import → H2/blob persistence → verified capture
→ paired JSON/Markdown export. Это контролируемые тестовые данные, **не Tor crawl**.
Адрес взят из существующей repository fixture; сетевых запросов к нему не было.

В bundle одна обсуждаемая HTML-страница с двумя сообщениями и один Markdown guide.
`MANUAL_IMPORT`, `http_status=0`, `fetched_at=null` сохраняются в JSON. Имена
`alice-fixture`/`bob-fixture`, тексты, thread/post IDs и даты публикации заданы fixture;
они не описывают реальных участников. Snapshot IDs/hashes/recorded_at получены
настоящей записью тестового каталога, source text не дополнялся summary.

- [Thread Markdown](research-export/threads/thread-001.md)
- [Thread JSON](research-export/threads/thread-001.json)
- [Document Markdown](research-export/documents/document-002.md)
- [Document JSON](research-export/documents/document-002.json)
- [Original Markdown source](research-export/documents/document-002.source.txt)
- [Manifest](research-export/manifest.json)

Повторная генерация требует нового output directory: exporter не заменяет существующий.
Подробная проверка: [LLM readability](../../LLM_READABILITY_REPORT.md).
