package dev.onionall.brawler.admission;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.*;
import java.util.*;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

/** Child-owned execution lease and deadline survive termination of the initiating CLI.
 * This local process guard is not Frontier scheduling or a distributed job lock.
 */
public final class CrawlWorker {
    private CrawlWorker() { }
    public static void main(String[] args) throws Exception {
        if(args.length<4) throw new IllegalArgumentException("worker lock, readiness path, deadline and command required");
        Path lockPath=Path.of(args[0]),ready=Path.of(args[1]);int seconds=Integer.parseInt(args[2]);
        if(seconds<1 || seconds>690 || Files.exists(ready,LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Invalid worker deadline/readiness marker");
        boolean fresh;FileChannel opened;
        try {opened=FileChannel.open(lockPath,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);fresh=true;}
        catch(FileAlreadyExistsException e){opened=FileChannel.open(lockPath,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);fresh=false;}
        try(FileChannel owner=opened;FileLock lock=owner.tryLock()) {
            if(lock==null) throw new IOException("A bounded worker still owns this archive");
            Map<String,Object> ownership=WorkerOwnership.claim(lockPath,fresh,System.getenv(WorkerOwnership.HANDOFF));
            ProcessBuilder launcher=new ProcessBuilder(Arrays.copyOfRange(args,3,args.length)).inheritIO();
            launcher.environment().remove(WorkerOwnership.HANDOFF);
            Process worker=launcher.start();
            OwnedChild child=new OwnedChild(worker,lockPath,ownership);
            Thread shutdown=new Thread(child::finish,"onionall-worker-stop");
            Runtime.getRuntime().addShutdownHook(shutdown);
            int exit;
            try {
                // Unique marker is published only after both OS lock and actual child exist.
                Path partial=Files.createTempFile(ready.getParent(),".ready-",".tmp");
                try {
                    Files.write(partial,new ObjectMapper().writeValueAsBytes(Map.of("owner_pid",ProcessHandle.current().pid(),"worker_pid",worker.pid())));
                    Files.move(partial,ready,StandardCopyOption.ATOMIC_MOVE);
                } finally {Files.deleteIfExists(partial);}
                long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(seconds);
                while(worker.isAlive() && System.nanoTime()<until) {child.observe();worker.waitFor(25,TimeUnit.MILLISECONDS);}
                if(worker.isAlive()) {child.finish();exit=124;}
                else exit=worker.exitValue();
            } finally {
                child.finish();
                try {Runtime.getRuntime().removeShutdownHook(shutdown);}catch(IllegalStateException shuttingDown){ /* hook owns cleanup */ }
            }
            if(exit!=0) System.exit(exit);
        }
    }
    /** Probe never creates legacy files and never equates a released lock with cleanup. */
    static void requireAvailable(Path path) throws IOException { WorkerOwnership.requireAvailable(path); }
    private static final class OwnedChild {
        private final Process process;private final Path lock;private final Map<String,Object> value;
        private final Map<Long,Instant> descendants=new HashMap<>();
        private final Set<Long> unidentified=new HashSet<>();private boolean identities=true,finished;
        OwnedChild(Process process,Path lock,Map<String,Object> value)throws IOException {
            this.process=process;this.lock=lock;this.value=value;
            value.put("phase","RUNNING");value.put("child_created",true);value.put("child_pid",process.pid());
            value.put("child_start",WorkerObservation.start(process.pid()));
            if(value.get("child_start")==null)identities=false;
            observe();WorkerOwnership.publish(lock,value);
        }
        synchronized void observe() {
            if(!process.isAlive())return;
            process.descendants().forEach(p->{var start=p.info().startInstant();
                if(start.isEmpty())unidentified.add(p.pid());else descendants.putIfAbsent(p.pid(),start.get());});
        }
        private boolean alive(long pid,Instant start) {return !Boolean.FALSE.equals(WorkerObservation.alive(pid,start.toString()));}
        private void terminate(boolean force) {
            descendants.forEach((pid,start)->{
                ProcessHandle.of(pid).ifPresent(p->{
                    // Check and act on the SAME Java handle, never look up a PID again after validation.
                    if(p.info().startInstant().filter(start::equals).isPresent()&&p.isAlive()) {
                        if(force)p.destroyForcibly();else p.destroy();
                    }
                });
            });
            // Process is the object returned by our own ProcessBuilder, never a lookup from metadata.
            if(process.isAlive()){if(force)process.destroyForcibly();else process.destroy();}
        }
        private boolean unverifiedProcessPresent() {
            // Missing identity NEVER permits a signal. Only complete PID absence can
            // discharge this observation; a live reused PID conservatively blocks.
            return unidentified.stream().anyMatch(pid->ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
        }
        synchronized void finish() {
            if(finished)return;
            observe();terminate(false);
            boolean stopped=false;
            try {
                if(!process.waitFor(5,TimeUnit.SECONDS))terminate(true);
                terminate(true);process.waitFor(5,TimeUnit.SECONDS);
                long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
                while((unverifiedProcessPresent()||descendants.entrySet().stream().anyMatch(e->alive(e.getKey(),e.getValue())))&&System.nanoTime()<until)Thread.sleep(25);
                stopped=identities&&!unverifiedProcessPresent()&&!process.isAlive()&&descendants.entrySet().stream().noneMatch(e->alive(e.getKey(),e.getValue()));
            } catch(InterruptedException e){Thread.currentThread().interrupt();}
            value.put("phase",stopped?"STOPPED":"UNKNOWN");value.put("cleanup_confirmed",stopped);
            value.put("identities_verified",identities);value.put("observed_descendant_count",descendants.size());value.put("unidentified_descendant_count",unidentified.size());
            if(!process.isAlive())value.put("exit_code",process.exitValue());
            value.put("observed_at",Instant.now().toString());
            try {WorkerOwnership.publish(lock,value);finished=true;}
            catch(IOException e){System.err.println("Worker cleanup proof unavailable; restart remains BLOCKED");}
        }
    }
}
