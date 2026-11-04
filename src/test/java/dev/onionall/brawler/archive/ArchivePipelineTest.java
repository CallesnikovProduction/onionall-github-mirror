package dev.onionall.brawler.archive;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.apache.stormcrawler.Metadata;
import org.apache.stormcrawler.parse.ParseResult;
import org.junit.Test;
import static org.junit.Assert.*;

public class ArchivePipelineTest {
    private static final String HOST="aeaqcaibaeaqcaibaeaqcaibaeaqcaibaeaqcaibaeaqcaibaea37ead.onion";
    private static final String URL="http://"+HOST+"/";
    private static Map<String,Object> conf(Path root,String job) {
        Map<String,Object> conf=new HashMap<>();
        conf.put("archive.root",root.toString());conf.put("archive.job.id",job);
        conf.put("protocol.md.prefix","protocol.");conf.put("tor.mode","ONLY");conf.put("tor.socks.host","127.0.0.1");conf.put("tor.socks.port",1);conf.put("tor.allowed.hosts",List.of(HOST));
        conf.put("http.protocol.implementation","dev.onionall.brawler.tor.TorProtocol");
        conf.put("https.protocol.implementation","dev.onionall.brawler.tor.TorProtocol");
        return conf;
    }
    @Test public void realParseCallbackCommitsBeforeReturningAndRetainsProvenance() throws Exception {
        Path root=Files.createTempDirectory("archive-filter");String job;
        try(var store=new ArchiveStore(root)){job=(String)store.createPlan(List.of(URL),1,2,"user").get("plan_id");store.approvePlan(job);}
        var filter=new ArchiveParseFilter();filter.configure(conf(root,job),new ObjectMapper().createObjectNode(),"Archive");
        var parse=new ParseResult(List.of());var md=new Metadata();
        md.setValue("fetch.statusCode","200");md.setValue("protocol.content-type","text/html");
        md.setValue("discovery.edges","{\"source\":\""+URL+"\",\"target\":\""+URL+"b\",\"result\":\"ADMITTED\"}");
        parse.get(URL).setMetadata(md);
        filter.filter(URL,"<html><title>Owned</title><body>marker</body></html>".getBytes(StandardCharsets.UTF_8),null,parse);
        assertEquals("true",md.getFirstValue("archive.committed"));
        try(var store=new ArchiveStore(root)) {
            var page=store.page(md.getFirstValue("archive.page.id"));
            assertEquals("Owned",page.get("title"));assertEquals(1,((List<?>)page.get("links")).size());
        }
    }
    @Test public void catalogFailureThrowsInsteadOfMarkingFetchedSuccess() throws Exception {
        Path root=Files.createTempDirectory("archive-failure-filter");String job;
        try(var store=new ArchiveStore(root)){job=(String)store.createPlan(List.of(URL),0,1,"user").get("plan_id");store.approvePlan(job);}
        var filter=new ArchiveParseFilter();filter.configure(conf(root,job),new ObjectMapper().createObjectNode(),"Archive");
        var parse=new ParseResult(List.of());var md=new Metadata();
        md.setValue("fetch.statusCode","200");md.setValue("protocol.content-type","text/html");md.setValue("discovery.depth","1");parse.get(URL).setMetadata(md);
        assertThrows(IllegalStateException.class,()->filter.filter(URL,"<html>deep</html>".getBytes(StandardCharsets.UTF_8),null,parse));
        assertNull(md.getFirstValue("archive.committed"));
        try(var store=new ArchiveStore(root)){assertEquals(0L,store.job(job).get("archived_pages"));}
    }
    @Test public void installedSchedulerTerminalOutcomeRemainsInCatalog() throws Exception {
        Path root=Files.createTempDirectory("archive-terminal-outcome");String job;
        try(var store=new ArchiveStore(root)){job=(String)store.createPlan(List.of(URL),0,1,"user").get("plan_id");store.approvePlan(job);}
        final String approved=job;
        class Sink extends org.apache.stormcrawler.persistence.AbstractStatusUpdaterBolt {
            Metadata saved;
            Sink() {
                Map<String,Object> options=new HashMap<>();
                options.put("scheduler.class","org.apache.stormcrawler.persistence.DefaultScheduler");
                options.put("status.updater.use.cache",false);
                options.put("max.fetch.errors",3);options.put("fetchInterval.fetch.error",120);options.put("fetchInterval.error",-1);
                options.put("metadata.persist",List.of("error.cause","fetch.exception"));
                var out=(org.apache.storm.task.IOutputCollector)java.lang.reflect.Proxy.newProxyInstance(
                    getClass().getClassLoader(),new Class<?>[]{org.apache.storm.task.IOutputCollector.class},
                    (proxy,method,args)->{if(method.getName().equals("emit"))return List.of();throw new AssertionError(method.getName());});
                prepare(options,null,new org.apache.storm.task.OutputCollector(out));
            }
            protected void store(String url,org.apache.stormcrawler.persistence.Status status,Metadata md,Optional<java.util.Date> next,org.apache.storm.tuple.Tuple tuple) {
                try{ArchiveStatusUpdaterBolt.recordOutcome(root,approved,url,status,md,next);saved=md;}
                catch(java.io.IOException e){throw new java.io.UncheckedIOException(e);}
            }
        }
        Metadata input=new Metadata();
        for(int i=0;i<3;i++) {
            Sink sink=new Sink();final Metadata current=input;
            var tuple=(org.apache.storm.tuple.Tuple)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{org.apache.storm.tuple.Tuple.class},(proxy,method,args)->{
                    if(method.getName().equals("getStringByField"))return URL;
                    if(method.getName().equals("getValueByField"))return args[0].equals("status")?org.apache.stormcrawler.persistence.Status.FETCH_ERROR:current;
                    throw new AssertionError(method.getName());});
            sink.execute(tuple);input=sink.saved;
        }
        try(var store=new ArchiveStore(root)) {
            var failures=(List<?>)store.job(job).get("failure_records");
            assertEquals(2,failures.size());
            assertTrue(failures.stream().anyMatch(row->"TERMINAL_MAX_FETCH_ERRORS".equals(((Map<?,?>)row).get("category"))));
            var history=store.failureHistory(job,null,100);
            assertEquals(3L,history.get("observation_count"));
            assertEquals(true,((Map<?,?>)((List<?>)history.get("rows")).getLast()).get("terminal"));
            assertNull(history.get("fetch_attempt_count"));
        }
    }

}
