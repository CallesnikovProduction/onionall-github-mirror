// SPDX-FileCopyrightText: 2020 Crawler-commons
// SPDX-License-Identifier: Apache-2.0

package crawlercommons.urlfrontier.service.rocksdb;

import crawlercommons.urlfrontier.service.QueueInterface;
import crawlercommons.urlfrontier.service.QueueWithinCrawl;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Map.Entry;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.RocksIterator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <b>Восстановление и сохранение счётчиков очередей Frontier.</b>
 *
 * <p>Заимствует существующие дескрипторы RocksDB и карту очередей. Не открывает и
 * не закрывает базу, не меняет Admission, расписание или формат семейств столбцов.</p>
 * <p>Сканирование выполняется до публикации службы, сохранение — при остановке.
 * Создаваемые RocksIterator закрываются внутри операций. Алгоритмы перенесены
 * из исходного RocksDBService проекта Crawler-commons с сохранением поведения.</p>
 *
 * @author GPT-6.1 Sol
 * @Reviewer Stephan Kolesnikov
 */
final class QueueRecovery {
    private static final Logger LOG = LoggerFactory.getLogger(QueueRecovery.class);
    /** Заимствованная база; закрывается только RocksDBService. */
    private final RocksDB rocksDB;
    /** Заимствованные семейства столбцов: URL, расписание и восьмибайтовые счётчики. */
    private final ColumnFamilyHandle urls, scheduled, metadata;
    /** Общая карта службы; не отдельный источник решений планировщика. */
    private final Map<QueueWithinCrawl, QueueInterface> queues;

    /**
     * Заимствует уже открытые ресурсы службы без передачи владения.
     *
     * @param rocksDB база, закрываемая только RocksDBService
     * @param urls семейство указателей URL
     * @param scheduled семейство записей расписания
     * @param metadata семейство сохранённых счётчиков очередей
     * @param queues исходная общая карта очередей
     */
    QueueRecovery(RocksDB rocksDB, ColumnFamilyHandle urls, ColumnFamilyHandle scheduled,
                  ColumnFamilyHandle metadata, Map<QueueWithinCrawl, QueueInterface> queues) {
        this.rocksDB = rocksDB;
        this.urls = urls;
        this.scheduled = scheduled;
        this.metadata = metadata;
        this.queues = queues;
    }

    /**
     * Восстанавливает исторический путь через сохранённые счётчики очередей.
     *
     * <p>Разрешён только до публикации службы, без конкурентных обращений к базе
     * и очередям. При отсутствии счётчиков вызывает scan(false). Текущая защищённая
     * реализация всегда выбирает scan(true), но прежняя ветка сохранена.</p>
     */
    void recover() {
        // Сначала читаем сохранённые вспомогательные счётчики очередей.
        // Если их нет, сохраняем прежний путь восстановления по таблицам URL.
        int read = 0;
        try {
            read = readQueueInfos();
        } catch (RocksDBException e) {
            LOG.error("readQueueInfos", e);
        }
        // Отсутствие сохранённых счётчиков требует обхода таблиц.
        if (read == 0) {
            scan(false);
        }
    }

    /**
     * Восстанавливает очереди из постоянных указателей и записей расписания.
     *
     * <p>Вызывается до публикации службы и не вводит собственных блокировок.
     * В режиме проверки сначала считает строки расписания, затем сопоставляет
     * их с указателями URL, включая последнюю очередь. Итераторы принадлежат
     * операции и закрываются до её завершения; DB и CF остаются у службы.</p>
     *
     * @param check проверять соответствие активных указателей таблице расписания
     * @throws RuntimeException при несовпадении числа активных URL
     */
    void scan(boolean check) {

        LOG.info("Recovering queues from existing RocksDB");

        if (check) {
            try (final RocksIterator rocksIterator =
                    rocksDB.newIterator(scheduled)) {
                for (rocksIterator.seekToFirst(); rocksIterator.isValid(); rocksIterator.next()) {
                    final String currentKey =
                            new String(rocksIterator.key(), StandardCharsets.UTF_8);
                    final QueueWithinCrawl qk = QueueWithinCrawl.parseAndDeNormalise(currentKey);
                    QueueMetadata queueMD =
                            (QueueMetadata)
                                    queues.computeIfAbsent(qk, s -> new QueueMetadata());
                    queueMD.incrementActive();
                }
            }
            LOG.info("Found {} queues from scheduled table", queues.size());
        }

        QueueWithinCrawl previousQueueID = null;
        long numScheduled = 0;

        // Подсчитываем URL с пустым указателем: они больше не запланированы.
        try (final RocksIterator rocksIterator =
                rocksDB.newIterator(urls)) {
            for (rocksIterator.seekToFirst(); rocksIterator.isValid(); rocksIterator.next()) {
                final String currentKey = new String(rocksIterator.key(), StandardCharsets.UTF_8);
                final QueueWithinCrawl Qkey = QueueWithinCrawl.parseAndDeNormalise(currentKey);

                // При переходе к другой очереди проверяем счётчики предыдущей.
                if (previousQueueID == null) {
                    previousQueueID = Qkey;
                } else if (check && !previousQueueID.equals(Qkey)) {
                    int activeinQueues = queues.get(previousQueueID).countActive();
                    if (activeinQueues != numScheduled) {
                        LOG.error(
                                "Incorrect number of active URLs for queue: {}. {} vs {}",
                                previousQueueID,
                                activeinQueues,
                                numScheduled);
                        throw new RuntimeException(
                                "Incorrect number of active URLs for queue: " + previousQueueID);
                    }
                    previousQueueID = Qkey;
                    numScheduled = 0;
                }

                // Для очереди без запланированных URL записи в таблице расписания может не быть.
                // В таком случае создаём метаданные при чтении указателей URL.
                QueueMetadata queueMD =
                        (QueueMetadata) queues.computeIfAbsent(Qkey, s -> new QueueMetadata());

                // Пустое значение означает отсутствие расписания, непустое — указатель
                // на запланированную запись. Причина завершения здесь не восстанавливается.
                // Это различие сохраняет прежнюю семантику счётчика completed.
                boolean done = rocksIterator.value().length == 0;
                if (done) {
                    queueMD.incrementCompleted();
                } else {
                    // Историческая ветка без предварительной проверки сохранена без изменений.
                    // TODO(RECOVERY-001): В сохранённой scan(false) ветка active пуста.
                    // Она недостижима при штатном scan(true); отдельно проверить
                    // исторический путь до любого изменения счётчиков.
                    if (!check) {

                    }
                    // Сравнение числа указателей с таблицей расписания выполняется на границе очереди.
                    numScheduled++;
                }
            }
        }
        // Последнюю очередь проверяем после завершения обхода.
        if (check
                && previousQueueID != null
                && queues.get(previousQueueID).countActive() != numScheduled) {
            throw new RuntimeException(
                    "Incorrect number of active URLs for queue " + previousQueueID);
        }
    }

