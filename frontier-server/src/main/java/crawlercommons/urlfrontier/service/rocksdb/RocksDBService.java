// SPDX-FileCopyrightText: 2020 Crawler-commons
// SPDX-License-Identifier: Apache-2.0

package crawlercommons.urlfrontier.service.rocksdb;

import com.google.protobuf.InvalidProtocolBufferException;
import crawlercommons.urlfrontier.CrawlID;
import crawlercommons.urlfrontier.Urlfrontier.AckMessage.Status;
import crawlercommons.urlfrontier.Urlfrontier.KnownURLItem;
import crawlercommons.urlfrontier.Urlfrontier.Stats;
import crawlercommons.urlfrontier.Urlfrontier.URLInfo;
import crawlercommons.urlfrontier.Urlfrontier.URLItem;
import crawlercommons.urlfrontier.Urlfrontier.URLStatusRequest;
import crawlercommons.urlfrontier.service.AbstractFrontierService;
import crawlercommons.urlfrontier.service.QueueInterface;
import crawlercommons.urlfrontier.service.QueueWithinCrawl;
import crawlercommons.urlfrontier.service.SynchronizedStreamObserver;
import io.grpc.netty.shaded.io.netty.util.internal.StringUtil;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.rocksdb.BlockBasedTableConfig;
import org.rocksdb.BloomFilter;
import org.rocksdb.ColumnFamilyDescriptor;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.ColumnFamilyOptions;
import org.rocksdb.DBOptions;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.RocksIterator;
import org.rocksdb.Statistics;
import org.rocksdb.StatsLevel;
import org.rocksdb.WriteBatch;
import org.rocksdb.WriteOptions;
import org.slf4j.LoggerFactory;

/**
 * <b>Служба Frontier с постоянным хранением в RocksDB и допуском Admission.</b>
 *
 * <p>Сохраняет внешний контракт исходной реализации Crawler-commons и
 * координирует запись расписания, выдачу URL, удаление очередей и остановку.
 * Восстановление счётчиков передаётся QueueRecovery, кодирование ключей и
 * чтение состояния расписания — UrlIndex.</p>
 *
 * <p>Границы ответственности: служба остаётся владельцем базы, дескрипторов,
 * блокировок и жизненного цикла. AdmissionLedger владеет проверками допуска
 * и общей пакетной записью резервирования; вспомогательные компоненты
 * заимствуют ресурсы и не образуют отдельный планировщик.</p>
 *
 * @author GPT-6.1 Sol
 * @Reviewer Stephan Kolesnikov
 */
public class RocksDBService extends AbstractFrontierService {

    private static final org.slf4j.Logger LOG = LoggerFactory.getLogger(RocksDBService.class);


    static {
        RocksDB.loadLibrary();
    }

    /** Открытая база; принадлежит службе и закрывается после остановки Admission. */
    private RocksDB rocksDB;
    /** Восстановление и сохранение очередей; нативные дескрипторы остаются у службы. */
    private final QueueRecovery queueRecovery;
    /** Ключи и чтение расписания; не владеет нативными ресурсами или блокировками. */
    private final UrlIndex urlIndex;
    /** Общий монитор Admission, записи статусов и закрытия; берётся до монитора URL. */
    final Object admissionLock = new Object();
    /** Запрещает новые операции Admission до ожидания унаследованных пулов. */
    private volatile boolean admissionStopping;
    /** Флаг завершённого освобождения ресурсов; доступ только под admissionLock. */
    private boolean admissionClosed; // Доступ только под admissionLock.
    /** Проверки допуска и постоянное состояние; все вызовы защищены admissionLock. */
    AdmissionLedger admission;

    // Дескрипторы семейств столбцов в порядке открытия:
    // default, queues, queueInfos, onionallAdmissionV1.
    private final List<ColumnFamilyHandle> columnFamilyHandleList = new ArrayList<>();

    /** Необязательная нативная статистика; открывается и закрывается вместе со службой. */
    private Statistics statistics;

    // Использует конфигурацию по умолчанию.
    public RocksDBService(String host, int port) {
        this(new HashMap<String, String>(), host, port);
    }

    /** Временные отметки удаления; запись URL проверяет их перед изменением очереди. */
    private final ConcurrentHashMap<QueueWithinCrawl, QueueWithinCrawl> queuesBeingDeleted =
            new ConcurrentHashMap<>();

