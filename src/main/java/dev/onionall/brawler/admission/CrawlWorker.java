package dev.onionall.brawler.admission;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.*;
import java.util.Arrays;
import java.util.Map;
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
        try(FileChannel owner=FileChannel.open(lockPath,StandardOpenOption.CREATE,StandardOpenOption.WRITE);
            FileLock lock=owner.tryLock()) {
            if(lock==null) throw new IOException("A bounded worker still owns this archive");
            Process worker=new ProcessBuilder(Arrays.copyOfRange(args,3,args.length)).inheritIO().start();
            Thread shutdown=new Thread(()->stop(worker),"onionall-worker-stop");
            Runtime.getRuntime().addShutdownHook(shutdown);
            int exit;
            try {
                // Unique marker is published only after both OS lock and actual child exist.
                Path partial=Files.createTempFile(ready.getParent(),".ready-",".tmp");
                try {
                    Files.write(partial,new ObjectMapper().writeValueAsBytes(Map.of("owner_pid",ProcessHandle.current().pid(),"worker_pid",worker.pid())));
                    Files.move(partial,ready,StandardCopyOption.ATOMIC_MOVE);
                } finally {Files.deleteIfExists(partial);}
                if(!worker.waitFor(seconds,TimeUnit.SECONDS)) { stop(worker);exit=124; }
                else exit=worker.exitValue();
            } finally {
                stop(worker);
                Runtime.getRuntime().removeShutdownHook(shutdown);
            }
            if(exit!=0) System.exit(exit);
        }
    }
    /** A later start probes this lock before any admission or worker launch. */
    static void requireAvailable(Path path) throws IOException {
        try(FileChannel channel=FileChannel.open(path,StandardOpenOption.CREATE,StandardOpenOption.WRITE);
            FileLock lock=channel.tryLock()) {
            if(lock==null) throw new IOException("A bounded worker is still active; wait for its deadline before restarting");
        } catch(java.nio.channels.OverlappingFileLockException e) { throw new IOException("A bounded worker is still active",e); }
    }
    private static synchronized void stop(Process worker) {
        var descendants=worker.descendants().toList();
        descendants.forEach(ProcessHandle::destroy);worker.destroy();
        try {
            if(!worker.waitFor(5,TimeUnit.SECONDS)) worker.destroyForcibly();
            descendants.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
            worker.waitFor(5,TimeUnit.SECONDS);
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while(descendants.stream().anyMatch(ProcessHandle::isAlive) && System.nanoTime()<until) Thread.sleep(25);
        } catch(InterruptedException e) {
            descendants.forEach(ProcessHandle::destroyForcibly);worker.destroyForcibly();Thread.currentThread().interrupt();
        }
    }
}
