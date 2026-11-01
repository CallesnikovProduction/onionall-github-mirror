package dev.onionall.brawler.tor;

import java.lang.reflect.Proxy;
import java.util.*;
import org.apache.storm.tuple.Tuple;
import org.apache.storm.task.OutputCollector;
import org.apache.storm.task.IOutputCollector;
import org.apache.stormcrawler.Metadata;
import org.apache.stormcrawler.persistence.AbstractStatusUpdaterBolt;
import org.apache.stormcrawler.persistence.Status;
import org.junit.Test;
import static org.junit.Assert.*;

/** Actual installed status/scheduler contracts; in-memory sink, not a RocksDB test. */
public class FrontierSchedulingTest {
    private static final String URL="http://owned.invalid/pending";
    private static final class Sink extends AbstractStatusUpdaterBolt {
        Status status; Metadata metadata; Optional<Date> date;
        Sink() {
            Map<String,Object> config=new HashMap<>();
            config.put("status.updater.use.cache",false);
            config.put("scheduler.class","org.apache.stormcrawler.persistence.DefaultScheduler");
            config.put("max.fetch.errors",3);
            config.put("fetchInterval.default",1440);
            config.put("fetchInterval.fetch.error",120);
            config.put("fetchInterval.error",-1);
            config.put("metadata.persist",List.of("fetch.exception","fetch.error.count","error.cause"));
            IOutputCollector output=(IOutputCollector)Proxy.newProxyInstance(IOutputCollector.class.getClassLoader(),new Class<?>[]{IOutputCollector.class},(proxy,method,args)->{
                if(method.getName().equals("emit"))return List.of();
                throw new AssertionError("Unexpected collector call: "+method.getName());
            });
            prepare(config,null,new OutputCollector(output));
        }
        protected void store(String url,Status status,Metadata metadata,Optional<Date> date,Tuple tuple) {
            assertEquals(URL,url);this.status=status;this.metadata=metadata;this.date=date;
        }
        void apply(Status status,Metadata metadata) {
            Tuple tuple=(Tuple)Proxy.newProxyInstance(Tuple.class.getClassLoader(),new Class<?>[]{Tuple.class},(proxy,method,args)->{
                if(method.getName().equals("getStringByField"))return URL;
                if(method.getName().equals("getValueByField"))return args[0].equals("status")?status:metadata;
                throw new UnsupportedOperationException(method.getName());
            });
            execute(tuple);
        }
    }
    @Test public void successivePersistedFailuresStopAtThreeAcrossNewUpdaterInstances() {
        Metadata persisted=new Metadata();persisted.setValue("fetch.exception","java.io.IOException");
        for(int attempt=1;attempt<=3;attempt++) {
            Sink updater=new Sink();updater.apply(Status.FETCH_ERROR,persisted);
            if(attempt<3) {
                assertEquals(Status.FETCH_ERROR,updater.status);
                assertEquals(Integer.toString(attempt),updater.metadata.getFirstValue("fetch.error.count"));
                long delay=updater.date.orElseThrow().getTime()-System.currentTimeMillis();
                assertTrue(delay>119*60_000L && delay<=121*60_000L);
            } else {
                assertEquals(Status.ERROR,updater.status);assertFalse(updater.date.isPresent());
            }
            persisted=updater.metadata;
        }
    }
    @Test public void successfulRetryResetsConsecutiveErrorBudget() {
        Metadata failed=new Metadata();failed.setValue("fetch.error.count","2");failed.setValue("error.cause","network");
        Sink updater=new Sink();updater.apply(Status.FETCHED,failed);
        assertEquals(Status.FETCHED,updater.status);
        assertNull(updater.metadata.getFirstValue("fetch.error.count"));
        assertNull(updater.metadata.getFirstValue("error.cause"));
        assertTrue(updater.date.orElseThrow().getTime()>System.currentTimeMillis()+23*60*60_000L);
        Sink restarted=new Sink();restarted.apply(Status.FETCH_ERROR,updater.metadata);
        assertEquals("1",restarted.metadata.getFirstValue("fetch.error.count"));
    }
}
