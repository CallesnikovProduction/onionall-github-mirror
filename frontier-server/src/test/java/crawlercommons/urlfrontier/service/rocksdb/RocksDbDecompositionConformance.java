// SPDX-License-Identifier: Apache-2.0
package crawlercommons.urlfrontier.service.rocksdb;

import crawlercommons.urlfrontier.service.QueueInterface;
import crawlercommons.urlfrontier.service.QueueWithinCrawl;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.rocksdb.ColumnFamilyDescriptor;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.ColumnFamilyOptions;
import org.rocksdb.DBOptions;
import org.rocksdb.RocksDB;

/**
 * <b>Проверки совместимости извлечённых компонентов на настоящей изолированной базе.</b>
 * <p>Формирует прежние bytes напрямую, независимо от нового кодирования, и проверяет
 * чтение, счётчики, corruption guard и сохранение заимствованных handles.</p>
 * @author GPT-6.1 Sol
 * @Reviewer Stephan Kolesnikov
 */
final class RocksDbDecompositionConformance {
    private static byte[] utf8(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    static void run(Path path) throws Exception {
        RocksDB.loadLibrary();
        try (ColumnFamilyOptions cf = new ColumnFamilyOptions();
             DBOptions options = new DBOptions().setCreateIfMissing(true).setCreateMissingColumnFamilies(true)) {
            var handles = new ArrayList<ColumnFamilyHandle>();
            var descriptors = Arrays.asList(new ColumnFamilyDescriptor(RocksDB.DEFAULT_COLUMN_FAMILY, cf),
                new ColumnFamilyDescriptor(utf8("queues"), cf),
                new ColumnFamilyDescriptor(utf8("queueInfos"), cf));
            try (RocksDB db = RocksDB.open(options, path.toString(), descriptors, handles)) {
                try {
                    UrlIndex index = new UrlIndex(db, handles.get(0), handles.get(1));
                    QueueWithinCrawl queue = QueueWithinCrawl.get("host_key", "crawl_id");
                    String url = "http://example.test/a_b?x=1_2";
                    byte[] existence = utf8(queue + "_" + url);
                    byte[] scheduling = utf8(queue + "_0000000042_" + url);
                    check(Arrays.equals(index.schedulingKey(queue, 42, url), scheduling), "Legacy key bytes changed");
                    check(Arrays.equals(index.schedulingKey(queue, 4102444800L, url),
                        utf8(queue + "_4102444800_" + url)), "Future date bytes changed");
                    check(index.observe("missing", url, 40, Collections.emptyMap())
                        .get("state").getAsString().equals("UNKNOWN"), "Unknown row invented");
                    db.put(handles.get(0), existence, scheduling);
                    db.put(handles.get(1), scheduling, new byte[]{1});
                    Map<QueueWithinCrawl, QueueInterface> queues = Collections.synchronizedMap(new LinkedHashMap<>());
                    QueueRecovery recovery = new QueueRecovery(db, handles.get(0), handles.get(1), handles.get(2), queues);
                    recovery.scan(true);
                    check(queues.get(queue).countActive() == 1, "Scheduled recovery count");
                    recovery.writeQueueInfos();
                    check(Arrays.equals(db.get(handles.get(2), queue.toString().getBytes()),
                        ByteBuffer.allocate(8).putInt(1).putInt(0).array()), "Metadata bytes changed");
                    queues.clear();
                    recovery.recover();
                    check(queues.get(queue).countActive() == 1, "Metadata readback count");
                    db.put(handles.get(0), utf8(queue + "_http://example.test/unscheduled"), new byte[0]);
                    queues.clear(); recovery.scan(true);
                    check(queues.get(queue).countActive() == 1 && queues.get(queue).getCountCompleted() == 1,
                        "Mixed scheduled/unscheduled counts");
                    db.put(handles.get(0), utf8(queue + "_http://example.test/bad-pointer"), scheduling);
                    queues.clear();
                    boolean rejected = false;
                    try { recovery.scan(true); } catch (RuntimeException expected) { rejected = true; }
                    check(rejected, "Recovery consistency guard removed");
                    check(db.get(handles.get(0), existence) != null, "Borrowed database closed or mutated");
                } finally {
                    for (ColumnFamilyHandle handle : handles) handle.close();
                }
            }
        }
        System.out.println("PASS RocksDbDecompositionConformance: raw legacy bytes, metadata, counters, consistency guard");
    }
}
