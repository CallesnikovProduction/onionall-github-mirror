package dev.onionall.brawler.m2;

import com.fasterxml.jackson.databind.ObjectMapper;
import crawlercommons.urlfrontier.URLFrontierGrpc;
import crawlercommons.urlfrontier.Urlfrontier.*;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.StreamObserver;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** Test-only bounded gRPC client. Never takes an arbitrary target or crawl URL. */
public final class FrontierProbe {
    private static final String CRAWL = "m2";

    public static void main(String[] args) throws Exception {
        if (args.length == 0) throw new IllegalArgumentException("ready | seed root/pending | snapshot filename");
        ManagedChannel channel = ManagedChannelBuilder.forAddress("frontier", 7071).usePlaintext().build();
        try {
            var stub = URLFrontierGrpc.newBlockingStub(channel);
            switch (args[0]) {
                case "ready" -> {
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                    while (true) {
                        try {
                            stub.withDeadlineAfter(2, TimeUnit.SECONDS).listCrawls(Local.getDefaultInstance());
                            break;
                        } catch (io.grpc.StatusRuntimeException e) {
                            if (System.nanoTime() >= deadline)
                                throw new IllegalStateException("M2 gRPC frontier:7071 unavailable; inspect frontier logs", e);
                            Thread.sleep(500);
                        }
                    }
                    System.out.println("URLFrontier gRPC ready");
                }
                case "seed" -> {
                    if (args.length != 2 || !Set.of("root", "pending", "outage").contains(args[1]))
                        throw new IllegalArgumentException("Only root, pending or outage synthetic seeds are permitted");
                    String host = java.nio.file.Files.readString(Path.of("/evidence/onion-hostname.txt")).trim();
                    if (!host.matches("[a-z2-7]{56}\\.onion")) throw new IllegalStateException("Invalid onion hostname");
                    String url = "http://" + host + "/" + (args[1].equals("root") ? "" : args[1]);
                    var info = URLInfo.newBuilder().setUrl(url).setKey(host).setCrawlID(CRAWL).build();
                    var item = URLItem.newBuilder().setID(url)
                        .setDiscovered(DiscoveredURLItem.newBuilder().setInfo(info)).build();
                    CountDownLatch done = new CountDownLatch(1);
                    AtomicReference<Throwable> failure = new AtomicReference<>();
                    List<AckMessage> acks = Collections.synchronizedList(new ArrayList<>());
                    var input = URLFrontierGrpc.newStub(channel).withDeadlineAfter(10, TimeUnit.SECONDS)
                        .putURLs(new StreamObserver<AckMessage>() {
                            public void onNext(AckMessage ack) { acks.add(ack); }
                            public void onError(Throwable e) { failure.set(e); done.countDown(); }
                            public void onCompleted() { done.countDown(); }
                        });
                    input.onNext(item);
                    input.onCompleted();
                    if (!done.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("Seed RPC timed out");
                    if (failure.get() != null) throw new IllegalStateException("Seed RPC failed", failure.get());
                    if (acks.size() != 1 || acks.get(0).getStatus() != AckMessage.Status.OK)
                        throw new IllegalStateException("Seed not acknowledged successfully: " + acks);
                    System.out.println("Seed acknowledged: " + url);
                }
                case "snapshot" -> {
                    if (args.length != 2 || !args[1].matches("[a-z-]+\\.json"))
                        throw new IllegalArgumentException("Expected simple JSON filename");
                    List<Map<String, Object>> entries = new ArrayList<>();
                    var items = stub.withDeadlineAfter(10, TimeUnit.SECONDS).listURLs(
                        ListUrlParams.newBuilder().setCrawlID(CRAWL).setStart(0).setSize(100).build());
                    while (items.hasNext()) {
                        var item = items.next();
                        if (!item.hasKnown() && !item.hasDiscovered()) throw new IllegalStateException("Empty URL item");
                        URLInfo info = item.hasKnown() ? item.getKnown().getInfo() : item.getDiscovered().getInfo();
                        Map<String, Object> row = new TreeMap<>();
                        row.put("url", info.getUrl());
                        row.put("key", info.getKey());
                        row.put("crawl", info.getCrawlID());
                        row.put("kind", item.hasKnown() ? "known" : "discovered");
                        row.put("refetch", item.hasKnown() ? item.getKnown().getRefetchableFromDate() : null);
                        Map<String, Object> metadata = new TreeMap<>();
                        info.getMetadataMap().forEach((key, value) -> metadata.put(key, value.getValuesList()));
                        row.put("metadata", metadata);
                        entries.add(row);
                    }
                    if (entries.size() >= 100) throw new IllegalStateException("Unexpected corpus size; snapshot capped");
                    entries.sort(Comparator.comparing(row -> (String) row.get("url")));
                    new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(
                        Path.of("/evidence", args[1]).toFile(),
                        Map.of("entries", entries, "captured_at_epoch", java.time.Instant.now().getEpochSecond()));
                    System.out.println("Snapshot: " + entries.size() + " entries");
                }
                default -> throw new IllegalArgumentException("Unknown command: " + args[0]);
            }
        } finally {
            channel.shutdownNow();
            if (!channel.awaitTermination(5, TimeUnit.SECONDS)) throw new IllegalStateException("gRPC channel did not stop");
        }
    }
}
