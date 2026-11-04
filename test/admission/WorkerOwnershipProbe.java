package dev.onionall.brawler.admission;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/** Test-only fixed fixture archive. Never deployed; never adopts or signals unknown PIDs. */
public final class WorkerOwnershipProbe {
    private static void require(boolean condition,String message) {if(!condition)throw new IllegalStateException(message);}
    public static void main(String[] args)throws Exception {
        require(args.length==4&&Set.of("inspect","kill-owner","wait-child","guard").contains(args[0]),"fixture action/job/run/output required");
        require(UUID.fromString(args[1]).toString().equals(args[1])&&UUID.fromString(args[2]).toString().equals(args[2]),"canonical fixture identities required");
        require(args[3].matches("[a-z-]+\\.json"),"safe fixture output required");
        Path archive=Path.of("/evidence/archive"),lock=archive.resolve("crawl-worker.lock");
        Map<String,Object> marker=WorkerOwnership.read(lock);
        require(args[1].equals(marker.get("plan_id"))&&args[2].equals(marker.get("run_id")),"fixture ownership changed");
        long owner=((Number)marker.get("owner_pid")).longValue();
        long child=((Number)marker.get("child_pid")).longValue();
        Object ownerStart=marker.get("owner_start"),childStart=marker.get("child_start");
        if(args[0].equals("kill-owner")) {
            require(Boolean.TRUE.equals(WorkerObservation.alive(owner,ownerStart))&&Boolean.TRUE.equals(WorkerObservation.alive(child,childStart)),"both fixture processes must be pinned/live");
            ProcessHandle process=ProcessHandle.of(owner).orElseThrow();
            require(process.info().startInstant().map(Instant::toString).filter(ownerStart::equals).isPresent(),"owner birth changed");
            require(Arrays.asList(process.info().arguments().orElse(new String[0])).contains(CrawlWorker.class.getName()),"not fixture wrapper");
            require(process.pid()!=ProcessHandle.current().pid(),"never kill probe");
            require(process.destroyForcibly(),"owned wrapper signal rejected");process.onExit().get(5,TimeUnit.SECONDS);
        }
        if(args[0].equals("wait-child")) {
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(150);
            Boolean alive;
            do {
                alive=WorkerObservation.alive(child,childStart);
                require(alive!=null,"child identity became UNKNOWN; no signal/adoption");
                if(Boolean.FALSE.equals(alive))break;
                Thread.sleep(100);
            }while(System.nanoTime()<until);
            require(Boolean.FALSE.equals(alive),"bounded Storm child did not exit; retain marker/fixture evidence");
        }
        Map<String,Object> output=new LinkedHashMap<>(WorkerObservation.read(archive.resolve("unused-execution.json"),archive,args[1]).details());
        output.put("marker_phase",marker.get("phase"));output.put("run_id",args[2]);
        boolean storm=false;
        if(Boolean.TRUE.equals(WorkerObservation.alive(child,childStart))) {
            ProcessHandle process=ProcessHandle.of(child).orElseThrow();
            storm=Stream.concat(Stream.of(process),process.descendants()).anyMatch(p->Arrays.stream(p.info().arguments().orElse(new String[0]))
                .anyMatch(a->a.equals("org.apache.storm.LocalCluster")||a.equals("org.apache.storm.flux.Flux")));
        }
        output.put("storm_runtime_present",storm);
        if(args[0].equals("guard")) {
            try {CrawlWorker.requireAvailable(lock);throw new IllegalStateException("unsafe guard accepted fixture restart");}
            catch(java.io.IOException blocked){require(blocked.getMessage().contains("BLOCKED"),"unexpected ownership IO failure");output.put("guard_blocked",true);}
        }
        Files.write(Path.of("/evidence",args[3]),new ObjectMapper().writeValueAsBytes(output),StandardOpenOption.CREATE_NEW);
    }
}