    /**
     * <b>Открывает базу и восстанавливает очереди до публикации службы.</b>
     *
     * <p>Сохраняет порядок семейств столбцов и запрет неявной миграции.
     * Вспомогательные компоненты получают уже открытые дескрипторы.</p>
     *
     * @param configuration параметры хранилища, Admission и унаследованных пулов
     * @param host адрес службы в существующем контракте Frontier
     * @param port порт службы
     * @throws IllegalArgumentException при запрещённой очистке или миграции
     */
    public RocksDBService(final Map<String, String> configuration, String host, int port) {

        // Настройка унаследованных пулов чтения и записи остаётся в базовом классе.
        super(configuration, host, port);

        // Каталог существующей либо создаваемой базы.
        String path = configuration.getOrDefault("rocksdb.path", "./rocksdb");

        LOG.info("RocksDB data stored in {} ", path);

        if (configuration.containsKey("rocksdb.purge")) {
            throw new IllegalArgumentException("Purge is disabled on the durable admission candidate");
        }

        if (configuration.containsKey("rocksdb.stats")) {
            statistics = new Statistics();
            statistics.setStatsLevel(StatsLevel.ALL);
        }

        boolean checkOnRecovery = configuration.containsKey("rocksdb.recovery.check");

        boolean bloomFilters = configuration.containsKey("rocksdb.bloom.filters");

        try (final ColumnFamilyOptions cfOpts = new ColumnFamilyOptions()) {

            String sMaxBytesForLevelBase = configuration.get("rocksdb.max_bytes_for_level_base");
            if (sMaxBytesForLevelBase != null) {
                cfOpts.setMaxBytesForLevelBase(Long.parseLong(sMaxBytesForLevelBase));
            }

            cfOpts.optimizeUniversalStyleCompaction();

            // Порядок семейств определяет соответствие дескрипторов; первым всегда
            // должно оставаться семейство default.
            final List<ColumnFamilyDescriptor> cfDescriptors =
                    Arrays.asList(
                            new ColumnFamilyDescriptor(RocksDB.DEFAULT_COLUMN_FAMILY, cfOpts),
                            new ColumnFamilyDescriptor("queues".getBytes(), cfOpts),
                            new ColumnFamilyDescriptor("queueInfos".getBytes(), cfOpts),
                            new ColumnFamilyDescriptor("onionallAdmissionV1".getBytes(StandardCharsets.UTF_8), cfOpts));

            if (java.nio.file.Files.exists(java.nio.file.Path.of(path, "CURRENT"))) {
                try (org.rocksdb.Options inspectOptions = new org.rocksdb.Options()) {
                    boolean admissionSchema = RocksDB.listColumnFamilies(inspectOptions, path).stream()
                        .anyMatch(bytes -> Arrays.equals(bytes,"onionallAdmissionV1".getBytes(StandardCharsets.UTF_8)));
                    if (!admissionSchema && !"copy-only".equals(configuration.get("admission.migrate")))
                        throw new IllegalArgumentException("Admission schema migration requires explicit copy-only opt-in on a fresh isolated copy; stock2.4 rollback is incompatible");
                } catch (RocksDBException e) { throw new IllegalStateException("Cannot inspect schema",e); }
            }
            long start = System.currentTimeMillis();

            if (bloomFilters) {
                LOG.info("Configuring Bloom filters");
                cfOpts.setTableFormatConfig(
                        new BlockBasedTableConfig().setFilterPolicy(new BloomFilter(10, false)));
            }

            try (final DBOptions options = new DBOptions()) {
                options.setCreateIfMissing(true).setCreateMissingColumnFamilies(true);

                String smaxBackgroundJobs = configuration.get("rocksdb.max_background_jobs");
                if (smaxBackgroundJobs != null) {
                    options.setMaxBackgroundJobs(Integer.parseInt(smaxBackgroundJobs));
                }

                // Значение Options.max_subcompactions по умолчанию — 1.
                String smax_subcompactions = configuration.get("rocksdb.max_subcompactions");
                if (smax_subcompactions != null) {
                    options.setMaxSubcompactions(Integer.parseInt(smax_subcompactions));
                }

                if (statistics != null) {
                    LOG.info("Allowing stats from RocksDB to be displayed when GetStats is called");
                    options.setStatistics(statistics);
                }

                rocksDB = RocksDB.open(options, path, cfDescriptors, columnFamilyHandleList);
            } catch (RocksDBException e) {
                LOG.error("RocksDB exception ", e);
                throw new RuntimeException(e);
            }

            long end = System.currentTimeMillis();

            LOG.info("RocksDB loaded in {} msec", end - start);

            // TODO(INIT-001): При исключении после RocksDB.open освободить уже открытые
            // DB/CF/Statistics и унаследованные пулы; проверить частичную инициализацию
            // на изолированной базе, не меняя текущий порядок штатного закрытия.
            urlIndex = new UrlIndex(rocksDB, columnFamilyHandleList.get(0), columnFamilyHandleList.get(1));
            queueRecovery = new QueueRecovery(rocksDB, columnFamilyHandleList.get(0),
                columnFamilyHandleList.get(1), columnFamilyHandleList.get(2), getQueues());
            admission = new AdmissionLedger(this, rocksDB, columnFamilyHandleList.get(3));
            admission.readOnly = "true".equals(configuration.get("admission.readonly"));
            // Всегда восстанавливаем индексы кандидата из постоянных строк, в том числе после аварии.
            checkOnRecovery = true;
            // Полная проверка соответствия указателей и запланированных записей.
            if (checkOnRecovery) {
                LOG.info("Scanning tables to rebuild queues... (can take a long time)");
                queueRecovery.scan(true);
            } else {
                queueRecovery.recover();
            }

            long end2 = System.currentTimeMillis();

            LOG.info("{} queues discovered in {} msec", getQueues().size(), (end2 - end));
        }
    }

