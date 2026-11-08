package dev.onionall.brawler.admission;

import dev.onionall.brawler.archive.ArchiveStore;
import dev.onionall.brawler.tools.OnionSmoke;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import org.apache.storm.flux.parser.FluxParser;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/** Preparation and actual Flux/plugin resource consumption; no worker or network is started. */
public class CrawlRuntimePreparerTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private record Fixture(Path root, Path archive, Path token, Path source, Path work,
                           Map<String, Object> plan, Map<String, Object> runtime) { }

    private Fixture fixture() throws Exception {
        Path root = temporary.newFolder().toPath(), archive = root.resolve("archive");
        Map<String, Object> plan;
        try (var store = new ArchiveStore(archive)) {
            var draft = store.createPlan(List.of("http://" + OnionSmoke.HOST + "/"), 2, 3, "runtime-fixture");
            plan = store.approvePlan((String) draft.get("plan_id"));
        }
        Path token = root.resolve("capability"), source = root.resolve("installed.jar");
        Files.writeString(token, "synthetic-runtime-capability-0000000000");
        var manifest = new Manifest();
        manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
        try (var jar = new JarOutputStream(Files.newOutputStream(source), manifest)) {
            jar.putNextEntry(new JarEntry("original.txt"));
            jar.write(new byte[] {1, 2, 3});
            jar.closeEntry();
        }
        var runtime = new LinkedHashMap<String, Object>();
        runtime.put("frontier_host", "127.0.0.1"); runtime.put("frontier_port", 7071);
        runtime.put("socks_host", "127.0.0.1"); runtime.put("socks_port", 19050);
        runtime.put("work_directory", root.resolve("work").toString());
        return new Fixture(root, archive, token, source, root.resolve("work").resolve((String) plan.get("plan_id")), plan, runtime);
    }

    private Path prepare(Fixture f) throws IOException {
        return CrawlRuntimePreparer.prepare(f.plan(), f.runtime(), f.archive(), f.token(), f.work(), f.source(), 1000, 5);
    }

    @Test public void actualFluxIncludesConsumeOneCoherentIdentityAndPackagedPluginResources() throws Exception {
        Fixture f = fixture();
        byte[] original = Files.readAllBytes(f.source());
        Path job = prepare(f);
        assertArrayEquals(original, Files.readAllBytes(f.source()));
        var topology = FluxParser.parseFile(f.work().resolve("topology.json").toString(), false, true, null, false);
        Map<String, Object> config = topology.getConfig();
        for (String key : List.of("archive.job.id", "admission.session.id", "urlfrontier.crawlid")) {
            assertEquals(key, f.plan().get("plan_id"), config.get(key));
        }
        assertEquals("onionall-bounded", topology.getName());
        assertEquals(5, topology.getBolts().size());
        assertEquals(7, topology.getStreams().size());
        assertEquals(f.archive().toAbsolutePath().toString(), config.get("archive.root"));
        assertEquals(f.token().toAbsolutePath().toString(), config.get("admission.token.file"));
        assertEquals(1000, config.get("http.timeout"));
        assertEquals("ONLY", config.get("tor.mode"));
        assertEquals("dev.onionall.brawler.tor.TorProtocol", config.get("http.protocol.implementation"));
        assertEquals("dev.onionall.brawler.tor.TorProtocol", config.get("https.protocol.implementation"));
        assertEquals(List.of(OnionSmoke.HOST), config.get("tor.allowed.hosts"));
        assertEquals(false, config.get("http.allow.redirects"));
        assertEquals(false, config.get("http.robots.file.skip"));
        assertEquals(-1, config.get("fetcher.max.throttle.sleep"));
        assertEquals(List.of(), config.get("metadata.transfer"));
        assertEquals("onionall-job/parsefilters.json", config.get("parsefilters.config.file"));
        assertEquals("onionall-job/urlfilters.json", config.get("urlfilters.config.file"));
        try (var jar = new JarFile(job.toFile())) {
            for (String name : List.of("parsefilters.json", "urlfilters.json")) {
                try (var input = jar.getInputStream(jar.getJarEntry("onionall-job/" + name))) {
                    assertArrayEquals(Files.readAllBytes(f.work().resolve(name)), input.readAllBytes());
                }
            }
        }
        // The real JSONResource loader resolves from its defining classloader, not an arbitrary
        // context loader. A worker-like isolated loader proves these exact resources are consumed.
        List<URL> classpath = new ArrayList<>();
        classpath.add(job.toUri().toURL());
        for (String path : System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"))
                .split(java.util.regex.Pattern.quote(System.getProperty("path.separator")))) {
            classpath.add(Path.of(path).toAbsolutePath().toUri().toURL());
        }
        try (var loader = new URLClassLoader(classpath.toArray(URL[]::new), ClassLoader.getPlatformClassLoader())) {
            Class<?> parseType = loader.loadClass("org.apache.stormcrawler.parse.ParseFilters");
            Object filters = parseType.getMethod("fromConf", Map.class).invoke(null, config);
            var field = parseType.getDeclaredField("filters"); field.setAccessible(true);
            Object[] chain = (Object[]) field.get(filters);
            assertEquals(List.of("dev.onionall.brawler.admission.DurableDiscoveryFilter",
                "dev.onionall.brawler.archive.ArchiveParseFilter"), Arrays.stream(chain)
                    .map(value -> value.getClass().getName()).toList());
            parseType.getMethod("cleanup").invoke(filters);
            Class<?> urlType = loader.loadClass("org.apache.stormcrawler.filtering.URLFilters");
            Object urlFilters = urlType.getMethod("fromConf", Map.class).invoke(null, config);
            var urlField = urlType.getDeclaredField("filters"); urlField.setAccessible(true);
            assertEquals(0, ((Object[]) urlField.get(urlFilters)).length);
        }
    }

    @Test public void partialFailureDoesNotPublishReadinessAndRetryRebuildsWholeBundle() throws Exception {
        Fixture f = fixture();
        Path obstacle = f.work().resolve("urlfilters.json");
        Files.createDirectories(obstacle);
        Files.writeString(obstacle.resolve("owned-obstacle"), "retained");
        assertThrows(IOException.class, () -> prepare(f));
        assertTrue(Files.isRegularFile(f.work().resolve("crawler-conf.json")));
        assertTrue(Files.isRegularFile(f.work().resolve("parsefilters.json")));
        try (var files = Files.list(f.work())) {
            assertFalse(files.anyMatch(file -> file.getFileName().toString().startsWith("worker-runtime-") ||
                file.getFileName().toString().startsWith(".runtime-")));
        }
        Files.delete(obstacle.resolve("owned-obstacle"));
        Files.delete(obstacle);
        f.runtime().put("frontier_port", 17071);
        Path job = prepare(f);
        assertTrue(Files.isRegularFile(job));
        var topology = FluxParser.parseFile(f.work().resolve("topology.json").toString(), false, true, null, false);
        assertEquals(17071, topology.getConfig().get("admission.port"));
        assertEquals(17071, topology.getConfig().get("urlfrontier.port"));
        try (var jar = new JarFile(job.toFile()); var input = jar.getInputStream(jar.getJarEntry("onionall-job/urlfilters.json"))) {
            assertArrayEquals(Files.readAllBytes(f.work().resolve("urlfilters.json")), input.readAllBytes());
        }
    }

    @Test public void invalidIdentityAndConfigurationFailBeforeWritingJobFiles() throws Exception {
        Fixture f = fixture();
        assertThrows(IOException.class, () -> CrawlRuntimePreparer.prepare(f.plan(), f.runtime(), f.archive(),
            f.token(), f.root().resolve("other-job"), f.source(), 1000, 5));
        assertFalse(Files.exists(f.root().resolve("other-job")));
        f.plan().put("approved", false);
        assertThrows(IOException.class, () -> prepare(f));
        assertFalse(Files.exists(f.work()));
        f.plan().put("approved", true);
        f.runtime().put("socks_port", 0);
        assertThrows(IOException.class, () -> prepare(f));
        assertFalse(Files.exists(f.work()));
    }

    @Test public void finalJobDirectorySymlinkCannotRedirectWritesOutsideConfiguredJob() throws Exception {
        Fixture f = fixture();
        Path outside = Files.createDirectory(f.root().resolve("outside"));
        Files.createDirectories(f.work().getParent());
        try {
            Files.createSymbolicLink(f.work(), outside);
        } catch (UnsupportedOperationException | IOException | SecurityException unavailable) {
            Assume.assumeNoException("Symlinks unavailable", unavailable);
        }
        assertThrows(IOException.class, () -> prepare(f));
        try (var files = Files.list(outside)) { assertEquals(0, files.count()); }
        assertTrue(Files.isSymbolicLink(f.work()));
    }

    @Test public void operatorConfiguredParentAliasRemainsSupported() throws Exception {
        Fixture f = fixture();
        Path authorized = Files.createDirectory(f.root().resolve("authorized-work-volume"));
        try {
            Files.createSymbolicLink(f.work().getParent(), authorized);
        } catch (UnsupportedOperationException | IOException | SecurityException unavailable) {
            Assume.assumeNoException("Symlinks unavailable", unavailable);
        }
        Path jar = prepare(f);
        assertTrue(Files.isRegularFile(jar));
        assertEquals(authorized.resolve((String) f.plan().get("plan_id")).resolve(jar.getFileName()).toRealPath(),
            jar.toRealPath());
    }
}
