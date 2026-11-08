package dev.onionall.brawler.admission;

import dev.onionall.brawler.crawl.CrawlConfigurationFactory;
import dev.onionall.brawler.tools.OnionSmoke;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import org.apache.storm.flux.parser.FluxParser;
import org.apache.storm.task.IOutputCollector;
import org.apache.storm.task.OutputCollector;
import org.apache.storm.tuple.Tuple;
import org.apache.stormcrawler.Metadata;
import org.apache.stormcrawler.indexing.StdOutIndexer;
import org.apache.stormcrawler.persistence.DefaultScheduler;
import org.apache.stormcrawler.persistence.Status;
import org.apache.stormcrawler.util.MetadataTransfer;
import org.junit.Test;
import static org.junit.Assert.*;

/** Actual installed scheduler and indexer behavior under the generated product configuration. */
public class CrawlGeneratedPolicyTest {
    private static final String URL = "http://" + OnionSmoke.HOST + "/short-private-path";

    private Map<String, Object> config(int seconds) throws Exception {
        var defaults = FluxParser.parseResource("/crawler-default.yaml", false, false, null, false).getConfig();
        var runtime = Map.<String, Object>of("frontier_host", "127.0.0.1", "frontier_port", 7071,
            "socks_host", "127.0.0.1", "socks_port", 19050, "seconds", seconds);
        var plan = Map.<String, Object>of("plan_id", UUID.randomUUID().toString(), "origin", "http://" + OnionSmoke.HOST);
        var config = new LinkedHashMap<String, Object>(defaults);
        config.putAll(CrawlConfigurationFactory.build(plan, runtime, Path.of("owned-archive"),
            Path.of("owned-token"), Path.of("owned-work"), 1000, 5));
        return config;
    }

    @Test public void discoveryFailureMarkerAloneGetsFutureErrorRetryWithoutChangingFetchStatus() throws Exception {
        var scheduler = new DefaultScheduler();
        scheduler.init(config(120));
        Metadata untagged = new Metadata();
        assertTrue(scheduler.schedule(Status.ERROR, untagged).isEmpty());
        Metadata tagged = new Metadata(); tagged.setValue("onionall.discovery.retry", "true");
        long before = System.currentTimeMillis();
        long scheduled = scheduler.schedule(Status.ERROR, tagged).orElseThrow().getTime();
        long after = System.currentTimeMillis();
        assertTrue(scheduled >= before + 5 * 60000L);
        assertTrue(scheduled <= after + 5 * 60000L);
        // The rule is status-specific, not a general override for every callback result.
        long fetched = scheduler.schedule(Status.FETCHED, tagged).orElseThrow().getTime();
        assertTrue(fetched > after + 23 * 60 * 60000L);
        tagged.remove("onionall.discovery.retry");
        assertTrue(scheduler.schedule(Status.ERROR, tagged).isEmpty());
    }

    @Test public void retryHintIsEphemeralWhileDiscoveryProvenanceRemainsPersisted() throws Exception {
        var config = config(120);
        Metadata current = new Metadata();
        current.setValue("onionall.discovery.retry", "true");
        current.setValue("discovery.depth", "1");
        current.setValue("discovery.source", URL);
        current.setValue("discovery.at", "2026-11-08T00:00:00Z");
        current.setValue("discovery.edges", "owned-edge");
        current.setValue("discovery.processing.complete", "false");
        current.setValue("discovery.processing.error", "UNRESOLVED");
        var scheduler = new DefaultScheduler(); scheduler.init(config);
        assertTrue(scheduler.schedule(Status.ERROR, current).isPresent());
        Metadata persisted = MetadataTransfer.getInstance(config).filter(current);
        assertNull(persisted.getFirstValue("onionall.discovery.retry"));
        for (String name : List.of("discovery.depth", "discovery.source", "discovery.at", "discovery.edges",
                "discovery.processing.complete", "discovery.processing.error")) {
            assertArrayEquals(name, current.getValues(name), persisted.getValues(name));
        }
        assertTrue("A later unrelated ERROR cannot inherit the retry authorization",
            scheduler.schedule(Status.ERROR, persisted).isEmpty());
    }

    @Test public void discoveryRpcBudgetIsAtMostWorkerTtlAndBelowTupleTimeout() throws Exception {
        for (int seconds : List.of(1, 2, 30, 120, 600)) {
            var config = config(seconds);
            int budget = (Integer) config.get("discovery.admission.timeout.ms");
            assertEquals(Math.min(30000, seconds * 1000), budget);
            assertTrue(budget <= seconds * 1000);
            assertTrue(budget < ((Number) config.get("topology.message.timeout.secs")).intValue() * 1000);
        }
    }

    @Test public void canonicalIndexerKeepsFetchedStatusAndAckWithoutPrintingDocumentData() throws Exception {
        var config = config(120);
        Metadata metadata = new Metadata();
        metadata.setValue("parse.title", "private-short-title");
        metadata.setValue("parse.keywords", "private-short-keyword");
        metadata.setValue("parse.description", "private-short-description");
        metadata.setValue("admission.token.file", "private-token-path-tripwire");
        Tuple tuple = (Tuple) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {Tuple.class},
            (proxy, method, arguments) -> switch (method.getName()) {
                case "getStringByField" -> arguments[0].equals("url") ? URL : "private-short-document";
                case "getValueByField" -> metadata;
                default -> throw new AssertionError("Unexpected tuple call " + method.getName());
            });
        List<String> events = new ArrayList<>();
        IOutputCollector collector = (IOutputCollector) Proxy.newProxyInstance(getClass().getClassLoader(),
            new Class<?>[] {IOutputCollector.class}, (proxy, method, arguments) -> {
                if (method.getName().equals("emit")) {
                    assertEquals("status", arguments[0]);
                    assertEquals(List.of(URL, metadata, Status.FETCHED), arguments[2]);
                    events.add("FETCHED");
                    return List.of();
                }
                if (method.getName().equals("ack")) { assertSame(tuple, arguments[0]); events.add("ACK"); return null; }
                throw new AssertionError("Unexpected collector call " + method.getName());
            });
        var indexer = new StdOutIndexer();
        var output = new ByteArrayOutputStream();
        PrintStream original = System.out;
        try (var capture = new PrintStream(output, true, StandardCharsets.UTF_8)) {
            System.setOut(capture);
            indexer.prepare(config, null, new OutputCollector(collector));
            indexer.execute(tuple);
        } finally {
            System.setOut(original);
            indexer.cleanup();
        }
        String printed = output.toString(StandardCharsets.UTF_8);
        for (String forbidden : List.of(URL, "private-short-title", "private-short-keyword",
                "private-short-description", "private-short-document", "private-token-path-tripwire")) {
            assertFalse("Unexpected indexer stdout data", printed.contains(forbidden));
        }
        assertEquals(List.of("FETCHED", "ACK"), events);
    }
}