    @Override
    protected int sendURLsForQueue(
            QueueInterface queue,
            QueueWithinCrawl queueID,
            int maxURLsPerQueue,
            int secsUntilRequestable,
            long now,
            SynchronizedStreamObserver<URLInfo> responseObserver) {

        // После начала остановки новые URL не выдаются.
        if (isClosing()) {
            return 0;
        }

        int alreadySent = 0;
        final byte[] prefixKey = (queueID.toString() + "_").getBytes(StandardCharsets.UTF_8);
        // Обход таблицы запланированных URL.
        try (final RocksIterator rocksIterator =
                rocksDB.newIterator(columnFamilyHandleList.get(1))) {
            for (rocksIterator.seek(prefixKey);
                    rocksIterator.isValid() && alreadySent < maxURLsPerQueue;
                    rocksIterator.next()) {

                final String currentKey = new String(rocksIterator.key(), StandardCharsets.UTF_8);

                // Разбираем только служебные разделители: URL может содержать подчёркивания.
                final int pos = currentKey.indexOf('_');
                final int pos2 = currentKey.indexOf('_', pos + 1);
                final int pos3 = currentKey.indexOf('_', pos2 + 1);

                final String crawlPart = currentKey.substring(0, pos);
                final String queuePart = currentKey.substring(pos + 1, pos2);
                final String urlPart = currentKey.substring(pos3 + 1);

                // Выход из непрерывного диапазона текущей очереди.
                if (!queueID.equals(crawlPart, queuePart)) {
                    return alreadySent;
                }

                // Будущая дата ещё не допускает выдачу URL.
                long scheduled = Long.parseLong(currentKey.substring(pos2 + 1, pos3));
                if (scheduled > now) {
                    // Последующие записи упорядочены по дате; продолжать обход не требуется.
                    return alreadySent;
                }

                // Действующее резервирование исключает повторную выдачу URL.
                if (((QueueMetadata) queue).isHeld(urlPart, now)) {
                    continue;
                }

                // URL доступен для выдачи в рамках текущего запроса.
                try {
                    // Первая выдача из очереди требует свободного места в лимите очередей ответа.
                    if (alreadySent == 0 && !responseObserver.tryTakingToken()) {
                        return 0;
                    }

                    responseObserver.onNext(URLInfo.parseFrom(rocksIterator.value()));

                    // Временное резервирование запрещает следующую выдачу до указанного срока.
                    ((QueueMetadata) queue).holdUntil(urlPart, now + secsUntilRequestable);

                    alreadySent++;
                } catch (Throwable e) {
                    LOG.error("Caught unlikely error ", e);
                }
            }
        }

        return alreadySent;
    }

    @Override
    protected Status putURLItem(final URLItem value) {
        if (admissionStopping) return Status.FAIL;
        synchronized (admissionLock) {
            if (admissionStopping) return Status.FAIL;
            if (!admission.permitsLegacy(value)) return Status.FAIL;
            return putLegacyURLItem(value);
        }
    }

