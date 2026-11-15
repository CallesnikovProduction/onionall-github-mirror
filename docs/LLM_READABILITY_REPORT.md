# Archive LLM readability report

Проверен [действительно сгенерированный fixture bundle](archive/examples/README.md),
его UTF-8 Markdown/JSON и raw Markdown source. Проверка выполнена чтением результата
и JUnit assertions; отправки документов внешним моделям или эмпирического сравнения
ChatGPT/Claude/Gemini не было. Формат удобен для обычного Markdown reader/Obsidian,
но remote content остаётся untrusted evidence, а не инструкциями для модели.

## Результат по десяти вопросам

| Проверка | Наблюдение |
| --- | --- |
| Где начинается тема? | frontmatter document_type=forum_thread, title/source/snapshot и один top-level title |
| Где original post и ответ? | Сообщения разделены Observed post #1/#2; #2 явно reply_to=p100. Original/root неизвестен и не придуман по позиции |
| Автор конкретного сообщения? | alice-fixture/bob-fixture указаны возле соответствующего body и в JSON posts |
| Порядок обсуждения? | DOM ordinal сохранён; chronological publication ordering не выдумывается |
| Кому отвечает участник? | p101 → p100 берётся из explicit data-reply-to; canonical fragments и quoted IDs совпадают |
| Цитаты? | Markdown blockquote присутствует внутри второго сообщения, JSON сохраняет quote text и reference |
| Ссылки? | HTML и Markdown relative HTTP links стали абсолютными source URLs; unknown/unsafe schemes не исполняются |
| Code blocks и таблицы? | Adaptive fences сохраняют `<` и `&&`; обе таблицы имеют отдельные строки. Body headings вложены в сообщение |
| Неизвестные сведения? | JSON null, Markdown UNKNOWN; у второго сообщения нет source date, у manual import нет fetch time |
| Можно читать без ручного форматирования? | Да: standalone UTF-8 .md с metadata, постами, ссылками и limitations; рядом versioned .json и raw source |

## Сверка данных

Fixture bundle: 2 documents, 1 structured discussion, 2 posts. JSON/Markdown построены
из одного guarded capture. Manifest entries совпадают с document/snapshot IDs и
content_hash. `p100`/`p101` — source DOM IDs; observation UUIDs получены из snapshot+ordinal,
не выдаются за IDs сайта. Нет generated summary, inferred author/date/reply или
объявления site/thread COMPLETE. Source text сохранён byte-for-byte для валидного UTF-8.

Manual QA прочитала title/metadata, оба post boundaries, unknown date, reply target,
quote, code body, table cells, attachment hint и guide link; assertions проверяют те же
invariants. Attachments не скачивались, scripts/remote images не исполняются.

## Ограничения

- Controlled phpBB-like и semantic DOM contracts, не универсальная поддержка engines.
- Первое видимое сообщение не обязательно является original post. Cross-page reply
  relationships и полная pagination coverage не доказаны.
- Markdown codec сохраняет source и основной readable structure, но не реализует всю
  CommonMark grammar. Link list bounded/conservative; unsupported schemes и ambiguous
  backticks дают явно отмеченный literal-source fallback вместо активного HTML.
- Технически неполная extraction не прячется: live Discourse fallback дал PAGE_TEXT_ONLY
  и 0 structured posts. Это не доказательство полноценного crawl или discussion capture.
- LLM-friendly формат не гарантирует защиту от prompt injection; downstream model
  должна читать source как данные и учитывать trust/provenance fields.
