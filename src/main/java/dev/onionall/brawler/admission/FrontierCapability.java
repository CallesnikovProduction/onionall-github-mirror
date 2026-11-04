package dev.onionall.brawler.admission;

import io.grpc.CallCredentials;
import io.grpc.Metadata;
import io.grpc.Status;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.Executor;

/** Existing admission capability, applied to every legacy RPC without an anonymous retry. */
public final class FrontierCapability extends CallCredentials {
    private static final Metadata.Key<String> HEADER = Metadata.Key.of(
        "x-onionall-admission-token", Metadata.ASCII_STRING_MARSHALLER);
    private final Path file;

    private FrontierCapability(Path file) { this.file = file; }

    public static FrontierCapability from(Map<String,Object> conf) {
        Object configured = conf.get("admission.token.file");
        if (configured == null || configured.toString().isBlank())
            throw new IllegalArgumentException("Require admission.token.file for Frontier RPC");
        FrontierCapability capability = new FrontierCapability(Path.of(configured.toString()));
        try { capability.read(); }
        catch (IOException e) { throw new IllegalArgumentException("Frontier capability unavailable"); }
        return capability;
    }

    private String read() throws IOException {
        String token = CrawlExecution.token(file);
        if (!token.matches("[A-Za-z0-9_-]+")) throw new IOException("Invalid capability encoding");
        return token;
    }

    @Override public void applyRequestMetadata(RequestInfo request, Executor executor, MetadataApplier applier) {
        try {
            executor.execute(() -> {
                try {
                    Metadata headers = new Metadata();
                    headers.put(HEADER, read());
                    applier.apply(headers);
                } catch (IOException | RuntimeException e) {
                    // Never propagate file contents, credentials or filesystem exceptions to logs.
                    applier.fail(Status.PERMISSION_DENIED.withDescription("Frontier capability unavailable"));
                }
            });
        } catch (RuntimeException e) {
            applier.fail(Status.PERMISSION_DENIED.withDescription("Frontier capability unavailable"));
        }
    }

    @Override public void thisUsesUnstableApi() { }
}