    private Status putLegacyURLItem(final URLItem value) {

        if (isClosing()) {
            return Status.FAIL;
        }

        long nextFetchDate;
        boolean discovered = true;
        URLInfo info;

        putURLs_urls_count.inc();

        if (value.hasDiscovered()) {
            putURLs_discovered_count.labels("true").inc();
            info = value.getDiscovered().getInfo();
            nextFetchDate = Instant.now().getEpochSecond();
        } else {
            putURLs_discovered_count.labels("false").inc();
            KnownURLItem known = value.getKnown();
            info = known.getInfo();
            nextFetchDate = known.getRefetchableFromDate();
            discovered = Boolean.FALSE;
        }

        String Qkey = info.getKey();
        final String url = info.getUrl();
        final String crawlID = CrawlID.normaliseCrawlID(info.getCrawlID());

        // Если ключ очереди не задан, используем имя хоста URL.
        if (Qkey.equals("")) {
            LOG.debug("key missing for {}", url);
            Qkey = provideMissingKey(url);
            if (Qkey == null) {
                LOG.error("Malformed URL {}", url);
                return Status.SKIPPED;
            }
            // Сохраняем вычисленный ключ и нормализованный идентификатор обхода в URLInfo.
            info = URLInfo.newBuilder(info).setKey(Qkey).setCrawlID(crawlID).build();
        }

        LOG.debug("putURLItem -> {} with key {} in crawl {}", url, Qkey, crawlID);

        // Ограничение длины ключа является частью существующего контракта.
        if (Qkey.length() > 255) {
            LOG.error("Key too long: {}", Qkey);
            return Status.SKIPPED;
        }

        final QueueWithinCrawl qk = QueueWithinCrawl.get(Qkey, crawlID);

        // Начатое удаление очереди запрещает добавление в неё URL.
        if (queuesBeingDeleted.containsKey(qk)) {
            LOG.info("Not adding {} as its queue {} is being deleted", url, Qkey);
            return Status.SKIPPED;
        }

        // Интернирование сохраняет один монитор для одинакового ключа URL.
        // Все потоки синхронизируются на том же экземпляре String, чтобы
        // одновременные операции не считали один и тот же URL новым.
        // Этот монитор берётся после admissionLock; порядок блокировок сохраняется.
        final String existenceKeyString = (qk.toString() + "_" + url).intern();
        final byte[] existenceKey = existenceKeyString.getBytes(StandardCharsets.UTF_8);

        synchronized (existenceKeyString) {

            // Проверяем существующий указатель внутри границы пакетной записи.
            try (WriteBatch writeBatch = new WriteBatch();
                    WriteOptions writeOps = new WriteOptions()) {

                if (isClosing()) {
                    return Status.FAIL;
                }
                byte[] schedulingKey = rocksDB.get(existenceKey);
                final byte[] oldSchedulingKey = schedulingKey;

                // Повторное обнаружение известного URL не меняет расписание.
                if (schedulingKey != null && discovered) {
                    putURLs_alreadyknown_count.inc();
                    return Status.SKIPPED;
                }

                // Метаданные очереди публикуются после успешной пакетной записи.


                // Обновление известного URL удаляет прежнюю запись расписания.
                // Её ключ хранится значением в семействе default.
                if (schedulingKey != null) {
                    if (isClosing()) {
                        return Status.FAIL;
                    }
                    writeBatch.delete(columnFamilyHandleList.get(1), schedulingKey);
                    // Счётчики изменяются только после успешного rocksDB.write.

                }

                // Обновление с nextFetchDate == 0 означает отсутствие новой даты выдачи.
                // Для остальных записей сохраняется прежний формат ключа расписания.
                if (!discovered && nextFetchDate == 0) {
                    // URL больше не запланирован; пустой указатель сохраняет факт существования.
                    // Пустое значение не является доказательством успешного завершения.
                    schedulingKey = new byte[] {};

                } else {
                    // Новый либо повторно запланированный URL получает ключ с датой.
                    // Кодирование ключа делегировано без изменения постоянного формата.
                    schedulingKey =
                            urlIndex.schedulingKey(qk, nextFetchDate, url);
                    // Новая запись расписания входит в тот же WriteBatch, что и указатель.
                    if (isClosing()) {
                        return Status.FAIL;
                    }
                    writeBatch.put(
                            columnFamilyHandleList.get(1), schedulingKey, info.toByteArray());

                }

                if (isClosing()) {
                    return Status.FAIL;
                }

                // Указатель URL обновляется в том же пакете.
                writeBatch.put(columnFamilyHandleList.get(0), existenceKey, schedulingKey);

                // Общий WriteBatch сохраняет атомарное соответствие таблицы расписания
                // и указателя URL в семействе default.
                rocksDB.write(writeOps, writeBatch);
                QueueMetadata queueMD=(QueueMetadata)getQueues().computeIfAbsent(qk, k -> new QueueMetadata());
                if (oldSchedulingKey != null && oldSchedulingKey.length != 0) {
                    queueMD.removeFromProcessed(url); queueMD.decrementActive();
                } else if (oldSchedulingKey != null) queueMD.decrementCompleted();
                if (!discovered && nextFetchDate == 0) { queueMD.incrementCompleted(); putURLs_completed_count.inc(); }
                else queueMD.incrementActive();

            } catch (RocksDBException e) {
                LOG.error("RocksDB exception", e);
                return Status.FAIL;
            }
        }

        return Status.OK;
    }

    /**
     * Добавляет URL и его расписание в пакет Admission без самостоятельного commit.
     *
     * <p>Вызывающий AdmissionLedger удерживает admissionLock и включает в тот же
     * WriteBatch резервирование и квоту. Счётчики публикуются только после commit.</p>
     *
     * @param info разрешённый URL с неизменяемой областью обхода
     * @param batch пакет, принадлежащий вызывающей операции Admission
     * @throws RocksDBException при ошибке чтения или подготовки пакетной записи
     */
    void stageAdmission(URLInfo info, WriteBatch batch) throws RocksDBException {
        QueueWithinCrawl q = QueueWithinCrawl.get(info.getKey(), info.getCrawlID());
        byte[] existence = (q.toString()+"_"+info.getUrl()).getBytes(StandardCharsets.UTF_8);
        if (rocksDB.get(existence) != null) throw new IllegalStateException("Legacy URL already exists");
        byte[] scheduling = urlIndex.schedulingKey(q, Instant.now().getEpochSecond(), info.getUrl());
        batch.put(columnFamilyHandleList.get(0), existence, scheduling);
        batch.put(columnFamilyHandleList.get(1), scheduling, info.toByteArray());
    }
    /** Публикует счётчик после успешного commit Admission под admissionLock. */
    void publishAdmission(URLInfo info) {
        QueueWithinCrawl q=QueueWithinCrawl.get(info.getKey(),info.getCrawlID());
        ((QueueMetadata)getQueues().computeIfAbsent(q,k->new QueueMetadata())).incrementActive();
    }
    boolean crawlExists(String id) {
        return getQueues().keySet().stream().anyMatch(q->q.getCrawlid().equals(CrawlID.normaliseCrawlID(id)));
    }
    public com.google.gson.JsonObject admissionCommand(com.google.gson.JsonObject command) {
        if (admissionStopping) throw new IllegalStateException("Admission service is stopping");
        synchronized(admissionLock) {
            if (admissionStopping) throw new IllegalStateException("Admission service is stopping");
            return admission.command(command);
        }
    }

