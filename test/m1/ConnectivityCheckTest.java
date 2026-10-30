package dev.onionall.brawler.m1;

import java.net.UnknownHostException;
import java.util.concurrent.CountDownLatch;

/** No sockets needed: checks propagation and an uninterruptible resolver timeout. */
public final class ConnectivityCheckTest {
    public static void main(String[] args) throws Exception {
        if (!"ok".equals(ConnectivityCheck.bounded("success", () -> "ok", 1000)))
            throw new AssertionError("Success result lost");
        try {
            ConnectivityCheck.bounded("DNS frontier", () -> { throw new UnknownHostException("frontier"); }, 1000);
            throw new AssertionError("DNS failure accepted");
        } catch (IllegalStateException expected) {
            if (!expected.getMessage().contains("DNS frontier") || !(expected.getCause() instanceof UnknownHostException))
                throw new AssertionError("DNS diagnostic lost", expected);
        }
        long start = System.nanoTime();
        try {
            ConnectivityCheck.bounded("stuck DNS", () -> {
                while (true) {
                    try { new CountDownLatch(1).await(); } catch (InterruptedException ignored) { }
                }
            }, 50);
            throw new AssertionError("Stuck lookup accepted");
        } catch (IllegalStateException expected) {
            if (!(expected.getCause() instanceof java.util.concurrent.TimeoutException)) throw expected;
        }
        if (System.nanoTime() - start > 2_000_000_000L) throw new AssertionError("Timeout not bounded");
        System.out.println("Connectivity checks passed: success, DNS diagnostic, stuck lookup deadline");
    }
}
