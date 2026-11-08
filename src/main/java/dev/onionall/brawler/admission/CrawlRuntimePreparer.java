package dev.onionall.brawler.admission;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.onionall.brawler.crawl.CrawlConfigurationFactory;
import java.io.IOException;
import java.nio.file.*;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static dev.onionall.brawler.crawl.RuntimeDescriptor.text;

/**
 * <b>Подготовка файлов и classpath-ресурсов задания.</b>
 *
 * <p>Вызывается под startup-lock и preparation ownership; исходный JAR неизменяем.
 * @author Stephan Kolesnikov
 * @author GPT-6.1 Sol
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
        String id = plan == null ? null : (plan.get("plan_id") instanceof String value ? value : null);
        if (id == null || !id.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}") ||
            !Boolean.TRUE.equals(plan.get("approved"))) {
            throw new IOException("Require an approved archive plan for runtime preparation");
        }
        if (runtime == null || work == null || archive == null || tokenFile == null || jar == null) {
            throw new IOException("Require runtime preparation paths and configuration");
        }
        Path configuredWork;
        try {
            configuredWork = Path.of(text(runtime, "work_directory")).toAbsolutePath().normalize().resolve(id);
        } catch (RuntimeException invalid) {
            throw new IOException("Invalid runtime work directory");
        }
        if (!configuredWork.equals(work.toAbsolutePath().normalize())) {
            throw new IOException("Runtime work directory does not match the approved plan");
        }
        work = configuredWork;
        Map<String, Object> configurationValues;
        try {
            configurationValues = CrawlConfigurationFactory.build(plan, runtime, archive.toAbsolutePath(),
                tokenFile.toAbsolutePath(), work, httpTimeout, retryMinutes);
        } catch (RuntimeException invalid) {
            throw new IOException("Invalid runtime preparation configuration");
        }
        byte[] configurationBytes = EXECUTION_JSON.writeValueAsBytes(Map.of("config", configurationValues));
        byte[] parseFilters = EXECUTION_JSON.writeValueAsBytes(Map.of("org.apache.stormcrawler.parse.ParseFilters",
            List.of(Map.of("class", "dev.onionall.brawler.admission.DurableDiscoveryFilter", "name",
                "DurableAdmission", "params", Map.of()), Map.of("class",
                "dev.onionall.brawler.archive.ArchiveParseFilter", "name", "Archive", "params", Map.of()))));
        byte[] urlFilters = EXECUTION_JSON.writeValueAsBytes(Map.of("org.apache.stormcrawler.filtering.URLFilters", List.of()));
        Path configuration = work.resolve("crawler-conf.json");
        byte[] topology = EXECUTION_JSON.writeValueAsBytes(CrawlTopology.build(configuration));

        // The operator owns the parent directory. This check rejects a mistaken final
        // job-directory symlink; it is not containment against a hostile directory owner.
        if (Files.exists(work, LinkOption.NOFOLLOW_LINKS) && !Files.isDirectory(work, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Require a regular runtime job directory");
        }
        Files.createDirectories(work);
        if (!Files.isDirectory(work, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Require a regular runtime job directory");
        }
        writeBytes(configuration, configurationBytes);
        writeBytes(work.resolve("parsefilters.json"), parseFilters);
        writeBytes(work.resolve("urlfilters.json"), urlFilters);
        writeBytes(work.resolve("topology.json"), topology);
        Path jobJar = work.resolve("worker-runtime-" + UUID.randomUUID() + ".jar");
        CrawlResources.publish(jar, jobJar, Map.of("onionall-job/parsefilters.json",
            parseFilters, "onionall-job/urlfilters.json", urlFilters));
        // Only this successful return publishes preparation readiness to CrawlExecution.
        // Partial JSON files are retained diagnostics, never a filesystem ready signal.
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
        writeBytes(path, EXECUTION_JSON.writeValueAsBytes(value));
    }

    private static void writeBytes(Path path, byte[] bytes) throws IOException {
        Path tmp = Files.createTempFile(path.getParent(), ".runtime-", ".tmp");
        try {
            Files.write(tmp, bytes);
            Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