    /**
     * Читает существующее расписание без выдачи либо изменения резервирования.
     *
     * <p>Вызывающий удерживает admissionLock и монитор очереди; UrlIndex не
     * приобретает новые блокировки и не выводит успешность из UNSCHEDULED.</p>
     *
     * @param id идентификатор сессии Admission
     * @param url разрешённый URL сессии
     * @param now время наблюдения в секундах эпохи
     * @param held снимок действующих временных резервирований
     * @return наблюдаемое состояние и известные даты
     * @throws RocksDBException при ошибке чтения базы
     */
    com.google.gson.JsonObject schedulingObservation(String id, String url, long now,
                                                     java.util.Map<String, Long> held) throws RocksDBException {
        return urlIndex.observe(id, url, now, held);
    }

    /**
     * Удаляет указанную очередь через существующий локальный алгоритм.
     *
     * <p>RPC-проверка полномочий остаётся в AdmissionRpc; данный метод
     * сохраняется для совместимости с базовым контрактом Frontier.</p>
     *
     * @param request ключ очереди и идентификатор обхода
     * @param responseObserver получатель числа удалённых URL
     */
    @Override
    public void deleteQueue(
            crawlercommons.urlfrontier.Urlfrontier.QueueWithinCrawlParams request,
            StreamObserver<crawlercommons.urlfrontier.Urlfrontier.Long> responseObserver) {
        final QueueWithinCrawl qc = QueueWithinCrawl.get(request.getKey(), request.getCrawlID());
        int sizeQueue = deleteLocalQueue(qc);
        responseObserver.onNext(
                crawlercommons.urlfrontier.Urlfrontier.Long.newBuilder()
                        .setValue(sizeQueue)
                        .build());
        responseObserver.onCompleted();
    }

    /**
     * Удаляет диапазон очереди и её метаданные, сохраняя прежние границы блокировок.
     *
     * @param qc идентификатор очереди
     * @return прежняя сумма активных и незапланированных URL
     */
    protected int deleteLocalQueue(QueueWithinCrawl qc) {
        int sizeQueue = 0;

        if (isClosing()) {
            return 0;
        }

        // Отсутствующая очередь не требует удаления.
        if (!getQueues().containsKey(qc)) {
            return sizeQueue;
        }

        // Проверяем существующую отметку удаления очереди.
        // Повторный вызов не должен повторно учитывать её размер.
        if (queuesBeingDeleted.contains(qc)) {
            return sizeQueue;
        }

        // TODO(DELETE-001): Проверка отметки и её установка неатомарны; конкурентное
        // удаление может обнулить getQueues().remove(qc). Нужен отдельный тест
        // удаления/выдачи с повторным открытием базы до изменения синхронизации.
        queuesBeingDeleted.put(qc, qc);

        String prefixed_queue = qc.toString() + "_";

        // Верхняя граница определяется следующей очередью с учётом разделителя.
        QueueWithinCrawl[] array = getQueues().keySet().toArray(new QueueWithinCrawl[0]);
        String[] prefixed_queues = new String[array.length];
        for (int i = 0; i < array.length; i++) {
            prefixed_queues[i] = array[i] + "_";
        }
        Arrays.sort(prefixed_queues);
        byte[] startKey = null;
        byte[] endKey = null;
        for (String p_queue : prefixed_queues) {
            if (startKey != null) {
                endKey = p_queue.getBytes(StandardCharsets.UTF_8);
                break;
            } else if (prefixed_queue.equals(p_queue)) {
                startKey = prefixed_queue.getBytes(StandardCharsets.UTF_8);
            }
        }

        try {
            deleteRanges(startKey, endKey);
        } catch (RocksDBException e) {
            LOG.error(
                    "Exception caught when deleting ranges - {} - {}",
                    new String(startKey),
                    new String(endKey),
                    e);
        }

        QueueInterface q = getQueues().remove(qc);
        sizeQueue += q.countActive();
        sizeQueue += q.getCountCompleted();

        queuesBeingDeleted.remove(qc);
        return sizeQueue;
    }

