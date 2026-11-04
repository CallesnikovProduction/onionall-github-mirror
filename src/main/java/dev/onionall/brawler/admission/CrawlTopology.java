package dev.onionall.brawler.admission;

import java.nio.file.Path;
import java.util.*;

/** Pure canonical product graph; no worker, network, scheduler or catalog side effects. */
final class CrawlTopology {
    private CrawlTopology() { }
    static Map<String,Object> build(Path config) {
        var bolts=new ArrayList<Map<String,Object>>();
        for(String[] b:List.of(new String[]{"partitioner","org.apache.stormcrawler.bolt.URLPartitionerBolt"},
            new String[]{"fetcher","org.apache.stormcrawler.bolt.SimpleFetcherBolt"},new String[]{"parse","org.apache.stormcrawler.bolt.JSoupParserBolt"},
            new String[]{"index","org.apache.stormcrawler.indexing.StdOutIndexer"},new String[]{"status","dev.onionall.brawler.archive.CapabilityArchiveStatusUpdaterBolt"}))
            bolts.add(Map.of("id",b[0],"className",b[1],"parallelism",1));
        var streams=new ArrayList<Map<String,Object>>();
        streams.add(Map.of("from","spout","to","partitioner","grouping",Map.of("type","SHUFFLE")));
        streams.add(Map.of("from","partitioner","to","fetcher","grouping",Map.of("type","FIELDS","args",List.of("key"))));
        streams.add(Map.of("from","fetcher","to","parse","grouping",Map.of("type","LOCAL_OR_SHUFFLE")));
        streams.add(Map.of("from","parse","to","index","grouping",Map.of("type","LOCAL_OR_SHUFFLE")));
        for(String from:List.of("fetcher","parse","index")) streams.add(Map.of("from",from,"to","status","grouping",Map.of("type","FIELDS","args",List.of("url"),"streamId","status")));
        return Map.of("name","onionall-bounded","includes",List.of(Map.of("resource",true,"file","/crawler-default.yaml","override",false),
            Map.of("resource",false,"file",config.toString(),"override",true)),"spouts",List.of(Map.of("id","spout","className","org.apache.stormcrawler.urlfrontier.CapabilitySpout","parallelism",1)),"bolts",bolts,"streams",streams);
    }
}
