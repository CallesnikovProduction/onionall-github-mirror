package dev.onionall.brawler.admission;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.onionall.brawler.archive.ArchiveStore;
import dev.onionall.brawler.crawl.JobStatusService;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static dev.onionall.brawler.admission.AdmissionClient.fromRuntime;
import static dev.onionall.brawler.admission.FrontierCapability.token;
import static dev.onionall.brawler.crawl.RuntimeDescriptor.*;

/** Local application orchestration. Frontier owns scheduling; the catalog is readback only.
 * Runtime descriptors are operator supplied deployment inputs, never fetched metadata.
 */
public final class CrawlExecution {

    /**
     * Общий объект Jackson для сериализации и десереализации JSON
     */
    private static final ObjectMapper EXECUTION_JSON = new ObjectMapper();

    /**
     * <b>Получение и сохранение актуального состояния crawl-задания.</b>
     *
     * <p>Объединяет архивные данные, наблюдение локального worker
     * и проверенные сведения Frontier. Формирует итоговый статус,
     * сохраняет его в архиве и возвращает расширенную карту задания.
     *
     * @param archive путь к архиву заданий
     * @param id идентификатор crawl-задания
     * @param runtimeFile путь к runtime-конфигурации или {@code null}
     * @param tokenFile путь к admission capability-токену
     * @return карта задания с итоговым статусом и наблюдениями
     * @throws IOException при ошибках чтения, записи или конфигурации
     */