    @Override
    public void getStats(
            crawlercommons.urlfrontier.Urlfrontier.QueueWithinCrawlParams request,
            StreamObserver<Stats> responseObserver) {
        if (statistics != null) {
            LOG.info("RockdSB stats: {}", statistics);
        }
        super.getStats(request, responseObserver);
    }

    /**
     * Запрещает новые Admission-операции и освобождает принадлежащие службе ресурсы.
     *
     * <p>Сначала останавливает унаследованные пулы без admissionLock, затем под
     * этим монитором сохраняет счётчики и закрывает дескрипторы, статистику и базу.
     * Вспомогательные компоненты не выполняют самостоятельное закрытие.</p>
     *
     * @throws IOException согласно контракту базовой службы
     */
    @Override
    public void close() throws IOException {

        LOG.info("Closing RocksDB");
        // Прямые вызовы Admission не выполняются в унаследованных пулах.
        // Запрещаем новые вызовы до ожидания пулов, не удерживая admissionLock.
        admissionStopping = true;
        super.close();
        synchronized (admissionLock) {
            if (admissionClosed) return;
            // Ранее допущенные операции Admission завершили запись и публикацию
            // до этой точки; последующие Admission-команды к дескрипторам не обращаются.

            // Сохраняем вспомогательные счётчики перед освобождением дескрипторов.
            try {
                queueRecovery.writeQueueInfos();
            } catch (Exception e) {
                LOG.error("writeQueueInfos ", e);
                rocksDB.destroyColumnFamilyHandle(columnFamilyHandleList.get(2));
            }

            for (final ColumnFamilyHandle columnFamilyHandle : columnFamilyHandleList) {
                columnFamilyHandle.close();
            }

            if (statistics != null) {
                statistics.close();
            }

            if (rocksDB != null) {
                try {
                    rocksDB.syncWal();
                    rocksDB.closeE();
                } catch (Exception e) {
                    LOG.error("Closing ", e);
                }
            }
            admissionClosed = true;
        }
    }

    @Override
    public void deleteCrawl(
            crawlercommons.urlfrontier.Urlfrontier.DeleteCrawlMessage crawlID,
            io.grpc.stub.StreamObserver<crawlercommons.urlfrontier.Urlfrontier.Long>
                    responseObserver) {
        long total = deleteLocalCrawl(crawlID.getValue());
        responseObserver.onNext(
                crawlercommons.urlfrontier.Urlfrontier.Long.newBuilder().setValue(total).build());
        responseObserver.onCompleted();
    }

    protected long deleteLocalCrawl(String crawlID) {
        long total = 0;

        if (isClosing()) {
            return 0;
        }

        final String normalisedCrawlID = CrawlID.normaliseCrawlID(crawlID);

        final Set<QueueWithinCrawl> toDelete = new HashSet<>();

        synchronized (getQueues()) {

            // Выбираем непрерывный диапазон очередей заданного обхода.
            QueueWithinCrawl[] array = getQueues().keySet().toArray(new QueueWithinCrawl[0]);
            Arrays.sort(array);

            byte[] startKey = null;
            byte[] endKey = null;
            for (QueueWithinCrawl prefixed_queue : array) {
                boolean samePrefix = prefixed_queue.getCrawlid().equals(normalisedCrawlID);
                if (samePrefix) {
                    if (startKey == null) {
                        startKey =
                                (prefixed_queue.getCrawlid().replaceAll("_", "%5F") + "_")
                                        .getBytes(StandardCharsets.UTF_8);
                    }
                    toDelete.add(prefixed_queue);
                } else if (startKey != null) {
                    endKey =
                            (prefixed_queue.getCrawlid().replaceAll("_", "%5F") + "_")
                                    .getBytes(StandardCharsets.UTF_8);
                    break;
                }
            }

            // Для отсутствующего обхода постоянное хранилище не изменяется.
            if (startKey == null) {
                return 0;
            }

            try {
                deleteRanges(startKey, endKey);
            } catch (RocksDBException e) {
                LOG.error(
                        "Exception caught when deleting ranges - {} - {}",
                        new String(startKey),
                        new String(endKey));
            }

            for (QueueWithinCrawl quid : toDelete) {
                if (queuesBeingDeleted.contains(quid)) {
                    continue;
                } else {
                    queuesBeingDeleted.put(quid, quid);
                }

                QueueInterface q = getQueues().remove(quid);
                total += q.countActive();
                total += q.getCountCompleted();

                queuesBeingDeleted.remove(quid);
            }
        }
        return total;
    }

