package dev.onionall.brawler.admission;

import java.io.IOException;
import java.nio.file.*;
import java.util.Map;

/** Publish a job-specific runtime artifact. Installed crawler JAR stays immutable.
 * StormCrawler reads filter configuration from the worker classpath, not a host path.
 */
final class CrawlResources {
    private CrawlResources() { }
    static void publish(Path original, Path destination, Map<String,byte[]> resources) throws IOException {
        if (Files.exists(destination,LinkOption.NOFOLLOW_LINKS)) throw new IOException("Runtime artifact already exists");
        for(var item:resources.entrySet()) {
            if (!item.getKey().matches("onionall-job/[a-z]+filters\\.json") || item.getValue().length>32768)
                throw new IOException("Invalid bounded runtime resource");
        }
        Path partial=Files.createTempFile(destination.getParent(),".crawler-",".jar");
        try {
            Files.copy(original,partial,StandardCopyOption.REPLACE_EXISTING);
            try(var zip=FileSystems.newFileSystem(partial,Map.of())) {
                for(var item:resources.entrySet()) {
                    Path entry=zip.getPath("/"+item.getKey());Files.createDirectories(entry.getParent());
                    Files.write(entry,item.getValue());
                }
            }
            Files.move(partial,destination,StandardCopyOption.ATOMIC_MOVE);
        } finally { Files.deleteIfExists(partial); }
    }
}
