package dev.onionall.brawler.m2;

import com.fasterxml.jackson.databind.ObjectMapper;
import crawlercommons.urlfrontier.URLFrontierGrpc;
import crawlercommons.urlfrontier.Urlfrontier.*;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import java.lang.reflect.Proxy;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.apache.storm.generated.StormTopology;
import org.apache.storm.metric.api.IMetric;
import org.apache.storm.task.*;
import org.apache.storm.tuple.Tuple;
import org.apache.stormcrawler.Metadata;
import org.apache.stormcrawler.persistence.Status;
import org.apache.stormcrawler.urlfrontier.StatusUpdaterBolt;

/** Characterizes installed scheduler + production updater + RocksDB behavior; no schema migration.
 * Run write before server recreation, verify afterward. The empty terminal metadata is an
 * expected baseline limitation, never proof of a durable terminal-reason guarantee.
 */
public final class TerminalMetadataProbe {
    private static final Path EVIDENCE = Path.of("/evidence");
    private static final String CRAWL = "m2";

    // Only metric registration is replaced. All scheduler, serialization, streaming ACK and
    // persistence operations below use the installed production StatusUpdaterBolt.
    private static final class MetricsContext extends TopologyContext {
        MetricsContext() {
            super(new StormTopology(), Map.of(), Map.of(1,"terminal"),
                Map.of("terminal",List.of(1)), Map.of(), Map.of(), "terminal-probe", null,
                null, 1, 0, List.of(1), Map.of(), Map.of(), new HashMap<>(),
                new HashMap<>(), new AtomicBoolean(), null);
        }
        @Override public <T extends IMetric> T registerMetric(String name, T metric, int seconds) {
            return metric;
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private static URLItem read(ManagedChannel channel, String url) {
        var rows = URLFrontierGrpc.newBlockingStub(channel).withDeadlineAfter(5, TimeUnit.SECONDS)
            .listURLs(ListUrlParams.newBuilder().setCrawlID(CRAWL).setStart(0).setSize(100).build());
        URLItem found = null;
        int count = 0;
        while (rows.hasNext()) {
            URLItem item = rows.next();
            check(++count < 100, "Terminal snapshot exceeded bound");
            URLInfo info = item.hasKnown() ? item.getKnown().getInfo() : item.getDiscovered().getInfo();
            if (url.equals(info.getUrl())) {
                check(found == null, "Duplicate terminal URL");
                found = item;
            }
        }
        check(found != null && found.hasKnown(), "Terminal regression URL missing/not Known");
        return found;
    }

    private static Metadata metadata(URLItem item) {
        Metadata md = new Metadata();
        item.getKnown().getInfo().getMetadataMap().forEach((key,value) ->
            md.setValues(key,value.getValuesList().toArray(String[]::new)));
        return md;
    }

    private static Map<String,Object> row(URLItem item) {
        Map<String,Object> values = new TreeMap<>();
        item.getKnown().getInfo().getMetadataMap().forEach((key,value) ->
            values.put(key,value.getValuesList()));
        return Map.of("refetch",item.getKnown().getRefetchableFromDate(),"metadata",values);
    }

    private static void assertTerminal(URLItem item) {
        check(item.getKnown().getRefetchableFromDate() == 0, "Third error did not terminalize");
        check(item.getKnown().getInfo().getMetadataCount() == 0,
            "Installed server now preserves terminal metadata: review/update characterization explicitly");
    }

    private static void write(ManagedChannel reader, String url, Map<String,Object> result) throws Exception {
        AtomicReference<CountDownLatch> acknowledgement = new AtomicReference<>();
        AtomicReference<Throwable> collectorFailure = new AtomicReference<>();
        IOutputCollector output = (IOutputCollector) Proxy.newProxyInstance(
            IOutputCollector.class.getClassLoader(), new Class<?>[]{IOutputCollector.class},
            (proxy,method,args) -> {
                switch(method.getName()) {
                    case "emit": return List.of();
                    case "ack": acknowledgement.get().countDown(); return null;
                    case "fail":
                        collectorFailure.set(new IllegalStateException("Production status updater failed tuple"));
                        acknowledgement.get().countDown(); return null;
                    case "reportError":
                        collectorFailure.set((Throwable)args[0]);
                        acknowledgement.get().countDown(); return null;
                    default: throw new AssertionError("Unexpected collector method: "+method.getName());
                }
            });
        Map<String,Object> conf = new HashMap<>();
        conf.put("status.updater.use.cache",false);
        conf.put("scheduler.class","org.apache.stormcrawler.persistence.DefaultScheduler");
        conf.put("max.fetch.errors",3);
        conf.put("fetchInterval.default",1440);
        conf.put("fetchInterval.fetch.error",120);
        conf.put("fetchInterval.error",-1);
        conf.put("metadata.persist",List.of("fetch.exception","error.cause","regression.provenance"));
        conf.put("urlfrontier.host","frontier");
        conf.put("urlfrontier.crawlid",CRAWL);
        conf.put("urlfrontier.max.messages.in.flight",1);
        StatusUpdaterBolt updater = new StatusUpdaterBolt();
        updater.prepare(conf,new MetricsContext(),new OutputCollector(output));
        try {
            List<Map<String,Object>> snapshots = new ArrayList<>();
            Metadata persisted = new Metadata();
            persisted.setValue("regression.provenance","third-fetch-error");
            for(int attempt=1; attempt<=3; attempt++) {
                persisted.setValue("fetch.exception","java.io.IOException: CONTROLLED_SOCKS_OUTAGE");
                Metadata input = persisted;
                Tuple tuple = (Tuple) Proxy.newProxyInstance(Tuple.class.getClassLoader(),
                    new Class<?>[]{Tuple.class}, (proxy,method,args) -> {
                        if(method.getName().equals("getStringByField")) return url;
                        if(method.getName().equals("getValueByField"))
                            return args[0].equals("status") ? Status.FETCH_ERROR : input;
                        throw new AssertionError("Unexpected tuple method: "+method.getName());
                    });
                acknowledgement.set(new CountDownLatch(1));
                updater.execute(tuple);
                check(acknowledgement.get().await(15,TimeUnit.SECONDS),"Production result ACK deadline");
                if(collectorFailure.get()!=null) throw new IllegalStateException("Result ACK failed",collectorFailure.get());
                URLItem actual = read(reader,url);
                snapshots.add(row(actual));
                if(attempt < 3) {
                    check(actual.getKnown().getRefetchableFromDate() > System.currentTimeMillis()/1000+119*60,
                        "Fetch-error retry not persisted");
                    Metadata saved = metadata(actual);
                    check(Integer.toString(attempt).equals(saved.getFirstValue("fetch.error.count")),
                        "Consecutive error count not persisted");
                    check("third-fetch-error".equals(saved.getFirstValue("regression.provenance")),
                        "Preterminal provenance missing");
                    persisted = saved;
                } else {
                    check("maxFetchErrors".equals(input.getFirstValue("error.cause")),
                        "Scheduler did not generate exhaustion cause");
                    assertTerminal(actual);
                }
            }
            result.put("attempts",snapshots);
            result.put("production_updater_acknowledged_three_errors",true);
            result.put("generated_terminal_cause","maxFetchErrors");
        } finally {
            updater.cleanup();
            var field = StatusUpdaterBolt.class.getDeclaredField("channel");
            field.setAccessible(true);
            ManagedChannel channel = (ManagedChannel) field.get(updater);
            if (!channel.awaitTermination(5,TimeUnit.SECONDS)) {
                channel.shutdownNow();
                check(channel.awaitTermination(5,TimeUnit.SECONDS),"Updater gRPC channel did not stop");
            }
        }
    }

    public static void main(String[] args) throws Exception {
        check(args.length==1 && Set.of("write","verify").contains(args[0]),"Expected write | verify");
        String host = Files.readString(EVIDENCE.resolve("onion-hostname.txt")).trim();
        check(host.matches("[a-z2-7]{56}\\.onion"),"Invalid controlled host");
        String url = "http://"+host+"/terminal";
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("passed",false);
        result.put("m2_verified",false);
        result.put("durable_terminal_reason_verified",false);
        result.put("operation",args[0]);
        ManagedChannel reader = ManagedChannelBuilder.forAddress("frontier",7071).usePlaintext().build();
        try {
            if(args[0].equals("write")) write(reader,url,result);
            URLItem terminal = read(reader,url);
            assertTerminal(terminal);
            result.put("terminal",row(terminal));
            result.put("baseline_limitation","Known tombstone survives; terminal metadata/cause are discarded by stock Frontier2.4");
            result.put("passed",true);
        } finally {
            reader.shutdownNow();
            check(reader.awaitTermination(5,TimeUnit.SECONDS),"Reader gRPC channel did not stop");
            new ObjectMapper().writerWithDefaultPrettyPrinter()
                .writeValue(EVIDENCE.resolve("terminal-"+args[0]+".json").toFile(),result);
        }
    }
}