    private void deleteRanges(final byte[] prefix, byte[] endKey) throws RocksDBException {

        if (isClosing()) {
            return;
        }

        // Отсутствие endKey означает, что за этим обходом нет следующего диапазона.
        boolean includeEndKey = false;

        if (endKey == null) {
            try (RocksIterator iter = rocksDB.newIterator(columnFamilyHandleList.get(0))) {
                iter.seekToLast();
                if (iter.isValid()) {
                    // Последний ключ URL определяет включительную границу хвостового диапазона.
                    endKey = iter.key();
                    includeEndKey = true;
                }
            }
        }

        // Без конечного ключа границу диапазона определить не удалось.
        if (endKey == null) {
            throw new RuntimeException("No endkey found");
        }

        // Удаляем записи расписания и указатели URL, включая уже не запланированные.
        // Текущий алгоритм и границы удаления сохраняются.
        // TODO(DELETE-002): Эти удаления разных CF не входят в один WriteBatch.
        // Проверить отказ между операциями и согласованность последующего
        // восстановления; не менять атомарность в структурном рефакторинге.
        rocksDB.deleteRange(columnFamilyHandleList.get(1), prefix, endKey);
        rocksDB.deleteRange(columnFamilyHandleList.get(0), prefix, endKey);

        if (includeEndKey) {
            rocksDB.deleteRange(columnFamilyHandleList.get(1), endKey, endKey);
            rocksDB.delete(columnFamilyHandleList.get(0), endKey);
        }
    }

    @Override
    public void getURLStatus(URLStatusRequest request, StreamObserver<URLItem> responseObserver) {

        String crawlId = request.getCrawlID();
        String key = request.getKey();
        String url = request.getUrl();

        // Если ключ очереди не задан, используем имя хоста URL.
        if (key == null || key.equals("")) {
            LOG.debug("key missing for {}", url);
            key = provideMissingKey(url);
            if (key == null) {
                LOG.error("Malformed URL {}", url);
                responseObserver.onError(io.grpc.Status.INVALID_ARGUMENT.asRuntimeException());
                return;
            }
        }

        final QueueWithinCrawl qk = QueueWithinCrawl.get(key, crawlId);
        final String existenceKeyString = (qk.toString() + "_" + url).intern();
        final byte[] existenceKey = existenceKeyString.getBytes(StandardCharsets.UTF_8);

        byte[] schedulingKey = null;

        boolean found = false;

        URLItem.Builder builder = URLItem.newBuilder();
        KnownURLItem.Builder knownbuilder = KnownURLItem.newBuilder();
        URLInfo info = null;
        long fromEpoch = 0;

        try {
            schedulingKey = rocksDB.get(columnFamilyHandleList.get(0), existenceKey);
            if (schedulingKey != null) {
                final String currentKey = new String(schedulingKey, StandardCharsets.UTF_8);

                if (StringUtil.isNullOrEmpty(currentKey)) {
                    info = URLInfo.newBuilder().setCrawlID(crawlId).setKey(key).setUrl(url).build();

                    found = true;
                } else {
                    final int pos = currentKey.indexOf('_');
                    final int pos2 = currentKey.indexOf('_', pos + 1);
                    final int pos3 = currentKey.indexOf('_', pos2 + 1);

                    fromEpoch = Long.parseLong(currentKey.substring(pos2 + 1, pos3));

                    try {
                        info =
                                URLInfo.parseFrom(
                                        rocksDB.get(columnFamilyHandleList.get(1), schedulingKey));
                    } catch (InvalidProtocolBufferException e) {
                        LOG.error(e.getMessage(), e);
                        // TODO(STATUS-001): После onError выполнение продолжается до
                        // found=true/onNext с info=null. Отдельно проверить повреждённый
                        // protobuf и единственное терминальное событие RPC, затем добавить return.
                        responseObserver.onError(
                                io.grpc.Status.fromThrowable(e).asRuntimeException());
                    }

                    found = true;
                }
            } else {
                // Указатель URL отсутствует в постоянном хранилище.
                found = false;
            }

        } catch (RocksDBException e) {
            LOG.error("Caught unlikely error ", e);
            responseObserver.onError(io.grpc.Status.fromThrowable(e).asRuntimeException());
            return;
        }

        if (found) {
            responseObserver.onNext(buildURLItem(builder, knownbuilder, info, fromEpoch));
            responseObserver.onCompleted();
        } else {
            responseObserver.onError(io.grpc.Status.NOT_FOUND.asRuntimeException());
        }
    }

    public Iterator<URLItem> urlIterator(
            Entry<QueueWithinCrawl, QueueInterface> qentry, long start, long maxURLs) {
        return new RocksDBURLItemIterator(qentry, start, maxURLs);
    }

    /**
     * <b>Последовательный просмотр URL одной очереди в существующем формате.</b>
     *
     * <p>Заимствует базу службы; позиционирование и порядок записей сохраняются.
     * Публичный контракт Iterator не предоставляет операции закрытия.</p>
     */
    class RocksDBURLItemIterator implements Iterator<URLItem> {

        private final org.slf4j.Logger LOG = LoggerFactory.getLogger(RocksDBURLItemIterator.class);

        private final long maxURLs;
        private long pos = 0;
        private long sent = 0;
        private URLItem.Builder builder = URLItem.newBuilder();
        private KnownURLItem.Builder knownBuilder = KnownURLItem.newBuilder();

