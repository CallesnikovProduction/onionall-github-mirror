package dev.onionall.brawler.m2;

import com.fasterxml.jackson.databind.ObjectMapper;
import crawlercommons.urlfrontier.URLFrontierGrpc;
import crawlercommons.urlfrontier.Urlfrontier.*;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.StreamObserver;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** Frontier-only lifecycle diagnostic: no HTTP/SOCKS client or arbitrary URL input. */
public final class FrontierLeaseProbe {
    public static void main(String[] args) throws Exception {
        if (args.length != 2 || !Set.of("lease-long", "lease-short", "complete").contains(args[0])
                || !args[1].matches("[a-z-]+\\.json"))
            throw new IllegalArgumentException("lease-long | lease-short | complete, simple JSON filename");
        String host = Files.readString(Path.of("/evidence/onion-hostname.txt")).trim();
        if (!host.matches("[a-z2-7]{56}\\.onion")) throw new IllegalStateException("Invalid fixture host");
        String url = "http://" + host + "/pending";
        var channel = ManagedChannelBuilder.forAddress("frontier", 7071).usePlaintext().build();
        try {
            Map<String, Object> result = new TreeMap<>();
            result.put("operation", args[0]);
            if (args[0].startsWith("lease-")) {
                int hold = args[0].equals("lease-long") ? 120 : 10;
                var params = GetParams.newBuilder().setCrawlID("m2").setKey(host)
                    .setMaxQueues(1).setMaxUrlsPerQueue(1).setDelayRequestable(hold).build();
                var iterator = URLFrontierGrpc.newBlockingStub(channel)
                    .withDeadlineAfter(5, TimeUnit.SECONDS).getURLs(params);
                List<String> urls = new ArrayList<>();
                while (iterator.hasNext()) {
                    var item = iterator.next();
                    if (!item.getUrl().equals(url) || !item.getKey().equals(host)
                            || !item.getCrawlID().equals("m2") || !urls.isEmpty())
                        throw new IllegalStateException("Unexpected frontier lease scope");
                    urls.add(item.getUrl());
                }
                // Process exits without sending a KnownURLItem update: no result ACK.
                result.put("urls", urls);
                result.put("hold_seconds", hold);
            } else {
                long refetch = Instant.now().getEpochSecond() + 86400;
                var metadata = StringList.newBuilder().addValues("committed").build();
                var info = URLInfo.newBuilder().setUrl(url).setKey(host).setCrawlID("m2")
                    .putMetadata("lifecycle.result", metadata).build();
                var item = URLItem.newBuilder().setID(url).setKnown(KnownURLItem.newBuilder()
                    .setInfo(info).setRefetchableFromDate(refetch)).build();
                CountDownLatch done = new CountDownLatch(1);
                AtomicReference<Throwable> failure = new AtomicReference<>();
                List<AckMessage> acks = Collections.synchronizedList(new ArrayList<>());
                var input = URLFrontierGrpc.newStub(channel).withDeadlineAfter(5, TimeUnit.SECONDS)
                    .putURLs(new StreamObserver<AckMessage>() {
                        public void onNext(AckMessage ack) { acks.add(ack); }
                        public void onError(Throwable error) { failure.set(error); done.countDown(); }
                        public void onCompleted() { done.countDown(); }
                    });
                input.onNext(item);
                input.onCompleted();
                if (!done.await(8, TimeUnit.SECONDS) || failure.get() != null || acks.size() != 1
                        || acks.get(0).getStatus() != AckMessage.Status.OK || !acks.get(0).getID().equals(url))
                    throw new IllegalStateException("Result update not acknowledged", failure.get());
                result.put("ack", "OK");
                result.put("refetch", refetch);
            }
            new ObjectMapper().writerWithDefaultPrettyPrinter()
                .writeValue(Path.of("/evidence", args[1]).toFile(), result);
        } finally {
            channel.shutdownNow();
            if (!channel.awaitTermination(5, TimeUnit.SECONDS))
                throw new IllegalStateException("gRPC channel did not stop");
        }
    }
}
