import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.storm.StormTimer;
import org.apache.storm.daemon.nimbus.Nimbus;
import sun.misc.Unsafe;

/** Test-only: real Nimbus + StormTimer; block its dependency lookup to force close overlap. */
public final class NimbusCleanupShutdownProbe {
    public static void main(String[] args) throws Exception {
        boolean legacy=args.length==1 && args[0].equals("legacy");
        Field unsafeField=Unsafe.class.getDeclaredField("theUnsafe");unsafeField.setAccessible(true);
        Nimbus nimbus=(Nimbus)((Unsafe)unsafeField.get(null)).allocateInstance(Nimbus.class);
        Field submit=Nimbus.class.getDeclaredField("submitLock");submit.setAccessible(true);
        Object lock=new Object();submit.set(nimbus,lock);
        CountDownLatch active=new CountDownLatch(1),release=new CountDownLatch(1),closed=new CountDownLatch(1);
        AtomicBoolean interrupted=new AtomicBoolean();AtomicLong cleanupThread=new AtomicLong();
        AtomicReference<Throwable> failed=new AtomicReference<>();
        Field leader=Nimbus.class.getDeclaredField("leaderElector");leader.setAccessible(true);
        leader.set(nimbus,Proxy.newProxyInstance(Nimbus.class.getClassLoader(),new Class<?>[]{leader.getType()},
                (proxy,method,arguments)->{
                    if(method.getName().equals("isLeader")) {
                        cleanupThread.set(Thread.currentThread().threadId());active.countDown();
                        try {release.await();}catch(InterruptedException e){interrupted.set(true);}
                        return false;
                    }
                    return null;
                }));
        StormTimer timer=new StormTimer("cleanup-shutdown-probe",(thread,error)->failed.set(error));
        Field cleanup=Nimbus.class.getDeclaredField("cleanupTimer");cleanup.setAccessible(true);cleanup.set(nimbus,timer);
        timer.schedule(0,nimbus::doCleanup);
        if(!active.await(2,TimeUnit.SECONDS))throw new AssertionError("Cleanup never entered dependency lookup");
        Thread closer=new Thread(()->{
            try {
                if(legacy)timer.close();
                else {var stop=Nimbus.class.getDeclaredMethod("stopCleanupTimer");stop.setAccessible(true);stop.invoke(nimbus);}
            }catch(Throwable e){failed.set(e);}finally{closed.countDown();}
        },"cleanup-shutdown-closer");
        closer.start();boolean drained=false;
        try {
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
            while(System.nanoTime()<deadline && closed.getCount()!=0 && !interrupted.get()) {
                var info=ManagementFactory.getThreadMXBean().getThreadInfo(closer.threadId());
                if(info!=null && info.getThreadState()==Thread.State.BLOCKED && info.getLockOwnerId()==cleanupThread.get()) {
                    drained=true;break;
                }
                Thread.yield();
            }
        } finally {release.countDown();}
        if(!closed.await(2,TimeUnit.SECONDS))throw new AssertionError("Timer close did not finish after cleanup drain");
        closer.join();
        if(failed.get()!=null)throw new AssertionError("Unexpected close failure",failed.get());
        if(interrupted.get() || !drained)throw new AssertionError("Active Nimbus cleanup was interrupted instead of drained");
        // Once stopped, a late task must not access even the leader dependency.
        long before=cleanupThread.get();cleanupThread.set(0);nimbus.doCleanup();
        if(cleanupThread.get()!=0)throw new AssertionError("Late cleanup accessed dependencies after shutdown started");
        System.out.println("PASS: real Nimbus drains active cleanup, closes timer, and rejects late dependency I/O");
    }
}