        private final QueueWithinCrawl queueID;
        private final byte[] prefixKey;
        // TODO(ITERATOR-001): RocksIterator создаётся здесь, но не закрывается.
        // listURLs может прервать обход по лимиту до исчерпания; закрытия только
        // в hasNext недостаточно. Согласовать явного владельца без изменения RPC API.
        private final RocksIterator rocksIterator;
        private boolean hasNext = true;

        public RocksDBURLItemIterator(
                Entry<QueueWithinCrawl, QueueInterface> qentry, long start, long maxURLs) {

            this.queueID = qentry.getKey();
            this.prefixKey = (queueID.toString() + "_").getBytes(StandardCharsets.UTF_8);
            this.maxURLs = maxURLs;
            this.builder = URLItem.newBuilder();
            this.knownBuilder = KnownURLItem.newBuilder();

            this.rocksIterator = rocksDB.newIterator(columnFamilyHandleList.get(0));
            this.rocksIterator.seek(prefixKey);

            if (rocksIterator.isValid() && start == 0L) {
                // Проверяем, что позиционирование осталось в диапазоне нужной очереди.
                final String currentKey = new String(rocksIterator.key(), StandardCharsets.UTF_8);
                final QueueWithinCrawl Qkey = QueueWithinCrawl.parseAndDeNormalise(currentKey);

                if (!queueID.equals(Qkey.getCrawlid(), Qkey.getQueue())) {
                    hasNext = false;
                    return;
                }
            } else {
                // Пропускаем start записей, не выходя за диапазон очереди.
                while (rocksIterator.isValid() && pos < start) {
                    final String currentKey =
                            new String(rocksIterator.key(), StandardCharsets.UTF_8);
                    final QueueWithinCrawl Qkey = QueueWithinCrawl.parseAndDeNormalise(currentKey);

                    if (!queueID.equals(Qkey.getCrawlid(), Qkey.getQueue())) {
                        hasNext = false;
                        break;
                    }
                    rocksIterator.next();
                    pos++;
                }
            }
        }

        @Override
        public boolean hasNext() {
            return rocksIterator.isValid() && hasNext && sent < maxURLs;
        }

        @Override
        public void remove() {
            throw new UnsupportedOperationException();
        }

        @Override
        public URLItem next() {

            if (!hasNext()) {
                throw new NoSuchElementException();
            }

            while (rocksIterator.isValid() && sent < maxURLs) {
                String currentKey = new String(rocksIterator.key(), StandardCharsets.UTF_8);
                QueueWithinCrawl Qkey = QueueWithinCrawl.parseAndDeNormalise(currentKey);

                if (!queueID.equals(Qkey.getCrawlid(), Qkey.getQueue())) {
                    hasNext = false;
                    break;
                }

                final String schedulingKey =
                        new String(rocksIterator.value(), StandardCharsets.UTF_8);

                LOG.debug("current key {}, schedulingKey={}", currentKey, schedulingKey);

                byte[] scheduled = null;
                try {
                    scheduled = rocksDB.get(columnFamilyHandleList.get(1), rocksIterator.value());
                } catch (RocksDBException e) {
                    LOG.error(e.getMessage(), e);
                }

                long fromEpoch = 0;
                URLInfo info = null;

                if (!StringUtil.isNullOrEmpty(schedulingKey)) {
                    final int pos1 = schedulingKey.indexOf('_');
                    final int pos2 = schedulingKey.indexOf('_', pos1 + 1);
                    final int pos3 = schedulingKey.indexOf('_', pos2 + 1);

                    fromEpoch = Long.parseLong(schedulingKey.substring(pos2 + 1, pos3));

                    try {
                        info = URLInfo.parseFrom(scheduled);
                    } catch (InvalidProtocolBufferException | NumberFormatException e) {
                        LOG.error(e.getMessage(), e);
                    }
                } else {
                    LOG.debug("no schedule for {}", currentKey);

                    final int pos1 = currentKey.indexOf('_');
                    final int pos2 = currentKey.indexOf('_', pos1 + 1);

                    info =
                            URLInfo.newBuilder()
                                    .setCrawlID(Qkey.getCrawlid())
                                    .setKey(Qkey.getQueue())
                                    .setUrl(currentKey.substring(pos2 + 1))
                                    .build();
                }

                sent++;
                rocksIterator.next();

                // TODO(ITERATOR-002): После next() сначала требуется проверить isValid().
                // Сейчас key() читается и после последней записи; воспроизвести EOF
                // на изолированной базе и сохранить порядок обхода при отдельном исправлении.
                currentKey = new String(rocksIterator.key(), StandardCharsets.UTF_8);
                Qkey = QueueWithinCrawl.parseAndDeNormalise(currentKey);

                if (!queueID.equals(Qkey.getCrawlid(), Qkey.getQueue())) {
                    hasNext = false;
                } else {
                    hasNext = rocksIterator.isValid();
                }

                return buildURLItem(builder, knownBuilder, info, fromEpoch);
            }

            return null; // Недостижимость этой ветки зависит от сохранённых проверок итератора.
        }
    }
}