    public static Map<String,Object> status(
            Path archive,
            String id,
            Path runtimeFile,
            Path tokenFile
    ) throws IOException {
        return JobStatusService.reconcileStatus(archive, id, runtimeFile, tokenFile);
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
        try(FileChannel owner=FileChannel.open(archive.toAbsolutePath().normalize().resolve("crawl-start.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);
            FileLock lock=owner.tryLock()) {
            if(lock==null) throw new IOException("This archive already has an active local crawl start");
            Path runtimeWorkerLock=archive.toAbsolutePath().normalize().resolve("crawl-worker.lock");
            if(!Files.exists(WorkerOwnership.marker(runtimeWorkerLock),LinkOption.NOFOLLOW_LINKS)) {
                try(var store=new ArchiveStore(archive)) {
                    if(store.hasExecutionHistory())throw WorkerOwnership.blocked("legacy execution history lacks archive cleanup proof");
                }
            }
            try(var preparation=new WorkerOwnership.Preparation(runtimeWorkerLock,id)) {
            Files.createDirectories(work);
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
            try(var rpc= fromRuntime(runtime,capability)) {
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
            ProcessBuilder launcher=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),
                "-cp",absoluteClasspath(),CrawlWorker.class.getName(),runtimeWorkerLock.toString(),ready.toString(),Integer.toString(seconds+90),
                storm.toString(),"local","-c","storm.log.dir="+work.resolve("logs"),"-c","storm.local.dir="+work.resolve("storm-local"),
                "-c","dev.zookeeper.path="+work.resolve("zookeeper"),jobJar.toString(),"org.apache.storm.flux.Flux",topology.toString(),"--local-ttl",Integer.toString(seconds))
                .directory(work.toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
            launcher.environment().put(WorkerOwnership.HANDOFF,preparation.handoff());
            Process worker=launcher.start();
            Instant workerIdentity=worker.info().startInstant().orElse(null);
            Thread stopping=new Thread(()->stop(worker,workerIdentity),"onionall-cli-worker-stop");
            Runtime.getRuntime().addShutdownHook(stopping);
            long readyDeadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
            try {
                while(!Files.exists(ready) && worker.isAlive() && System.nanoTime()<readyDeadline) Thread.sleep(25);
            } catch(InterruptedException e) {stop(worker,workerIdentity);Thread.currentThread().interrupt();throw new IOException("Worker startup interrupted",e);}
            if(!Files.exists(ready)) { stop(worker,workerIdentity);throw new IOException("Bounded worker could not acquire execution ownership; inspect "+log); }
            Map<String,Object> readiness= EXECUTION_JSON.readValue(Files.readAllBytes(ready),new TypeReference<Map<String,Object>>(){});
            if(!(readiness.get("owner_pid") instanceof Number ownerPid) || ownerPid.longValue()!=worker.pid()) {
                stop(worker,workerIdentity);throw new IOException("Worker ownership readback mismatch");
            }
            var result=new LinkedHashMap<String,Object>();result.put("plan_id",id);result.put("admission","COMMITTED");
            result.put("authoritative_admission",session);result.put("worker_pid",readiness.get("worker_pid"));result.put("worker_owner_pid",worker.pid());result.put("worker_log",log.toString());
            result.put("process_scope",WorkerObservation.scope());
            result.put("worker_owner_start_instant",workerIdentity==null?null:workerIdentity.toString());
            result.put("worker_start_instant",readiness.get("worker_start_instant"));
            result.put("started_at",Instant.now().toString());result.put("state","RUNNING");
            // Do not reopen embedded H2 while the live worker can own a callback connection.
            write(work.resolve("execution.json"),result);
            try {
                if(!worker.waitFor(seconds+115L,TimeUnit.SECONDS)) {
                    stop(worker,workerIdentity);
                    result.put("state","WORKER_TIMED_OUT");result.put("passed",false);
                    write(work.resolve("execution.json"),result);
                    try(var store=new ArchiveStore(archive)) { store.recordAdmission(id,result); }
                    throw new IOException("Worker exceeded bounded execution window; admitted work remains in Frontier");
                }
            } catch(InterruptedException e) { stop(worker,workerIdentity);Thread.currentThread().interrupt();throw new IOException("Crawl execution interrupted; resume the same plan",e); }
            finally { Runtime.getRuntime().removeShutdownHook(stopping); }
            result.put("worker_exit_code",worker.exitValue());result.put("state",worker.exitValue()==0?"WORKER_EXITED":"WORKER_FAILED");
            // Exit0 is not a crawl-completion assertion. Pending work/failures are read from owning systems.
            result.put("passed",worker.exitValue()==0);result.put("crawl_completed_verified",false);
            try(var rpc= fromRuntime(runtime,capability)) { result.put("authoritative_admission",rpc.command(Map.of("action","session","session_id",id))); }
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
    }

    private static String absoluteClasspath() {
        return Arrays
                .stream(System.getProperty("java.class.path")
                        .split(java.util.regex.Pattern.quote(java.io.File.pathSeparator)))
                .map(entry ->
                        Path.of(entry.isEmpty() ? "." : entry)
                                .toAbsolutePath()
                                .normalize()
                                .toString()
                )
                .collect(java.util.stream.Collectors.joining(java.io.File.pathSeparator));
    }

    public static void verifySession(Map<String,Object> session,Map<String,Object> plan) throws IOException {
        if(!(session.get("spec") instanceof Map<?,?> spec) ||
           !Objects.equals(spec.get("session_id"),plan.get("plan_id")) ||
           !Objects.equals(spec.get("origin"),plan.get("origin")) ||
           !(spec.get("seeds") instanceof List<?> actualSeeds) || !(plan.get("seeds") instanceof List<?> expectedSeeds) ||
           !new HashSet<>(actualSeeds).equals(new HashSet<>(expectedSeeds)) ||
           !(spec.get("max_pages") instanceof Number pages) || pages.intValue()!=((Number)plan.get("max_pages")).intValue() ||
           !(spec.get("max_depth") instanceof Number depth) || depth.intValue()!=((Number)plan.get("depth")).intValue() ||
           !Boolean.TRUE.equals(spec.get("approved"))) throw new IOException("Authoritative session differs from approved plan");
    }

    private static boolean matches(
            ProcessHandle process,
            Instant start
    ) {
        return start != null &&
                process.isAlive() &&
                process
                        .info()
                        .startInstant()
                        .filter(start::equals)
                        .isPresent();
    }

    private static void signal(
            ProcessHandle process,
            Instant start,
            boolean force
    ) {
        if (matches(process, start)) {
            if (force)
                process.destroyForcibly();
            else
                process.destroy();
        }
    }

    /** Cleanup only a launch-pinned wrapper and its identified, directly observed descendants. */
    static void stop(Process worker,Instant identity) {
        if(!worker.isAlive()||!matches(worker.toHandle(),identity))return;
        var descendants=worker.descendants().map(p->new AbstractMap.SimpleImmutableEntry<>(p,p.info().startInstant().orElse(null)))
            .filter(p->p.getValue()!=null).toList();
        if(!worker.isAlive()||!matches(worker.toHandle(),identity))return;
        descendants.forEach(p->signal(p.getKey(),p.getValue(),false));
        signal(worker.toHandle(),identity,false);
        try {if(!worker.waitFor(10,TimeUnit.SECONDS)) {
            descendants.forEach(p->signal(p.getKey(),p.getValue(),true));signal(worker.toHandle(),identity,true);worker.waitFor(10,TimeUnit.SECONDS);
        }}catch(InterruptedException e) {
            descendants.forEach(p->signal(p.getKey(),p.getValue(),true));signal(worker.toHandle(),identity,true);Thread.currentThread().interrupt();
        }
        // This caller never publishes STOPPED: only the owning wrapper can attest cleanup.
    }
    private static void write(
            Path path,
            Object value
    ) throws IOException {
        Path tmp = Files.createTempFile(
                path.getParent(),
                ".runtime-",
                ".tmp"
        );

        try {
            Files.write(
                    tmp,
                    EXECUTION_JSON.writeValueAsBytes(value)
            );
            Files.move(
                    tmp,
                    path,StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
            );
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
    private static Map<String, Object> configuration(
            Map<String, Object> plan,
            Map<String, Object> runtime,
            Path archive,
            Path tokenFile,
            Path work,
            int timeout,
            int retry
    ) throws IOException {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("topology.workers", 1);
        c.put("topology.message.timeout.secs", 300);
        c.put("topology.max.spout.pending", 10);

        c.put(
                "topology.kryo.register",
                List.of("org.apache.stormcrawler.Metadata", "org.apache.stormcrawler.persistence.Status")
        );
        c.put(
                "storm.log.dir",
                work.resolve("logs").toString()
        );
        c.put(
                "storm.local.dir",
                work.resolve("storm-local").toString()
        );
        c.put(
                "dev.zookeeper.path",
                work.resolve("zookeeper").toString()
        );
        c.put(
                "urlfrontier.host",
                text(runtime,"frontier_host")
        );
        c.put(
                "urlfrontier.port",
                number(runtime,"frontier_port",7071,65535)
        );
        c.put(
                "urlfrontier.crawlid",
                plan.get("plan_id")
        );
        c.put("urlfrontier.max.buckets", 1);
        c.put("urlfrontier.max.urls.per.bucket", 2);
        c.put("urlfrontier.max.messages.in.flight", 10);
        c.put("urlfrontier.delay.requestable", 300);
        c.put("fetcher.threads.number", 1);
        c.put("fetcher.threads.per.queue", 1);
        c.put("fetcher.server.delay", 2.0);
        c.put("fetcher.server.delay.force", true);
        c.put("fetcher.max.throttle.sleep", -1);
        c.put(
                "http.protocol.implementation",
                "dev.onionall.brawler.tor.TorProtocol"
        );
        c.put(
                "https.protocol.implementation",
                "dev.onionall.brawler.tor.TorProtocol"
        );
        c.put("http.timeout", timeout);
        c.put("http.content.limit", 131072);
        c.put(
                "http.agent.name",
                "OnionallBrawler"
        );
        c.put("http.robots.file.skip", false);
        c.put("http.allow.redirects", false);
        c.put("http.trust.everything", false);
        c.put("tor.mode","ONLY");
        c.put(
                "tor.socks.host",
                text(runtime,"socks_host")
        );
        c.put(
                "tor.socks.port",
                number(runtime,"socks_port",9050,65535)
        );
        c.put(
                "tor.allowed.hosts",
                List.of(java.net.URI.create((String) plan.get("origin")).getHost())
        );
        c.put("tor.discovery.html.only", true);
        c.put("protocol.md.prefix","protocol.");
        c.put(
                "metadata.persist",
                List.of(
                        "discovery.*",
                        "fetch.exception",
                        "fetch.statusCode",
                        "protocol.tor.category",
                        "error.cause",
                        "error.source"
                )
        );
        c.put(
                "metadata.transfer",
                List.of()
        );
        c.put("parser.emitOutlinks.max.per.page", 100);
        c.put("sitemap.discovery", false);
        c.put("fetchInterval.default", 1440);
        c.put("fetchInterval.fetch.error", retry);
        c.put("fetchInterval.error", -1);
        c.put("max.fetch.errors", 3);
        c.put(
                "parsefilters.config.file",
                "onionall-job/parsefilters.json"
        );
        c.put(
                "urlfilters.config.file",
                "onionall-job/urlfilters.json"
        );
        c.put(
                "archive.root",
                archive.toString());
        c.put(
                "archive.job.id",
                plan.get("plan_id")
        );
        c.put(
                "discovery.budget.mode",
                "durable-v1"
        );
        c.put(
                "admission.host",
                text(runtime,"frontier_host")
        );
        c.put(
                "admission.port",
                number(runtime,"frontier_port",7071,65535)
        );
        c.put(
                "admission.token.file",
                tokenFile.toString()
        );
        c.put(
                "admission.session.id",
                plan.get("plan_id")
        );

        return c;
    }

    private static Map<String, Object> topology(Path config) {
        return CrawlTopology.build(config);
    }

    private CrawlExecution() { // No instances
    }
}
