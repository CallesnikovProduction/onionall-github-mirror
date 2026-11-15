// SPDX-FileCopyrightText: 2020 Crawler-commons
// SPDX-License-Identifier: Apache-2.0

package crawlercommons.urlfrontier.service.rocksdb;

import crawlercommons.urlfrontier.service.QueueWithinCrawl;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;

/**
 * <b>Представление ключей URL и чтение указателей scheduling.</b>
 *
 * <p>Сохраняет прежний бинарный формат: UTF-8, нормализованная очередь,
 * разделитель и десятизначная дата. Не управляет Admission, квотами и leases.</p>
 * <p>База и handles заимствованы. Наблюдение выполняется под admissionLock и
 * monitor очереди, которые удерживает вызывающий AdmissionState; новых locks нет.</p>
 *
 * @author GPT-6.1 Sol
 * @Reviewer Stephan Kolesnikov
 */
final class UrlIndex {
    // TODO(IDX-001): DecimalFormat разделяется между экземплярами службы, а
    // admissionLock принадлежит экземпляру. Нужен отдельный multi-instance тест;
    // в этой декомпозиции форматирование и синхронизация сохранены буквально.
    private static final DecimalFormat DF = new DecimalFormat("0000000000");
    /** Заимствованные native ресурсы; только RocksDBService закрывает их. */
    private final RocksDB rocksDB;
    private final ColumnFamilyHandle urls, scheduled;

    UrlIndex(RocksDB rocksDB, ColumnFamilyHandle urls, ColumnFamilyHandle scheduled) {
        this.rocksDB = rocksDB;
        this.urls = urls;
        this.scheduled = scheduled;
    }

    /** Кодирует scheduling key; вызывающий сохраняет прежний admissionLock.
     * @param queue нормализованная очередь
     * @param epoch дата следующей выдачи в секундах
     * @param url URL без дополнительной нормализации
     * @return ключ в прежнем UTF-8 формате
     */
    byte[] schedulingKey(QueueWithinCrawl queue, long epoch, String url) {
        return (queue.toString() + "_" + DF.format(epoch) + "_" + url)
            .getBytes(StandardCharsets.UTF_8);
    }

    /** Читает состояние указателя без выдачи URL или изменения leases.
     * @param id crawl ID
     * @param url исходный URL
     * @param now время наблюдения в секундах
     * @param held уже прочитанные leases той же очереди
     * @return прежняя JSON-проекция состояния, без вывода об успехе fetch
     * @throws RocksDBException при ошибке чтения
     */
    com.google.gson.JsonObject observe(String id, String url, long now,
                                                     java.util.Map<String, Long> held) throws RocksDBException {
        QueueWithinCrawl q = QueueWithinCrawl.get(java.net.URI.create(url).getHost(), id);
        String prefix = q.toString() + "_";
        byte[] pointer = rocksDB.get(urls,
            (prefix + url).getBytes(StandardCharsets.UTF_8));
        com.google.gson.JsonObject result = new com.google.gson.JsonObject();
        String state = "UNKNOWN";
        result.add("next_due_epoch", com.google.gson.JsonNull.INSTANCE);
        result.add("lease_until_epoch", com.google.gson.JsonNull.INSTANCE);
        if (pointer != null && pointer.length == 0) state = "UNSCHEDULED";
        else if (pointer != null && rocksDB.get(scheduled, pointer) != null) {
            String key = new String(pointer, StandardCharsets.UTF_8);
            int separator = key.indexOf('_', prefix.length());
            if (!key.startsWith(prefix) || separator < 0 || !key.substring(separator + 1).equals(url))
                throw new IllegalStateException("Invalid scheduling pointer");
            long due = Long.parseLong(key.substring(prefix.length(), separator));
            result.addProperty("next_due_epoch", due);
            state = held.containsKey(url) ? "HELD" : due > now ? "SCHEDULED_WAIT" : "PENDING";
            if (held.containsKey(url)) result.addProperty("lease_until_epoch", held.get(url));
        }
        result.addProperty("state", state);
        return result;
    }

}
