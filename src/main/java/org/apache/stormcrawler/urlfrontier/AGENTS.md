# URLFrontier: capability-aware адаптеры

Действуют корневые инструкции и [общие Java-соглашения](../../../../dev/onionall/brawler/AGENTS.md).
Классы основаны на StormCrawler 3.7.0. Apache copyright и исходные SHA256 сохраняются
как provenance baseline, а не как хэш текущих изменённых файлов:

- Spout.java: `031aa003910552f9327785cf378beb327f213f691dc40b688187c3d1864ecd91`.
- StatusUpdaterBolt.java: `de66cdf9184c8283d9a944bee57bd681d1863237f64eb4caa9b30951811b2558`.

## Ответственность и API

[CapabilitySpout](CapabilitySpout.java) наследует AbstractQueryingSpout и заполняет
inherited buffer результатами authenticated server-streaming GetURLs.
GetURLs приобретает leases; Frontier владеет расписанием и reservations.

```java
public synchronized void open(Map<String, Object> stormConf, TopologyContext context, SpoutOutputCollector collector);
protected void populateBuffer();
public void ack(Object msgId);
public void fail(Object msgId);
public synchronized void close();
```

open/close сериализованы. Ошибка open запускает cleanup, сохраняя исходное исключение
и suppressed cleanup failure. close идемпотентен, null-safe для своего канала;
superclass cleanup выполняется только после начала superclass.open, канал закрывается
в finally через shutdownNow. Повторное открытие закрытого экземпляра запрещено.
Metadata/key/URL, query completion и ack/fail делегирование сохраняют upstream подход.

[CapabilityStatusUpdaterBolt](CapabilityStatusUpdaterBolt.java) наследует
AbstractStatusUpdaterBolt, реализует RemovalListener<String, List<Tuple>> и
StreamObserver<AckMessage>. Scheduler, partitioner, configuration keys и преобразование
status в DiscoveredURLItem/KnownURLItem с nextFetch сохраняют upstream контракт.

```java
public void prepare(Map<String, Object> stormConf, TopologyContext context, OutputCollector collector);
public void store(@NotNull String url, @NotNull Status status, @NotNull Metadata metadata, @NotNull Optional<Date> nextFetch, @NotNull Tuple t);
public void onNext(AckMessage confirmation);
public void onError(Throwable error);
public void onCompleted();
public void onRemoval(@Nullable String key, @Nullable List<Tuple> values, @NotNull RemovalCause cause);
public void cleanup();
```

## Streams, recovery и credentials

- prepare открывает один authenticated PutURLs stream. openStream создаёт generation;
  callbacks старой generation после terminal event/cleanup игнорируются.
- streamLock защищает prepare, публикацию/использование requestObserver, callbacks,
  recovery и cleanup. Вызовы gRPC асинхронные; ожидания сети/termination под lock нет.
- onError/onCompleted инвалидируют generation, снимают observer и fail-ят все pending tuples.
  Неизвестный server outcome не превращается в ACK. Дальнейшую доставку выполняет Storm replay;
  адаптер не пересылает неподтверждённые сообщения самостоятельно.
- После recoverable termination следующая store делает не более одной попытки создания stream.
  Также сохранён callback восстановления при переходе из TRANSIENT_FAILURE.
  Фонового reconnect-loop, нового retry timer и собственной очереди повторов нет.
- PERMISSION_DENIED/UNAUTHENTICATED блокируют новые отправки: tuples получают fail.
  Latch снимается transport recovery из TRANSIENT_FAILURE либо новым экземпляром компонента.
  Исправление файла при неизменном READY-канале само по себе не снимает auth latch.
- Каждый новый stub использует FrontierCapability и withWaitForReady. Файл credentials
  перечитывается при начале RPC; уже открытый PutURLs stream не перечитывает его для URLItem.
  Анонимного fallback нет. withWaitForReady не гарантирует deadline или успешный recovery.
- Синхронное исключение подготовки/отправки сообщения fail-ит pending и завершает client stream.
  Успешно применённая, но неподтверждённая запись может попасть в replay: exactly-once серверного
  применения не заявляется. Shared capability не разделяет operator/worker authority.

## ACK, backpressure и concurrency

У каждой отправки уникальный UUID в URLItem.ID; URL остаётся в URLInfo.url.
Существующий сервер возвращает непустой ID в AckMessage.ID без замены на URL
(см. [AbstractFrontierService](../../../../../../../frontier-server/src/main/java/crawlercommons/urlfrontier/service/AbstractFrontierService.java)).
Протокол/protobuf не изменён. Другой server, не возвращающий opaque ID, несовместим
с этой корреляцией; fallback к ACK по URL отсутствует.

Authoritative pending map хранит request ID → (URL, tuple); pendingUrls считает
незавершённые отправки URL. Caffeine cache с тем же request ID отвечает за expiry.
Настроенный expireAfterWrite остаётся в секундах. Removal listener может быть асинхронным;
время callback не гарантируется точно по TTL.

Порядок lock: streamLock → waitAckLock. Eviction использует только waitAckLock.
Один владелец pending.remove выполняет ACK/fail и release одного permit;
повторный ACK, поздний ACK после eviction и explicit cache invalidation повторного release не дают.
Новый status того же URL получает другой ID. Новый DISCOVERED при pending URL сохраняет
upstream fast ACK без второй отправки и сразу возвращает свой permit.

Semaphore ограничивает pending tuples. Во время timed ожидания выполняется cache.cleanUp.
Interrupt ожидания fail-ит tuple один раз, сохраняет interrupt flag и выходит без захвата permit.
Фонового busy-spin и повторного логирования interrupt нет. Store/cleanup после закрытия
не создают streams. ACK/fail вызываются в контексте lifecycle/response/eviction callback;
этот код не переносит collector-вызовы на новый executor.

## Cleanup и границы

cleanup устанавливает closed и инвалидирует generation до завершения observer.
Даже при exception из onCompleted пытается fail-ить весь pending и закрыть канал;
первое RuntimeException сохраняется, последующие добавляются suppressed.
Повторный cleanup не выполняет операции снова. Failed prepare вызывает тот же cleanup.
shutdownNow запрашивает завершение channel, но адаптер не ожидает awaitTermination.

Изменения Onionall относительно upstream: mandatory credentials; interrupt exit;
per-message opaque IDs и однократное разрешение pending; generation-aware stream recovery;
синхронизация observer; drain pending и partial/idempotent cleanup. Поэтому утверждение
«отличаются только имена и credentials» больше не применимо.

Покрытие и ограничения смотрите в [CapabilityClientsTest](../../../../../../../src/test/java/org/apache/stormcrawler/urlfrontier/CapabilityClientsTest.java)
и [CapabilityReliabilityTest](../../../../../../../src/test/java/org/apache/stormcrawler/urlfrontier/CapabilityReliabilityTest.java):
loopback RPC/auth, reconnect/rotation, interruption, eviction/late ACK, duplicate discovery,
concurrent submit/recovery, auth latch, partial prepare и shutdown exception.
Эти fixtures не доказывают полный Storm deployment, durability Frontier или real Tor acceptance.

[Архивный updater](../../../../dev/onionall/brawler/archive/AGENTS.md) сохраняет observations
перед store; эти observations не означают server ACK. Stock clients остаются отдельным fixture.
Изменения сохраняют upstream provenance и значимые комментарии; формат новых методов
и русские Javadocs следуют общим соглашениям. Дальнейший upstream upgrade требует
отдельной сверки этих отклонений, ACK/fail и credentials.
