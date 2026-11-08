package dev.onionall.brawler.admission;

import java.nio.file.Path;
import java.util.*;
import org.apache.storm.topology.OutputFieldsDeclarer;
import org.apache.storm.tuple.Fields;
import org.junit.Test;
import static org.junit.Assert.*;

/** Contracts of the installed StormCrawler components, without preparing or running a topology. */
public class CrawlTopologyContractTest {
    private static final class Declarations implements OutputFieldsDeclarer {
        private final Map<String, List<String>> fields = new LinkedHashMap<>();
        public void declare(Fields value) { declare(false, value); }
        public void declare(boolean direct, Fields value) { declareStream("default", direct, value); }
        public void declareStream(String stream, Fields value) { declareStream(stream, false, value); }
        public void declareStream(String stream, boolean direct, Fields value) {
            assertNull("Duplicate declaration for " + stream, fields.put(stream, value.toList()));
        }
    }

    @Test public void configuredGroupingsUseActualDeclaredFieldsOfInstalledComponents() throws Exception {
        var graph = CrawlTopology.build(Path.of("owned-config.json"));
        Map<String, Declarations> declared = new LinkedHashMap<>();
        List<Map<?, ?>> components = new ArrayList<>();
        for (String group : List.of("spouts", "bolts")) {
            for (Object entry : (List<?>) graph.get(group)) components.add((Map<?, ?>) entry);
        }
        for (var component : components) {
            Object instance = Class.forName((String) component.get("className")).getConstructor().newInstance();
            Declarations output = new Declarations();
            instance.getClass().getMethod("declareOutputFields", OutputFieldsDeclarer.class).invoke(instance, output);
            declared.put((String) component.get("id"), output);
        }
        for (Object entry : (List<?>) graph.get("streams")) {
            Map<?, ?> stream = (Map<?, ?>) entry;
            Map<?, ?> grouping = (Map<?, ?>) stream.get("grouping");
            String name = (String) grouping.getOrDefault("streamId", null);
            List<String> output = declared.get(stream.get("from")).fields.get(name == null ? "default" : name);
            assertNotNull("Configured stream must exist: " + stream, output);
            if ("FIELDS".equals(grouping.get("type"))) {
                assertTrue("Grouping keys must be emitted: " + stream, output.containsAll((List<?>) grouping.get("args")));
            }
        }
        assertEquals(List.of("url", "key", "metadata"), declared.get("partitioner").fields.get("default"));
        assertEquals(List.of("url", "content", "metadata"), declared.get("fetcher").fields.get("default"));
        assertEquals(List.of("url", "content", "metadata", "text"), declared.get("parse").fields.get("default"));
        for (String producer : List.of("fetcher", "parse", "index")) {
            assertEquals(List.of("url", "metadata", "status"), declared.get(producer).fields.get("status"));
        }
    }
}
