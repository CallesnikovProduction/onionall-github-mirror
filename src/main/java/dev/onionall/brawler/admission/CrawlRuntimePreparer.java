package dev.onionall.brawler.admission;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.onionall.brawler.crawl.CrawlConfigurationFactory;
import java.io.IOException;
import java.nio.file.*;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * <b>Подготовка файлов и classpath-ресурсов задания.</b>
 *
 * <p>Вызывается под startup-lock и preparation ownership; исходный JAR неизменяем.
 */
final class CrawlRuntimePreparer {

    private static final ObjectMapper EXECUTION_JSON = new ObjectMapper();

    private CrawlRuntimePreparer() {
    }

    /**
     * <b>Публикует конфигурацию, топологию и отдельный worker JAR.</b>
     *
     * <p>Сохраняет порядок атомарных публикаций до admission.
     *
     * @param plan утверждённый план
     * @param runtime параметры среды выполнения
     * @param archive каталог архива
     * @param tokenFile файл capability
     * @param work рабочий каталог задания
     * @param jar исходный crawler JAR
     * @param httpTimeout таймаут HTTP
     * @param retryMinutes интервал повтора
     * @return путь к опубликованному worker JAR
     * @throws IOException при ошибке конфигурации или публикации
     */
    static Path prepare(
        Map<String, Object> plan,
        Map<String, Object> runtime,
        Path archive,
        Path tokenFile,
        Path work,
        Path jar,
        int httpTimeout,
        int retryMinutes
    ) throws IOException {
        Files.createDirectories(work);
        Path configuration = work.resolve("crawler-conf.json");
        Path topology = work.resolve("topology.json");
        write(configuration, Map.of("config", CrawlConfigurationFactory.build(plan, runtime,
            archive.toAbsolutePath(), tokenFile.toAbsolutePath(), work, httpTimeout, retryMinutes)));
        write(work.resolve("parsefilters.json"), Map.of("org.apache.stormcrawler.parse.ParseFilters",
            List.of(Map.of("class", "dev.onionall.brawler.admission.DurableDiscoveryFilter", "name",
            "DurableAdmission", "params", Map.of()), Map.of("class",
            "dev.onionall.brawler.archive.ArchiveParseFilter", "name", "Archive", "params", Map.of()))));
        write(work.resolve("urlfilters.json"), Map.of("org.apache.stormcrawler.filtering.URLFilters", List.of()));
        write(topology, CrawlTopology.build(configuration));
        Path jobJar = work.resolve("worker-runtime-" + UUID.randomUUID() + ".jar");
        CrawlResources.publish(jar, jobJar, Map.of("onionall-job/parsefilters.json",
            Files.readAllBytes(work.resolve("parsefilters.json")), "onionall-job/urlfilters.json",
            Files.readAllBytes(work.resolve("urlfilters.json"))));
        return jobJar;
    }

    /**
     * <b>Атомарно публикует JSON-файл задания.</b>
     *
     * <p>Временный файл удаляется и при ошибке; неатомарного fallback нет.
     *
     * @param path путь назначения
     * @param value сериализуемое значение
     * @throws IOException при ошибке записи или атомарного перемещения
     */
    static void write(Path path, Object value) throws IOException {
        Path tmp = Files.createTempFile(path.getParent(), ".runtime-", ".tmp");
        try {
            Files.write(tmp, EXECUTION_JSON.writeValueAsBytes(value));
            Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
