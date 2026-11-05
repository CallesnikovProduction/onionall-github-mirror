package dev.onionall.brawler.admission;

import java.io.IOException;
import java.nio.file.*;
import java.util.Map;

/**
 * <b>Публикация отдельного JAR с ресурсами задания.</b>
 *
 * <p>Исходный JAR неизменяем; bounded фильтры попадают в classpath StormCrawler через атомарную публикацию.
 */
final class CrawlResources {

    private CrawlResources() {
    }

    /**
     * <b>Атомарно публикует worker JAR с bounded ресурсами.</b>
     *
     * <p>Проверяет имена и размеры ресурсов; не заменяет существующий destination и удаляет временный файл.
     *
     * @param original исходный неизменяемый JAR
     * @param destination путь публикуемого JAR
     * @param resources bounded ресурсы задания
     * @throws IOException при ошибке операции или нарушении её контракта
     */
    static void publish(Path original, Path destination, Map<String, byte[]> resources) throws IOException {
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Runtime artifact already exists");
        }
        for (var item : resources.entrySet()) {
            if (!item.getKey().matches("onionall-job/[a-z]+filters\\.json") || item.getValue().length > 32768) {
                throw new IOException("Invalid bounded runtime resource");
            }
        }
        Path partial = Files.createTempFile(destination.getParent(), ".crawler-", ".jar");
        try {
            Files.copy(original, partial, StandardCopyOption.REPLACE_EXISTING);
            try (var zip = FileSystems.newFileSystem(partial, Map.of())) {
                for (var item : resources.entrySet()) {
                    Path entry = zip.getPath("/" + item.getKey());
                    Files.createDirectories(entry.getParent());
                    Files.write(entry, item.getValue());
                }
            }
            Files.move(partial, destination, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(partial);
        }
    }
}
