package dev.onionall.brawler.archive;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.storm.generated.StormTopology;
import org.apache.storm.metric.api.IMetric;
import org.apache.storm.task.*;
import org.apache.storm.tuple.Tuple;
import org.apache.stormcrawler.Metadata;
import org.apache.stormcrawler.bolt.JSoupParserBolt;
import org.apache.stormcrawler.persistence.Status;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/** Installed parser component characterization; not a full Storm ACK-tree acceptance. */
public class ParserArchiveAckCharacterizationTest {
    @Rule public TemporaryFolder temporary=new TemporaryFolder();
    private static final String HOST=dev.onionall.brawler.tools.OnionSmoke.HOST,URL="http://"+HOST+"/";
    private static final byte[] HTML="<html><head><title>Owned ACK fixture</title></head><body>archive proof</body></html>".getBytes(StandardCharsets.UTF_8);
    private static final class Context extends TopologyContext {
        Context(Map<String,Object> conf,Path root) {
            super(new StormTopology(),conf,Map.of(1,"parse"),Map.of("parse",List.of(1)),Map.of(),Map.of(),
                "owned-archive-ack",root.toString(),root.toString(),1,6700,List.of(1),new HashMap<>(),new HashMap<>(),new HashMap<>(),new HashMap<>(),new AtomicBoolean(),null);
        }
        @Override public <T extends IMetric> T registerMetric(String name,T metric,int seconds){return metric;}
    }
    private void characterize(boolean corrupt)throws Exception {
        Path root=temporary.newFolder().toPath();String job;
        try(var store=new ArchiveStore(root)){job=(String)store.createPlan(List.of(URL),0,1,"parser-ack-fixture").get("plan_id");store.approvePlan(job);}
        String digest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(HTML));
        if(corrupt)Files.writeString(root.resolve("objects").resolve(digest+".html"),"owned corrupted immutable fixture");
        Path classpath=Path.of(getClass().getProtectionDomain().getCodeSource().getLocation().toURI());
        String resource="owned-parser-ack-"+UUID.randomUUID()+".json";Path filters=classpath.resolve(resource);
        Map<String,Object> conf=new HashMap<>();
        conf.put("archive.root",root.toString());conf.put("archive.job.id",job);conf.put("protocol.md.prefix","protocol.");
        conf.put("tor.mode","ONLY");conf.put("tor.socks.host","127.0.0.1");conf.put("tor.socks.port",1);conf.put("tor.allowed.hosts",List.of(HOST));
        conf.put("http.protocol.implementation","dev.onionall.brawler.tor.TorProtocol");conf.put("https.protocol.implementation","dev.onionall.brawler.tor.TorProtocol");
        conf.put("parsefilters.config.file",resource);conf.put("urlfilters.config.file",resource);conf.put("jsoupfilters.config.file",resource);
        conf.put("detect.mimetype",false);conf.put("stormcrawler.metrics.version","v1");
        Files.write(filters,new ObjectMapper().writeValueAsBytes(Map.of("org.apache.stormcrawler.parse.ParseFilters",List.of(
            Map.of("class",ArchiveParseFilter.class.getName(),"name","Archive","params",Map.of())),
            "org.apache.stormcrawler.parse.JSoupFilters",List.of(),"org.apache.stormcrawler.filtering.URLFilters",List.of())),StandardOpenOption.CREATE_NEW);
        var md=new Metadata();md.setValue("fetch.statusCode","200");md.setValue("protocol.content-type","text/html");md.setValue("protocol.Content-Type","text/html");
        List<String> events=new ArrayList<>();List<Status> statuses=new ArrayList<>();
        IOutputCollector output=(IOutputCollector)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{IOutputCollector.class},(proxy,method,args)->{
            if(method.getName().equals("emit")) {
                String stream=(String)args[0];events.add("emit:"+stream);
                if(stream.equals("status")){var values=(List<?>)args[2];statuses.add((Status)values.get(2));}
                else assertEquals("Archive must commit before successful emission","true",md.getFirstValue("archive.committed"));
                return List.of();
            }
            if(method.getName().equals("ack")||method.getName().equals("fail")){events.add(method.getName());return null;}
            throw new AssertionError("Unexpected collector method: "+method.getName());
        });
        Tuple tuple=(Tuple)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{Tuple.class},(proxy,method,args)->switch(method.getName()) {
            case "getStringByField" -> URL;case "getBinaryByField" -> HTML;case "getValueByField" -> md;
            default -> throw new AssertionError("Unexpected tuple method: "+method.getName());
        });
        JSoupParserBolt parser=new JSoupParserBolt();
        try {
            parser.prepare(conf,new Context(conf,root),new OutputCollector(output));parser.execute(tuple);
            if(corrupt) {
                assertNull(md.getFirstValue("archive.committed"));assertEquals("content filtering",md.getFirstValue("error.source"));
                assertTrue(md.getFirstValue("error.message").contains("Archive commit failed"));
                assertEquals(List.of(Status.ERROR),statuses);assertEquals(List.of("emit:status","ack"),events);
                try(var store=new ArchiveStore(root)){assertEquals(0L,store.job(job).get("snapshots"));assertEquals(0L,store.job(job).get("archived_pages"));}
                System.out.println("ARCHIVE ACK CHARACTERIZATION: installed parser emits ERROR then ACK after real archive commit fault; no FETCHED/default output or snapshot");
            } else {
                assertEquals("true",md.getFirstValue("archive.committed"));assertTrue(events.contains("emit:default"));assertEquals("ack",events.getLast());
                try(var store=new ArchiveStore(root)){assertEquals(1L,store.job(job).get("snapshots"));}
            }
        }finally{parser.cleanup();Files.deleteIfExists(filters);}
    }
    @Test public void successfulArchiveCommitsBeforeInstalledParserEmissionAndAck()throws Exception{characterize(false);}
    @Test public void archiveFaultCurrentlyEmitsErrorAndAcksInsteadOfFailingTuple()throws Exception{characterize(true);}
}