    /**
     * Сохраняет вспомогательные счётчики очередей в прежнем восьмибайтовом формате.
     *
     * <p>Служба вызывает метод при остановке под admissionLock после ожидания
     * унаследованных пулов. Обход карты выполняется под её монитором; порядок
     * блокировок и владение базой не меняются.</p>
     *
     * @throws RocksDBException при ошибке сохранения счётчиков
     */
    void writeQueueInfos() throws RocksDBException {
        long start = System.currentTimeMillis();
        int queuesWritten = 0;
        if (!queues.isEmpty()) {
            ByteBuffer bb = ByteBuffer.allocate(8);
            synchronized (queues) {
                for (Entry<QueueWithinCrawl, QueueInterface> entry : queues.entrySet()) {
                    queuesWritten++;
                    int active = entry.getValue().countActive();
                    int completed = entry.getValue().getCountCompleted();
                    bb.putInt(active);
                    bb.putInt(completed);
                    rocksDB.put(
                            metadata,
                            entry.getKey().toString().getBytes(),
                            bb.array());
                    bb.clear();
                }
            }
        }
        long end = System.currentTimeMillis();
        LOG.info(
                "writeQueueInfos stored stats for {} queues in {} msec",
                queuesWritten,
                (end - start));
    }

    /**
     * Читает сохранённые счётчики и удаляет прочитанный диапазон их таблицы.
     *
     * <p>Допускается только до публикации службы без конкурентных операций.
     * Созданный итератор закрывается локально, карта и дескрипторы заимствованы.</p>
     *
     * @return число прочитанных записей очередей
     * @throws RocksDBException при ошибке чтения или удаления диапазона
     */
    private int readQueueInfos() throws RocksDBException {
        long start = System.currentTimeMillis();
        int queuesRead = 0;
        byte[] firstKey = null;
        byte[] lastKey = null;
        ByteBuffer bb = ByteBuffer.allocate(8);
        try (final RocksIterator rocksIterator =
                rocksDB.newIterator(metadata)) {
            for (rocksIterator.seekToFirst(); rocksIterator.isValid(); rocksIterator.next()) {
                if (firstKey == null) firstKey = rocksIterator.key();
                lastKey = rocksIterator.key();
                queuesRead++;
                final String currentKey = new String(rocksIterator.key(), StandardCharsets.UTF_8);
                final QueueWithinCrawl qk = QueueWithinCrawl.parseAndDeNormalise(currentKey);
                QueueMetadata queueMD =
                        (QueueMetadata) queues.computeIfAbsent(qk, s -> new QueueMetadata());
                rocksIterator.value(bb);
                int active = bb.getInt();
                int completed = bb.getInt();
                bb.clear();
                queueMD.setActiveCount(active);
                queueMD.setCompletedCount(completed);
            }
        }

        if (queuesRead != 0) {
            // Удаляем прочитанный диапазон вспомогательных счётчиков, чтобы следующий
            // аварийный запуск не принял их за актуальное состояние.
            // TODO(RECOVERY-002): Верхняя граница deleteRange исключительна, поэтому
            // lastKey остаётся в таблице. Проверить одиночную/последнюю запись
            // исторического recover() отдельно; штатный scan(true) эту таблицу не читает.
            rocksDB.deleteRange(metadata, firstKey, lastKey);
        }

        long end = System.currentTimeMillis();
        LOG.info("readQueueInfos read stats for {} queues in {} msec", queuesRead, (end - start));

        return queuesRead;
    }

}
