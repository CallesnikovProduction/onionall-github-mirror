package dev.onionall.brawler.m1;

import java.net.*;
import java.nio.file.*;
import java.util.Arrays;
import java.util.concurrent.*;

/** Same container/JVM as the gRPC probe; fixed targets, no crawl requests. */
public final class ConnectivityCheck {
    static <T> T bounded(String stage, Callable<T> action, long timeoutMillis) throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "m1-connectivity");
            thread.setDaemon(true); // A stuck native DNS lookup must not keep the JVM alive.
            return thread;
        });
        Future<T> future = executor.submit(action);
        try {
            return future.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (ExecutionException | TimeoutException e) {
            Throwable cause = e instanceof ExecutionException ? e.getCause() : e;
            throw new IllegalStateException("M1 connectivity FAILED at " + stage
                + ": " + cause + ". Inspect network/service evidence; this readiness check submits no seeds.", cause);
        } finally {
            future.cancel(true);
            executor.shutdownNow();
        }
    }

    static void check() throws Exception {
        for (String file : new String[]{"/etc/resolv.conf", "/etc/hosts"}) {
            if (Files.isReadable(Path.of(file))) System.out.println(file + "\n" + Files.readString(Path.of(file)));
        }
        // One global 12-second budget, including native DNS, TCP and HTTP.
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(12);
        for (String host : new String[]{"frontier", "fixture"}) {
            step("DNS " + host, end, () -> {
                System.out.println("DNS " + host + " -> " + Arrays.toString(InetAddress.getAllByName(host)));
                return null;
            });
            int port = host.equals("frontier") ? 7071 : 8080;
            step("TCP " + host + ":" + port, end, () -> {
                try (Socket socket = new Socket()) {
                    socket.connect(new InetSocketAddress(host, port), 2000);
                }
                System.out.println("TCP " + host + ":" + port + " OK");
                return null;
            });
        }
        step("HTTP fixture:8080/health", end, () -> {
            HttpURLConnection connection = (HttpURLConnection) new URL("http://fixture:8080/health")
                .openConnection(Proxy.NO_PROXY);
            connection.setConnectTimeout(2000);
            connection.setReadTimeout(2000);
            connection.setInstanceFollowRedirects(false);
            try {
                if (connection.getResponseCode() != 200) throw new IllegalStateException("Expected HTTP 200");
                try (var input = connection.getInputStream()) {
                    if (!Arrays.equals(input.readNBytes(6), "ready".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                        throw new IllegalStateException("Unexpected fixture health response");
                }
            } finally {
                connection.disconnect();
            }
            System.out.println("HTTP fixture health OK");
            return null;
        });
    }

    private static <T> T step(String stage, long end, Callable<T> action) throws Exception {
        long remaining = TimeUnit.NANOSECONDS.toMillis(end - System.nanoTime());
        return bounded(stage, action, Math.max(1, Math.min(3000, remaining)));
    }
}
