package dev.onionall.brawler.admission;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.zip.CRC32;
import java.util.zip.ZipFile;

/**
 * <b>Публикация отдельного JAR с ресурсами задания.</b>
 *
 * <p>Исходный JAR неизменяем; bounded фильтры попадают в classpath StormCrawler через атомарную публикацию.
 * @author Stephan Kolesnikov
 * @author GPT-6.1 Sol
 */
final class CrawlResources {

    private static final String PARSE = "onionall-job/parsefilters.json";
    private static final String URL = "onionall-job/urlfilters.json";
    private static final int RESOURCE_LIMIT = 32768;
    private static final Set<String> NAMES = Set.of(PARSE, URL);
    private static final List<String> PARSE_CLASSES = List.of(
        "dev.onionall.brawler.admission.DurableDiscoveryFilter",
        "dev.onionall.brawler.archive.ArchiveParseFilter");
    private static final ObjectMapper JSON = new ObjectMapper()
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

    private CrawlResources() {
    }

    /**
     * <b>Атомарно публикует проверенный worker JAR без замены destination.</b>
     *
     * <p>Поддерживаются файловые системы с hard links: готовый временный файл и destination
     * находятся в одном каталоге. Создание нового имени атомарно отвергает существующий файл
     * или symlink; fallback на move отсутствует. Пути и их родители принадлежат оператору,
     * это не защита от враждебного изменения каталогов. Подписанный исходный JAR отвергается.
     * Ранее упакованные job-фильтры заменяются, остальные записи сохраняются.
     *
     * @param original исходный неизменяемый JAR
     * @param destination путь публикуемого JAR
     * @param resources полный набор bounded ресурсов задания
     * @throws IOException при неверном ресурсе, JAR, коллизии или отсутствии hard-link guarantees
     */
    static void publish(Path original, Path destination, Map<String, byte[]> resources) throws IOException {
        Map<String, byte[]> snapshot = resources(resources);
        if (!Files.isRegularFile(original, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Runtime source must be a regular JAR");
        }
        Path partial = Files.createTempFile(destination.toAbsolutePath().getParent(), ".crawler-", ".jar");
        try {
            Files.copy(original, partial, StandardCopyOption.REPLACE_EXISTING);
            requireUnsignedSource(partial);
            try (var zip = FileSystems.newFileSystem(partial, Map.of())) {
                for (var item : snapshot.entrySet()) {
                    Path entry = zip.getPath("/" + item.getKey());
                    Files.createDirectories(entry.getParent());
                    Files.write(entry, item.getValue());
                }
            }
            verify(partial, snapshot);
            try {
                Files.createLink(destination, partial);
            } catch (UnsupportedOperationException unsupported) {
                throw new IOException("Runtime publication requires atomic hard-link support", unsupported);
            }
        } catch (IOException | RuntimeException | Error failure) {
            try {
                Files.deleteIfExists(partial);
            } catch (IOException | SecurityException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
        // Publication has committed. A cleanup failure must not report a failed destination;
        // retained temporary hard links follow the existing retained-runtime lifecycle policy.
        try {
            Files.deleteIfExists(partial);
        } catch (IOException | SecurityException retainedTemporaryLink) {
            // No rollback: another component may already have opened the published artifact.
        }
    }

    private static Map<String, byte[]> resources(Map<String, byte[]> resources) throws IOException {
        if (resources == null || !NAMES.equals(resources.keySet())) {
            throw new IOException("Require the complete job filter resource set");
        }
        Map<String, byte[]> snapshot = new LinkedHashMap<>();
        int total = 0;
        for (String name : List.of(PARSE, URL)) {
            byte[] bytes = resources.get(name);
            if (bytes == null || bytes.length == 0 || bytes.length > RESOURCE_LIMIT) {
                throw new IOException("Invalid bounded runtime resource");
            }
            bytes = bytes.clone();
            total += bytes.length;
            if (total > 2 * RESOURCE_LIMIT) {
                throw new IOException("Runtime resources exceed aggregate limit");
            }
            JsonNode root = JSON.readTree(StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString());
            String key = name.equals(PARSE)
                ? "org.apache.stormcrawler.parse.ParseFilters"
                : "org.apache.stormcrawler.filtering.URLFilters";
            JsonNode filters = root == null ? null : root.get(key);
            if (root == null || !root.isObject() || filters == null || !filters.isArray() ||
                filters.size() != (name.equals(PARSE) ? PARSE_CLASSES.size() : 0)) {
                throw new IOException("Runtime resource lacks required filter configuration");
            }
            if (name.equals(PARSE)) {
                for (int i = 0; i < PARSE_CLASSES.size(); i++) {
                    JsonNode filter = filters.get(i);
                    if (!filter.isObject() || !filter.path("class").isTextual() ||
                        !PARSE_CLASSES.get(i).equals(filter.path("class").textValue()) ||
                        (filter.has("params") && !filter.get("params").isObject())) {
                        throw new IOException("Runtime resource changes the required parse filter chain");
                    }
                }
            }
            snapshot.put(name, bytes);
        }
        return snapshot;
    }

    private static void requireUnsignedSource(Path source) throws IOException {
        try (JarFile jar = new JarFile(source.toFile(), false)) {
            Set<String> names = new HashSet<>();
            var entries = jar.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                if (!names.add(name)) {
                    throw new IOException("Duplicate source JAR entry");
                }
                String upper = name.toUpperCase(Locale.ROOT);
                if (upper.startsWith("META-INF/") && upper.indexOf('/', "META-INF/".length()) < 0 &&
                    (upper.endsWith(".SF") || upper.endsWith(".RSA") || upper.endsWith(".DSA") ||
                        upper.endsWith(".EC") || upper.startsWith("META-INF/SIG-"))) {
                    throw new IOException("Signed runtime source cannot be repackaged");
                }
            }
        }
    }

    private static void verify(Path artifact, Map<String, byte[]> resources) throws IOException {
        try (JarFile jar = new JarFile(artifact.toFile(), false, ZipFile.OPEN_READ, JarFile.runtimeVersion())) {
            var entries = jar.entries();
            byte[] buffer = new byte[8192];
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }
                CRC32 crc = new CRC32();
                long size = 0;
                try (var input = jar.getInputStream(entry)) {
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        crc.update(buffer, 0, read);
                        size += read;
                    }
                }
                if (size != entry.getSize() || crc.getValue() != entry.getCrc()) {
                    throw new IOException("Unreadable or corrupt runtime JAR entry");
                }
            }
            for (var resource : resources.entrySet()) {
                var entry = jar.getJarEntry(resource.getKey());
                if (entry == null || entry.isDirectory()) {
                    throw new IOException("Runtime JAR lacks required resource");
                }
                try (var input = jar.getInputStream(entry)) {
                    if (!Arrays.equals(resource.getValue(), input.readNBytes(resource.getValue().length + 1))) {
                        throw new IOException("Runtime classpath resource differs from prepared configuration");
                    }
                }
            }
        }
    }
}
