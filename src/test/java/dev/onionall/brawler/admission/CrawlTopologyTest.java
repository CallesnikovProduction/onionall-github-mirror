package dev.onionall.brawler.admission;

import java.nio.file.Path;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class CrawlTopologyTest {
    private static List<?> rows(Map<String,Object> graph,String key){return (List<?>)graph.get(key);}
    @Test public void canonicalGraphKeepsCapabilityAdaptersAndFiveBoltChain() {
        var graph=CrawlTopology.build(Path.of("owned config with spaces.json"));
        assertEquals("onionall-bounded",graph.get("name"));
        var spout=(Map<?,?>)rows(graph,"spouts").getFirst();
        assertEquals("org.apache.stormcrawler.urlfrontier.CapabilitySpout",spout.get("className"));
        assertEquals(List.of("partitioner","fetcher","parse","index","status"),rows(graph,"bolts").stream().map(r->((Map<?,?>)r).get("id")).toList());
        assertEquals(List.of("org.apache.stormcrawler.bolt.URLPartitionerBolt","org.apache.stormcrawler.bolt.SimpleFetcherBolt",
            "org.apache.stormcrawler.bolt.JSoupParserBolt","org.apache.stormcrawler.indexing.StdOutIndexer",
            "dev.onionall.brawler.archive.CapabilityArchiveStatusUpdaterBolt"),rows(graph,"bolts").stream().map(r->((Map<?,?>)r).get("className")).toList());
        for(var row:rows(graph,"bolts"))assertEquals(1,((Map<?,?>)row).get("parallelism"));
    }
    @Test public void fieldGroupingAndThreeStatusStreamsKeepTheirContracts() {
        var graph=CrawlTopology.build(Path.of("fixture.json"));var streams=rows(graph,"streams");assertEquals(7,streams.size());
        var fetch=(Map<?,?>)streams.get(1);
        assertEquals("partitioner",fetch.get("from"));assertEquals("fetcher",fetch.get("to"));
        assertEquals(Map.of("type","FIELDS","args",List.of("key")),fetch.get("grouping"));
        assertEquals(List.of("fetcher","parse","index"),streams.subList(4,7).stream().map(r->((Map<?,?>)r).get("from")).toList());
        for(var row:streams.subList(4,7)) {
            var stream=(Map<?,?>)row;assertEquals("status",stream.get("to"));
            assertEquals(Map.of("type","FIELDS","args",List.of("url"),"streamId","status"),stream.get("grouping"));
        }
    }
    @Test public void includesKeepDefaultsThenExactOperatorPathWithoutOpeningIt() {
        Path path=Path.of("no such fixture directory","config with spaces.json");var graph=CrawlTopology.build(path);
        assertEquals(List.of(Map.of("resource",true,"file","/crawler-default.yaml","override",false),
            Map.of("resource",false,"file",path.toString(),"override",true)),rows(graph,"includes"));
        assertEquals(graph,CrawlTopology.build(path));
    }
}
