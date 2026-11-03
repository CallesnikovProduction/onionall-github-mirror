package dev.onionall.brawler.admission;

import java.nio.file.*;
import java.net.URLClassLoader;
import java.util.Map;
import java.util.jar.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class CrawlResourcesTest {
    @Test public void publishedWorkerClasspathHasFiltersAndLeavesInstalledArtifactUnchanged() throws Exception {
        Path directory=Files.createTempDirectory("crawl-resources"), original=directory.resolve("installed.jar"), result=directory.resolve("worker.jar");
        try(var jar=new JarOutputStream(Files.newOutputStream(original))) {
            jar.putNextEntry(new JarEntry("original.txt"));jar.write(new byte[]{1,2,3});jar.closeEntry();
        }
        byte[] before=Files.readAllBytes(original), filters="{\"filters\":[]}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        CrawlResources.publish(original,result,Map.of("onionall-job/parsefilters.json",filters));
        assertArrayEquals(before,Files.readAllBytes(original));
        try(var loader=new URLClassLoader(new java.net.URL[]{result.toUri().toURL()},null);
            var input=loader.getResourceAsStream("onionall-job/parsefilters.json")) {
            assertNotNull(input);assertArrayEquals(filters,input.readAllBytes());
        }
        assertThrows(java.io.IOException.class,()->CrawlResources.publish(original,result,Map.of()));
        assertThrows(java.io.IOException.class,()->CrawlResources.publish(original,directory.resolve("bad.jar"),Map.of("../unsafe",filters)));
    }
}
