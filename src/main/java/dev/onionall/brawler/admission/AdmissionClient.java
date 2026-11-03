package dev.onionall.brawler.admission;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.ByteString;
import com.google.protobuf.BytesValue;
import io.grpc.*;
import io.grpc.protobuf.ProtoUtils;
import io.grpc.stub.ClientCalls;
import io.grpc.stub.MetadataUtils;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Versioned bounded unary RPC. No count/put fallback to a stock Frontier. */
public final class AdmissionClient implements AutoCloseable {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final MethodDescriptor<BytesValue, BytesValue> COMMAND = MethodDescriptor
        .<BytesValue, BytesValue>newBuilder().setType(MethodDescriptor.MethodType.UNARY)
        .setFullMethodName("onionall.frontier.admission.v1.Admission/Command")
        .setRequestMarshaller(ProtoUtils.marshaller(BytesValue.getDefaultInstance()))
        .setResponseMarshaller(ProtoUtils.marshaller(BytesValue.getDefaultInstance())).build();
    private final ManagedChannel channel;
    private final Channel authenticated;
    public AdmissionClient(String host, int port, String token) throws IOException {
        if (host == null || !host.matches("[a-zA-Z0-9.-]{1,253}") || host.replaceAll("\\.+$", "").toLowerCase(java.util.Locale.ROOT).endsWith(".onion") || port < 1 || port > 65535)
            throw new IOException("Invalid admission endpoint");
        if (token == null || token.length() < 32 || token.length() > 256 || !token.matches("[A-Za-z0-9_-]+"))
            throw new IOException("Require a bounded admission capability token");
        channel = ManagedChannelBuilder.forAddress(host, port).usePlaintext().maxInboundMessageSize(65536).build();
        Metadata metadata = new Metadata();
        metadata.put(Metadata.Key.of("x-onionall-admission-token", Metadata.ASCII_STRING_MARSHALLER), token);
        authenticated = ClientInterceptors.intercept(channel, MetadataUtils.newAttachHeadersInterceptor(metadata));
    }
    public Map<String,Object> command(Map<String,Object> request) throws IOException {
        byte[] body = JSON.writeValueAsBytes(request);
        if (body.length > 32768) throw new IOException("Admission request exceeds32KiB");
        try {
            BytesValue response = ClientCalls.blockingUnaryCall(authenticated, COMMAND,
                CallOptions.DEFAULT.withDeadlineAfter(10, TimeUnit.SECONDS),
                BytesValue.newBuilder().setValue(ByteString.copyFrom(body)).build());
            if (response.getValue().size() > 65536) throw new IOException("Admission response exceeds64KiB");
            return JSON.readValue(response.getValue().toByteArray(), new TypeReference<Map<String,Object>>(){});
        } catch (StatusRuntimeException e) { throw new IOException("Admission RPC failed: " + e.getStatus().getCode(), e); }
    }
    @Override public void close() {
        channel.shutdownNow();
        try { if (!channel.awaitTermination(5, TimeUnit.SECONDS)) throw new IllegalStateException("Admission channel did not stop"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
