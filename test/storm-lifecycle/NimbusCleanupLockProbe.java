import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.apache.storm.daemon.nimbus.Nimbus;
import sun.misc.Unsafe;

/** Test-only: invoke real Nimbus cleanup without starting ZooKeeper or networking. */
public final class NimbusCleanupLockProbe {
    public static void main(String[] args) throws Exception {
        Field unsafeField=Unsafe.class.getDeclaredField("theUnsafe");unsafeField.setAccessible(true);
        Nimbus nimbus=(Nimbus)((Unsafe)unsafeField.get(null)).allocateInstance(Nimbus.class);
        Field submit= Nimbus.class.getDeclaredField("submitLock");submit.setAccessible(true);
        Object lock=new Object();submit.set(nimbus,lock);
        Field leader=Nimbus.class.getDeclaredField("leaderElector");leader.setAccessible(true);
        // A non-leader immediately returns AFTER acquiring the synchronization lock.
        leader.set(nimbus,Proxy.newProxyInstance(Nimbus.class.getClassLoader(),new Class<?>[]{leader.getType()},
                (proxy,method,arguments)->method.getReturnType()==boolean.class?false:null));
        CountDownLatch entered=new CountDownLatch(1),finished=new CountDownLatch(1);
        Thread cleanup=new Thread(()->{entered.countDown();try{nimbus.doCleanup();}finally{finished.countDown();}},"cleanup-lock-probe");
        boolean blocked=false;
        synchronized(lock) {
            cleanup.start();if(!entered.await(1,TimeUnit.SECONDS))throw new AssertionError("Cleanup thread not scheduled");
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
            while(System.nanoTime()<deadline && finished.getCount()!=0) {
                var info=ManagementFactory.getThreadMXBean().getThreadInfo(cleanup.threadId());
                if(info!=null && info.getThreadState()==Thread.State.BLOCKED && info.getLockOwnerId()==Thread.currentThread().threadId()) {
                    blocked=true;break;
                }
                Thread.yield();
            }
        }
        if(!finished.await(2,TimeUnit.SECONDS))throw new AssertionError("Cleanup failed to complete after publication lock released");
        cleanup.join();
        if(!blocked)throw new AssertionError("Real Nimbus cleanup entered while submission publication lock was held");
        System.out.println("PASS: real Nimbus cleanup blocks during publication and completes after release");
    }
}
