package dev.onionall.brawler.admission;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.onionall.brawler.archive.ArchiveStore;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Local application orchestration. Frontier owns scheduling; the catalog is readback only.
 * Runtime descriptors are operator supplied deployment inputs, never fetched metadata.
 */
public final class CrawlExecution {
    private static final ObjectMapper JSON = new ObjectMapper();
    private CrawlExecution() { }
    private static Map<String,Object> descriptor(Path file) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file)>32768)
            throw new IOException("Require regular runtime JSON descriptor <=32KiB");
        return JSON.readValue(Files.readAllBytes(file),new TypeReference<Map<String,Object>>(){});
    }
    private static String text(Map<String,Object> value,String key) throws IOException {
        Object field=value.get(key); if (!(field instanceof String s) || s.isBlank() || s.length()>4096)
            throw new IOException("Missing runtime " + key); return s;
    }
    private static int number(Map<String,Object> value,String key,int fallback,int max) throws IOException {
        Object n=value.get(key); int result=n==null?fallback:n instanceof Number?((Number)n).intValue():-1;
        if(result<1 || result>max || n instanceof Number && ((Number)n).doubleValue()!=result)
            throw new IOException("Invalid runtime " + key); return result;
    }
    public static String token(Path file) throws IOException {
        if(file==null || !Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS) || Files.size(file)>257)
            throw new IOException("Require regular admission capability file <=257 bytes");
        String token=Files.readString(file).strip();
        if(token.length()<32 || token.length()>256 || !token.matches("[A-Za-z0-9_-]+")) throw new IOException("Invalid admission capability");
        return token;
    }
    private static AdmissionClient client(Map<String,Object> runtime,String token) throws IOException {
        return new AdmissionClient(text(runtime,"frontier_host"),number(runtime,"frontier_port",7071,65535),token);
    }
    public static Map<String,Object> status(Path archive,String id,Path runtimeFile,Path tokenFile) throws IOException {
        Map<String,Object> result;
        try(var store=new ArchiveStore(archive)) { result=new LinkedHashMap<>(store.job(id)); }
        String catalogAt=Instant.now().toString();
        WorkerObservation.Observation worker=WorkerObservation.unavailable("NO_RUNTIME_OBSERVATION");
        FrontierReadback.Counts counts=null;Boolean reachable=null;
        if(runtimeFile!=null) {
            Map<String,Object> runtime=descriptor(runtimeFile);
            if(runtime.get("work_directory") instanceof String directory && !directory.isBlank() && directory.length()<=4096)
                worker=WorkerObservation.read(Path.of(directory).toAbsolutePath().normalize().resolve(id).resolve("execution.json"),archive,id);
            Map<String,Object> session=null;
            try(var rpc=client(runtime,token(tokenFile))) {
                session=rpc.command(Map.of("action","session","session_id",id));reachable=true;
                if(!"SESSION_OPEN".equals(session.get("outcome"))) throw new IOException("Authoritative session unavailable: "+session.get("outcome"));
                if(!Boolean.TRUE.equals(result.get("approved"))) throw new IOException("Stored plan is not approved");
                verifySession(session,result);
                if(!(session.get("reserved_count") instanceof Integer || session.get("reserved_count") instanceof Long))
                    throw new IOException("Invalid authoritative reservation count");
                long sessionReserved=((Number)session.get("reserved_count")).longValue();
                if(sessionReserved<0 || sessionReserved>((Number)result.get("max_pages")).intValue())
                    throw new IOException("Invalid authoritative reservation count");
                counts=rpc.counts(id);
                if(counts.reserved()>((Number)result.get("max_pages")).intValue()
                    || counts.reserved()<sessionReserved) throw new IOException("Inconsistent Frontier readback");
            } catch(IOException e) {
                counts=null;
                if(e.getCause() instanceof io.grpc.StatusRuntimeException rpcFailure) {
                    var code=rpcFailure.getStatus().getCode();
                    if(code==io.grpc.Status.Code.UNAVAILABLE) reachable=Boolean.FALSE;
                    else if(code==io.grpc.Status.Code.DEADLINE_EXCEEDED) reachable=null;
                    else reachable=Boolean.TRUE;
                }
            }
            if(counts!=null) {
                result.put("authoritative_admission",session);result.put("admission","SERVER_READBACK");
                try(var store=new ArchiveStore(archive)) {
                    var observation=new LinkedHashMap<String,Object>(store.admissionObservation(id));
                    observation.putIfAbsent("state","ADMITTED");observation.put("admission","COMMITTED");
                    observation.put("authoritative_admission",session);observation.put("session_id",id);observation.put("observed_at",Instant.now().toString());
                    store.recordAdmission(id,observation);
                    result.put("state",observation.get("state"));
                }
            }
        }
        Map<String,Object> view=JobStatusView.assemble(result,counts,worker,reachable,runtimeFile!=null,catalogAt);
        try(var store=new ArchiveStore(archive)) {
            store.recordJobStatus(id,view);
            Map<String,Object> recorded=store.admissionObservation(id);
            result.put("status_transitions",recorded.get("status_transitions"));
            result.put("status_transitions_dropped",recorded.get("status_transitions_dropped"));
        }
        result.put("job_status",view);
        result.putAll(view);
        return result;
    }
    public static Map<String,Object> start(Path archive,String id,Path runtimeFile,Path tokenFile) throws Exception {
        Map<String,Object> plan;
        try(var store=new ArchiveStore(archive)) { plan=store.plan(id); }
        if(!Boolean.TRUE.equals(plan.get("approved"))) throw new IOException("Explicit plan approval is required before admission");
        Map<String,Object> runtime=descriptor(runtimeFile);
        Path storm=Path.of(text(runtime,"storm_executable")).toAbsolutePath();
        Path jar=Path.of(text(runtime,"crawler_jar")).toAbsolutePath();
        Path work=Path.of(text(runtime,"work_directory")).toAbsolutePath().normalize().resolve(id);
        int seconds=number(runtime,"seconds",120,600);
        int httpTimeout=number(runtime,"http_timeout_ms",20000,30000);
        int retryMinutes=number(runtime,"retry_minutes",120,120);
        if(!Files.isRegularFile(storm) || !Files.isExecutable(storm) || !Files.isRegularFile(jar,LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Runtime requires installed Storm executable and full crawler JAR");
        String capability=token(tokenFile);
        Files.createDirectories(work);
        try(FileChannel owner=FileChannel.open(archive.toAbsolutePath().normalize().resolve("crawl-start.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);
            FileLock lock=owner.tryLock()) {
            if(lock==null) throw new IOException("This archive already has an active local crawl start");
            Path runtimeWorkerLock=archive.toAbsolutePath().normalize().resolve("crawl-worker.lock");
            CrawlWorker.requireAvailable(runtimeWorkerLock);
            Path configuration=work.resolve("crawler-conf.json");
            Path topology=work.resolve("topology.json");
            write(configuration,Map.of("config",configuration(plan,runtime,archive.toAbsolutePath(),tokenFile.toAbsolutePath(),work,httpTimeout,retryMinutes)));
            write(work.resolve("parsefilters.json"),Map.of("org.apache.stormcrawler.parse.ParseFilters",List.of(
                Map.of("class","dev.onionall.brawler.admission.DurableDiscoveryFilter","name","DurableAdmission","params",Map.of()),
                Map.of("class","dev.onionall.brawler.archive.ArchiveParseFilter","name","Archive","params",Map.of()))));
            write(work.resolve("urlfilters.json"),Map.of("org.apache.stormcrawler.filtering.URLFilters",List.of()));
            write(topology,topology(configuration));
            Path jobJar=work.resolve("worker-runtime-"+UUID.randomUUID()+".jar");
            CrawlResources.publish(jar,jobJar,Map.of(
                "onionall-job/parsefilters.json",Files.readAllBytes(work.resolve("parsefilters.json")),
                "onionall-job/urlfilters.json",Files.readAllBytes(work.resolve("urlfilters.json"))));
            Map<String,Object> session;
            try(var rpc=client(runtime,capability)) {
                var open=new LinkedHashMap<String,Object>();
                open.put("action","open");open.put("session_id",id);open.put("origin",plan.get("origin"));
                open.put("seeds",plan.get("seeds"));open.put("max_pages",plan.get("max_pages"));
                open.put("max_depth",plan.get("depth"));open.put("approved",true);
                var opened=rpc.command(open);
                if(!"SESSION_OPEN".equals(opened.get("outcome"))) throw new IOException("Authoritative session refused: "+opened.get("outcome"));
                verifySession(opened,plan);
                for(Object seed:(List<?>)plan.get("seeds")) {
                    var reservation=rpc.command(Map.of("action","reserve","session_id",id,"url",seed,"source_url","","depth",0));
                    if(!Set.of("RESERVED","ALREADY_RESERVED").contains(reservation.get("outcome")))
                        throw new IOException("Authoritative seed admission refused: "+reservation.get("outcome"));
                    var verified=rpc.command(Map.of("action","reservation","session_id",id,"url",seed));
                    if(!Objects.equals(reservation.get("reservation_id"),verified.get("reservation_id")) || verified.get("reservation_id")==null)
                        throw new IOException("Committed admission readback mismatch");
                }
                session=rpc.command(Map.of("action","session","session_id",id));
                verifySession(session,plan);
            }
            // This checkpoint can be reconstructed by the identical session readback after any crash.
            write(work.resolve("admission-observation.json"),session);
            try(var store=new ArchiveStore(archive)) { store.recordAdmission(id,Map.of("admission","COMMITTED","state","ADMITTED","session_id",id,"authoritative_admission",session,"runtime",runtimeFile.toAbsolutePath().toString(),"observed_at",Instant.now().toString())); }
            Path log=work.resolve("worker-"+UUID.randomUUID()+".log");
            Path ready=work.resolve("worker-ready-"+UUID.randomUUID()+".json");
            Process worker=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),
                "-cp",absoluteClasspath(),CrawlWorker.class.getName(),runtimeWorkerLock.toString(),ready.toString(),Integer.toString(seconds+90),
                storm.toString(),"local","-c","storm.log.dir="+work.resolve("logs"),"-c","storm.local.dir="+work.resolve("storm-local"),
                "-c","dev.zookeeper.path="+work.resolve("zookeeper"),jobJar.toString(),"org.apache.storm.flux.Flux",topology.toString(),"--local-ttl",Integer.toString(seconds))
                .directory(work.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
            Thread stopping=new Thread(()->stop(worker),"onionall-cli-worker-stop");
            Runtime.getRuntime().addShutdownHook(stopping);
            long readyDeadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
            try {
                while(!Files.exists(ready) && worker.isAlive() && System.nanoTime()<readyDeadline) Thread.sleep(25);
            } catch(InterruptedException e) {stop(worker);Thread.currentThread().interrupt();throw new IOException("Worker startup interrupted",e);}
            if(!Files.exists(ready)) { stop(worker);throw new IOException("Bounded worker could not acquire execution ownership; inspect "+log); }
            Map<String,Object> readiness=JSON.readValue(Files.readAllBytes(ready),new TypeReference<Map<String,Object>>(){});
            if(!(readiness.get("owner_pid") instanceof Number ownerPid) || ownerPid.longValue()!=worker.pid()) {
                stop(worker);throw new IOException("Worker ownership readback mismatch");
            }
            var result=new LinkedHashMap<String,Object>();result.put("plan_id",id);result.put("admission","COMMITTED");
            result.put("authoritative_admission",session);result.put("worker_pid",readiness.get("worker_pid"));result.put("worker_owner_pid",worker.pid());result.put("worker_log",log.toString());
            result.put("process_scope",WorkerObservation.scope());
            result.put("worker_owner_start_instant",WorkerObservation.start(worker.pid()));
            result.put("worker_start_instant",readiness.get("worker_pid") instanceof Number pid ? WorkerObservation.start(pid.longValue()) : null);
            result.put("started_at",Instant.now().toString());result.put("state","RUNNING");
            // Do not reopen embedded H2 while the live worker can own a callback connection.
            write(work.resolve("execution.json"),result);
            try {
                if(!worker.waitFor(seconds+115L,TimeUnit.SECONDS)) {
                    stop(worker);
                    result.put("state","WORKER_TIMED_OUT");result.put("passed",false);
                    write(work.resolve("execution.json"),result);
                    try(var store=new ArchiveStore(archive)) { store.recordAdmission(id,result); }
                    throw new IOException("Worker exceeded bounded execution window; admitted work remains in Frontier");
                }
            } catch(InterruptedException e) { stop(worker);Thread.currentThread().interrupt();throw new IOException("Crawl execution interrupted; resume the same plan",e); }
            finally { Runtime.getRuntime().removeShutdownHook(stopping); }
            result.put("worker_exit_code",worker.exitValue());result.put("state",worker.exitValue()==0?"WORKER_EXITED":"WORKER_FAILED");
            // Exit0 is not a crawl-completion assertion. Pending work/failures are read from owning systems.
            result.put("passed",worker.exitValue()==0);result.put("crawl_completed_verified",false);
            try(var rpc=client(runtime,capability)) { result.put("authoritative_admission",rpc.command(Map.of("action","session","session_id",id))); }
            catch(IOException e) { result.put("admission_readback_error","AUTHORITATIVE_READBACK_UNAVAILABLE"); }
            write(work.resolve("execution.json"),result);
            try(var store=new ArchiveStore(archive)) { store.recordAdmission(id,result); }
            // Expose the same versioned job view as status; legacy passed/state
            // continue to describe execution only, never crawl completion.
            Map<String,Object> observed=status(archive,id,runtimeFile,tokenFile);
            if(observed.get("job_status") instanceof Map<?,?> view) {
                for(var entry:view.entrySet()) result.put((String)entry.getKey(),entry.getValue());
                result.put("job_status",view);
            }
            write(work.resolve("execution.json"),result);
            return result;
        }
    }
    private static String absoluteClasspath() {
        return Arrays.stream(System.getProperty("java.class.path").split(java.util.regex.Pattern.quote(java.io.File.pathSeparator)))
            .map(entry->Path.of(entry.isEmpty()?".":entry).toAbsolutePath().normalize().toString())
            .collect(java.util.stream.Collectors.joining(java.io.File.pathSeparator));
    }
    private static void verifySession(Map<String,Object> session,Map<String,Object> plan) throws IOException {
        if(!(session.get("spec") instanceof Map<?,?> spec) ||
           !Objects.equals(spec.get("session_id"),plan.get("plan_id")) ||
           !Objects.equals(spec.get("origin"),plan.get("origin")) ||
           !(spec.get("seeds") instanceof List<?> actualSeeds) || !(plan.get("seeds") instanceof List<?> expectedSeeds) ||
           !new HashSet<>(actualSeeds).equals(new HashSet<>(expectedSeeds)) ||
           !(spec.get("max_pages") instanceof Number pages) || pages.intValue()!=((Number)plan.get("max_pages")).intValue() ||
           !(spec.get("max_depth") instanceof Number depth) || depth.intValue()!=((Number)plan.get("depth")).intValue() ||
           !Boolean.TRUE.equals(spec.get("approved"))) throw new IOException("Authoritative session differs from approved plan");
    }
    private static void stop(Process worker) {
        var descendants=worker.descendants().toList();
        descendants.forEach(ProcessHandle::destroy);worker.destroy();
        try { if(!worker.waitFor(10,TimeUnit.SECONDS)) { descendants.forEach(ProcessHandle::destroyForcibly);worker.destroyForcibly();worker.waitFor(10,TimeUnit.SECONDS); } }
        catch(InterruptedException e) { descendants.forEach(ProcessHandle::destroyForcibly);worker.destroyForcibly();Thread.currentThread().interrupt(); }
    }
    private static void write(Path path,Object value) throws IOException {
        Path tmp=Files.createTempFile(path.getParent(),".runtime-",".tmp");
        try {Files.write(tmp,JSON.writeValueAsBytes(value));Files.move(tmp,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
        finally {Files.deleteIfExists(tmp);}
    }
    private static Map<String,Object> configuration(Map<String,Object> plan,Map<String,Object> runtime,Path archive,Path tokenFile,Path work,int timeout,int retry) throws IOException {
        Map<String,Object> c=new LinkedHashMap<>();
        c.put("topology.workers",1);c.put("topology.message.timeout.secs",300);c.put("topology.max.spout.pending",10);
        c.put("topology.kryo.register",List.of("org.apache.stormcrawler.Metadata","org.apache.stormcrawler.persistence.Status"));
        c.put("storm.log.dir",work.resolve("logs").toString());c.put("storm.local.dir",work.resolve("storm-local").toString());
        c.put("dev.zookeeper.path",work.resolve("zookeeper").toString());
        c.put("urlfrontier.host",text(runtime,"frontier_host"));c.put("urlfrontier.port",number(runtime,"frontier_port",7071,65535));
        c.put("urlfrontier.crawlid",plan.get("plan_id"));c.put("urlfrontier.max.buckets",1);c.put("urlfrontier.max.urls.per.bucket",2);
        c.put("urlfrontier.max.messages.in.flight",10);c.put("urlfrontier.delay.requestable",300);
        c.put("fetcher.threads.number",1);c.put("fetcher.threads.per.queue",1);c.put("fetcher.server.delay",2.0);
        c.put("fetcher.server.delay.force",true);c.put("fetcher.max.throttle.sleep",-1);
        c.put("http.protocol.implementation","dev.onionall.brawler.tor.TorProtocol");c.put("https.protocol.implementation","dev.onionall.brawler.tor.TorProtocol");
        c.put("http.timeout",timeout);c.put("http.content.limit",131072);c.put("http.agent.name","OnionallBrawler");
        c.put("http.robots.file.skip",false);c.put("http.allow.redirects",false);c.put("http.trust.everything",false);
        c.put("tor.mode","ONLY");c.put("tor.socks.host",text(runtime,"socks_host"));c.put("tor.socks.port",number(runtime,"socks_port",9050,65535));
        c.put("tor.allowed.hosts",List.of(java.net.URI.create((String)plan.get("origin")).getHost()));c.put("tor.discovery.html.only",true);
        c.put("protocol.md.prefix","protocol.");c.put("metadata.persist",List.of("discovery.*","fetch.exception","fetch.statusCode","protocol.tor.category","error.cause","error.source"));
        c.put("metadata.transfer",List.of());c.put("parser.emitOutlinks.max.per.page",100);c.put("sitemap.discovery",false);
        c.put("fetchInterval.default",1440);c.put("fetchInterval.fetch.error",retry);c.put("fetchInterval.error",-1);c.put("max.fetch.errors",3);
        c.put("parsefilters.config.file","onionall-job/parsefilters.json");c.put("urlfilters.config.file","onionall-job/urlfilters.json");
        c.put("archive.root",archive.toString());c.put("archive.job.id",plan.get("plan_id"));
        c.put("discovery.budget.mode","durable-v1");
        c.put("admission.host",text(runtime,"frontier_host"));c.put("admission.port",number(runtime,"frontier_port",7071,65535));
        c.put("admission.token.file",tokenFile.toString());c.put("admission.session.id",plan.get("plan_id"));return c;
    }
    private static Map<String,Object> topology(Path config) {
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
