// SPDX-License-Identifier: Apache-2.0
package crawlercommons.urlfrontier.service.rocksdb;

import com.google.gson.JsonObject;
import crawlercommons.urlfrontier.Urlfrontier.AckMessage;
import java.nio.file.*;
import java.util.Map;
import java.util.concurrent.*;

/** Real DB plus explicit synchronization: no sleeps used as transaction boundaries. */
public final class AdmissionShutdownConformance {
    static final class Probe extends RocksDBService {
        Probe(Path path) { super(Map.of("rocksdb.path",path.toString()),"localhost",7071); }
        boolean stopping() { return isClosing(); }
    }
    static void check(boolean ok,String message) { if(!ok) throw new AssertionError(message); }
    static void await(CountDownLatch latch) {
        try { if(!latch.await(10,TimeUnit.SECONDS)) throw new AssertionError("latch deadline"); }
        catch(InterruptedException e) { Thread.currentThread().interrupt();throw new AssertionError(e); }
    }
    public static void main(String[] args) throws Exception {
        Path db=Path.of(args[0]);Files.createDirectories(db);
        AdmissionConformance.base="http://"+AdmissionConformance.host();
        Probe service=new Probe(db);
        CountDownLatch atCommit=new CountDownLatch(1),release=new CountDownLatch(1);
        ExecutorService pool=Executors.newFixedThreadPool(3);
        try {
            service.admissionCommand(AdmissionConformance.open("shutdown",2,1));
            service.admission.beforeCommit=()->{ atCommit.countDown();await(release); };
            Future<JsonObject> admission=pool.submit(()->service.admissionCommand(AdmissionConformance.reserve("shutdown","/seed","",0)));
            await(atCommit);
            Future<?> closing=pool.submit(()->{service.close();return null;});
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while(!service.stopping() && System.nanoTime()<deadline) Thread.yield();
            check(service.stopping(),"close did not mark service stopping");
            try { closing.get(100,TimeUnit.MILLISECONDS);throw new AssertionError("close freed storage during active transaction"); }
            catch(TimeoutException expected) { }
            Future<?> late=pool.submit(()->{
                try { service.admissionCommand(AdmissionConformance.command("session","shutdown"));throw new AssertionError("late command accepted"); }
                catch(IllegalStateException expected) { }
                check(service.putURLItem(AdmissionConformance.item("shutdown","/unreserved"))==AckMessage.Status.FAIL,"late legacy write accepted");
            });
            late.get(1,TimeUnit.SECONDS);
            release.countDown();
            check(AdmissionConformance.result(admission.get(5,TimeUnit.SECONDS)).equals("RESERVED"),"active commit lost");
            closing.get(5,TimeUnit.SECONDS);
            try { service.admissionCommand(AdmissionConformance.command("session","shutdown"));throw new AssertionError("closed command accepted"); }
            catch(IllegalStateException expected) { }
            service.close(); // terminal cleanup is safe to replay
        } finally {
            release.countDown();pool.shutdownNow();pool.awaitTermination(5,TimeUnit.SECONDS);
            service.close();
        }
        try(Probe reopened=new Probe(db)) {
            AdmissionConformance.noMutation(reopened,"shutdown",1);
            check(AdmissionConformance.dequeue(reopened,"shutdown").size()==1,"committed work missing after reopen");
            check(AdmissionConformance.result(reopened.admissionCommand(AdmissionConformance.reserve("shutdown","/seed","",0))).equals("ALREADY_RESERVED"),"shutdown replay changed admission");
        }
        System.out.println("ADMISSION_SHUTDOWN_PASS active-commit-drain late-command-rejection terminal-cleanup reopen-and-replay");
    }
}
