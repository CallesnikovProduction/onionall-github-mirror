package dev.onionall.brawler.admission;

import java.io.IOException;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.jar.*;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class CrawlResourcesTest {
    private static final String PARSE = "onionall-job/parsefilters.json";
    private static final String URL = "onionall-job/urlfilters.json";
    private static final String PARSE_JSON = """
        {"org.apache.stormcrawler.parse.ParseFilters":[
          {"class":"dev.onionall.brawler.admission.DurableDiscoveryFilter","name":"DurableAdmission","params":{}},
          {"class":"dev.onionall.brawler.archive.ArchiveParseFilter","name":"Archive","params":{}}
        ]}
        """;
    private static final String URL_JSON = "{\"org.apache.stormcrawler.filtering.URLFilters\":[]}";
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static Map<String, byte[]> resources() {
        return new LinkedHashMap<>(Map.of(PARSE, bytes(PARSE_JSON), URL, bytes(URL_JSON)));
    }

    private Path source(Map<String, byte[]> entries, boolean multiRelease) throws Exception {
        Path source = temporary.getRoot().toPath().resolve(UUID.randomUUID() + ".jar");
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        if (multiRelease) {
            manifest.getMainAttributes().put(Attributes.Name.MULTI_RELEASE, "true");
        }
        try (var jar = new JarOutputStream(Files.newOutputStream(source), manifest)) {
            for (var entry : entries.entrySet()) {
                jar.putNextEntry(new JarEntry(entry.getKey()));
                jar.write(entry.getValue());
                jar.closeEntry();
            }
        }
        return source;
    }

    private Path source() throws Exception {
        return source(Map.of("original.txt", new byte[]{1, 2, 3}), false);
    }

    private Path destination() {
        return temporary.getRoot().toPath().resolve(UUID.randomUUID() + ".jar");
    }

    private void noPartialArtifacts() throws Exception {
        try (var files = Files.list(temporary.getRoot().toPath())) {
            assertFalse(files.anyMatch(path -> path.getFileName().toString().startsWith(".crawler-")));
        }
    }

    private static void assertClasspathResources(Path jar, Map<String, byte[]> expected) throws Exception {
        try (var loader = new URLClassLoader(new java.net.URL[]{jar.toUri().toURL()}, null)) {
            for (var entry : expected.entrySet()) {
                try (var input = loader.getResourceAsStream(entry.getKey())) {
                    assertNotNull(input);
                    assertArrayEquals(entry.getValue(), input.readAllBytes());
                }
            }
            try (var input = loader.getResourceAsStream("original.txt")) {
                assertNotNull(input);
                assertArrayEquals(new byte[]{1, 2, 3}, input.readAllBytes());
            }
        }
    }

    @Test public void publishedWorkerClasspathContainsCompleteFiltersAndSourceRemainsUnchanged() throws Exception {
        Path original = source(), result = destination();
        byte[] before = Files.readAllBytes(original);
        Map<String, byte[]> filters = resources();
        CrawlResources.publish(original, result, filters);
        assertArrayEquals(before, Files.readAllBytes(original));
        assertClasspathResources(result, filters);
        noPartialArtifacts();
    }

    @Test public void prepackagedJobEntriesAreReplacedWithoutChangingOriginalJar() throws Exception {
        Path original = source(Map.of("original.txt", new byte[]{1, 2, 3},
            PARSE, bytes("obsolete parse filters"), URL, bytes("obsolete URL filters")), false);
        byte[] before = Files.readAllBytes(original);
        Path result = destination();
        CrawlResources.publish(original, result, resources());
        assertArrayEquals(before, Files.readAllBytes(original));
        assertClasspathResources(result, resources());
        noPartialArtifacts();
    }

    @Test public void existingRegularDestinationAndSourceAliasCannotBeReplaced() throws Exception {
        Path original = source(), result = destination();
        byte[] before = Files.readAllBytes(original), competing = bytes("another publisher");
        Files.write(result, competing);
        assertThrows(FileAlreadyExistsException.class, () -> CrawlResources.publish(original, result, resources()));
        assertArrayEquals(competing, Files.readAllBytes(result));
        assertThrows(FileAlreadyExistsException.class, () -> CrawlResources.publish(original, original, resources()));
        assertArrayEquals(before, Files.readAllBytes(original));
        noPartialArtifacts();
    }

    @Test public void existingAndDanglingSymlinksCannotBeReplacedOrFollowed() throws Exception {
        Path original = source(), target = destination();
        Files.write(target, bytes("preserved target"));
        for (Path linkTarget : List.of(target, destination())) {
            Path result = destination();
            try {
                Files.createSymbolicLink(result, linkTarget);
            } catch (UnsupportedOperationException | FileSystemException unavailable) {
                Assume.assumeNoException("Symbolic links unavailable on this platform", unavailable);
            }
            assertThrows(FileAlreadyExistsException.class, () -> CrawlResources.publish(original, result, resources()));
            assertTrue(Files.isSymbolicLink(result));
            assertEquals(linkTarget, Files.readSymbolicLink(result));
        }
        assertEquals("preserved target", Files.readString(target));
        noPartialArtifacts();
    }

    @Test public void concurrentPublishersHaveExactlyOneWinner() throws Exception {
        Path original = source(), result = destination();
        byte[] before = Files.readAllBytes(original);
        Map<String, byte[]> first = resources(), second = resources();
        second.put(PARSE, bytes(PARSE_JSON + " "));
        CountDownLatch ready = new CountDownLatch(2), start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<Integer>> outcomes = new ArrayList<>();
            List<Map<String, byte[]>> candidates = List.of(first, second);
            for (int i = 0; i < candidates.size(); i++) {
                final int publisher = i;
                outcomes.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("Publisher start deadline");
                    }
                    try {
                        CrawlResources.publish(original, result, candidates.get(publisher));
                        return publisher;
                    } catch (FileAlreadyExistsException collision) {
                        return -1;
                    }
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            int a = outcomes.get(0).get(10, TimeUnit.SECONDS), b = outcomes.get(1).get(10, TimeUnit.SECONDS);
            assertTrue((a == 0 && b == -1) || (a == -1 && b == 1));
            assertClasspathResources(result, candidates.get(a >= 0 ? a : b));
            assertArrayEquals(before, Files.readAllBytes(original));
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
        noPartialArtifacts();
    }

    @Test public void missingUnexpectedNullAndOversizedResourcesAreRejectedBeforePublication() throws Exception {
        Path original = source();
        List<Map<String, byte[]>> invalid = new ArrayList<>();
        invalid.add(null);
        invalid.add(Map.of());
        invalid.add(Map.of(PARSE, bytes(PARSE_JSON)));
        Map<String, byte[]> extra = resources();
        extra.put("onionall-job/otherfilters.json", bytes("{}"));
        invalid.add(extra);
        Map<String, byte[]> traversal = resources();
        traversal.put("../unsafe", bytes("{}"));
        invalid.add(traversal);
        for (String name : List.of(PARSE, URL)) {
            for (byte[] value : Arrays.asList(null, new byte[0], new byte[32769])) {
                Map<String, byte[]> bad = resources();
                bad.put(name, value);
                invalid.add(bad);
            }
        }
        for (Map<String, byte[]> bad : invalid) {
            Path result = destination();
            assertThrows(IOException.class, () -> CrawlResources.publish(original, result, bad));
            assertFalse(Files.exists(result, LinkOption.NOFOLLOW_LINKS));
        }
        noPartialArtifacts();
    }

    @Test public void malformedUtf8JsonAndIncompleteOrReorderedFilterChainAreRejected() throws Exception {
        Path original = source();
        List<byte[]> invalidParse = List.of(bytes("{}"), bytes("null"), bytes("[]"), bytes("{"),
            bytes(PARSE_JSON + " {}"), bytes("{\"x\":1,\"x\":2}"),
            bytes("{\"org.apache.stormcrawler.parse.ParseFilters\":[]}"),
            bytes(PARSE_JSON.replace("DurableDiscoveryFilter", "UntrustedFilter")),
            bytes(PARSE_JSON.replace("\"params\":{}", "\"params\":null")),
            bytes(PARSE_JSON.replace("dev.onionall.brawler.admission.DurableDiscoveryFilter", "TEMP")
                .replace("dev.onionall.brawler.archive.ArchiveParseFilter",
                    "dev.onionall.brawler.admission.DurableDiscoveryFilter")
                .replace("TEMP", "dev.onionall.brawler.archive.ArchiveParseFilter")),
            new byte[]{(byte) 0xc3, 0x28});
        for (byte[] invalid : invalidParse) {
            Map<String, byte[]> bad = resources();
            bad.put(PARSE, invalid);
            Path result = destination();
            assertThrows(IOException.class, () -> CrawlResources.publish(original, result, bad));
            assertFalse(Files.exists(result));
        }
        for (String invalid : List.of("{}", "{\"org.apache.stormcrawler.filtering.URLFilters\":[{}]}")) {
            Map<String, byte[]> bad = resources();
            bad.put(URL, bytes(invalid));
            assertThrows(IOException.class, () -> CrawlResources.publish(original, destination(), bad));
        }
        noPartialArtifacts();
    }

    @Test public void eachResourceMayUseItsFullLimitAndArraysAreSnapshotted() throws Exception {
        Path original = source(), result = destination();
        Map<String, byte[]> maximum = resources();
        for (String name : List.of(PARSE, URL)) {
            byte[] padded = new byte[32768];
            Arrays.fill(padded, (byte) ' ');
            System.arraycopy(maximum.get(name), 0, padded, 0, maximum.get(name).length);
            maximum.put(name, padded);
        }
        CrawlResources.publish(original, result, maximum);
        assertClasspathResources(result, maximum);
        byte[] parse = bytes(PARSE_JSON), expectedParse = parse.clone();
        Map<String, byte[]> changing = new LinkedHashMap<>(resources()) {
            @Override public byte[] get(Object key) {
                if (URL.equals(key)) {
                    Arrays.fill(parse, (byte) 'x');
                }
                return super.get(key);
            }
        };
        changing.put(PARSE, parse);
        Path snapshot = destination();
        CrawlResources.publish(original, snapshot, changing);
        assertClasspathResources(snapshot, Map.of(PARSE, expectedParse, URL, bytes(URL_JSON)));
        noPartialArtifacts();
    }

    @Test public void corruptAndSignatureBearingSourcesFailWithoutPublicationOrSourceChanges() throws Exception {
        List<Path> invalid = new ArrayList<>();
        Path corrupt = destination();
        Files.write(corrupt, bytes("not a ZIP or a JAR"));
        invalid.add(corrupt);
        for (String signature : List.of("META-INF/TEST.SF", "META-INF/TEST.RSA", "META-INF/TEST.DSA",
            "META-INF/TEST.EC", "META-INF/SIG-TEST")) {
            invalid.add(source(Map.of(signature, new byte[]{1}), false));
        }
        for (Path original : invalid) {
            byte[] before = Files.readAllBytes(original);
            Path result = destination();
            assertThrows(IOException.class, () -> CrawlResources.publish(original, result, resources()));
            assertFalse(Files.exists(result));
            assertArrayEquals(before, Files.readAllBytes(original));
        }
        noPartialArtifacts();
    }

    @Test public void runtimeVersionedResourceCannotShadowPreparedConfiguration() throws Exception {
        Path original = source(Map.of("original.txt", new byte[]{1, 2, 3},
            "META-INF/versions/9/" + PARSE, bytes("shadowed parse configuration")), true);
        byte[] before = Files.readAllBytes(original);
        Path result = destination();
        assertThrows(IOException.class, () -> CrawlResources.publish(original, result, resources()));
        assertFalse(Files.exists(result));
        assertArrayEquals(before, Files.readAllBytes(original));
        noPartialArtifacts();
    }

    @Test public void damagedStoredEntryIsNotPublishedAsAnOtherwiseReadableZip() throws Exception {
        Path original = destination();
        byte[] content = bytes("unique-original-entry-content");
        CRC32 crc = new CRC32();
        crc.update(content);
        try (var jar = new JarOutputStream(Files.newOutputStream(original))) {
            JarEntry entry = new JarEntry("original.txt");
            entry.setMethod(ZipEntry.STORED);
            entry.setSize(content.length);
            entry.setCompressedSize(content.length);
            entry.setCrc(crc.getValue());
            jar.putNextEntry(entry);
            jar.write(content);
            jar.closeEntry();
        }
        byte[] damaged = Files.readAllBytes(original);
        boolean changed = false;
        for (int i = 0; i <= damaged.length - content.length; i++) {
            if (Arrays.equals(content, Arrays.copyOfRange(damaged, i, i + content.length))) {
                damaged[i] ^= 1;
                changed = true;
                break;
            }
        }
        assertTrue(changed);
        Files.write(original, damaged);
        Path result = destination();
        assertThrows(IOException.class, () -> CrawlResources.publish(original, result, resources()));
        assertFalse(Files.exists(result));
        assertArrayEquals(damaged, Files.readAllBytes(original));
        noPartialArtifacts();
    }
}
