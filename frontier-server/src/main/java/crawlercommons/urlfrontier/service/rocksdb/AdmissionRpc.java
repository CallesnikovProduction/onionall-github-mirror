// SPDX-License-Identifier: Apache-2.0
package crawlercommons.urlfrontier.service.rocksdb;
import com.google.gson.*;
import com.google.protobuf.*;
import crawlercommons.urlfrontier.URLFrontierGrpc;
import io.grpc.*;
import io.grpc.protobuf.ProtoUtils;
import io.grpc.stub.ServerCalls;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Set;

/** Versioned protobuf envelope; bounded JSON schema is documented alongside source. */
public final class AdmissionRpc {
    public static final String METHOD="onionall.frontier.admission.v1.Admission/Command";
    private static final Set<String> CAPABILITY_METHODS = Set.of(METHOD,
        URLFrontierGrpc.getPutURLsMethod().getFullMethodName(),
        URLFrontierGrpc.getGetURLsMethod().getFullMethodName());
    private static final Set<String> READ_METHODS = Set.of("GetURLStatus", "ListURLs",
        "CountURLs", "ListCrawls", "ListQueues", "GetStats", "GetActive", "ListNodes");
    public static final MethodDescriptor<BytesValue,BytesValue> COMMAND=MethodDescriptor.<BytesValue,BytesValue>newBuilder()
        .setType(MethodDescriptor.MethodType.UNARY).setFullMethodName(METHOD)
        .setRequestMarshaller(ProtoUtils.marshaller(BytesValue.getDefaultInstance()))
        .setResponseMarshaller(ProtoUtils.marshaller(BytesValue.getDefaultInstance())).build();
    public static ServerServiceDefinition bind(RocksDBService service) {
        return ServerServiceDefinition.builder("onionall.frontier.admission.v1.Admission")
            .addMethod(COMMAND,ServerCalls.asyncUnaryCall((request,observer)->{
                try {
                    if(!request.getValue().isValidUtf8())throw new IllegalArgumentException("Require UTF8");
                    if(request.getValue().size()>32768)throw new IllegalArgumentException("Admission request limit32KiB");
                    String text=request.getValue().toStringUtf8();JsonObject command=JsonParser.parseString(text).getAsJsonObject();
                    JsonObject result=service.admissionCommand(command);
                    observer.onNext(BytesValue.newBuilder().setValue(ByteString.copyFromUtf8(result.toString())).build());observer.onCompleted();
                }catch(IllegalArgumentException|JsonParseException e){observer.onError(Status.INVALID_ARGUMENT.withDescription("Admission request rejected").asRuntimeException());}
                catch(IllegalStateException e){observer.onError(Status.INTERNAL.withDescription("Admission storage unavailable").asRuntimeException());}
            })).build();
    }
    public static ServerInterceptor guard(String token) {
        if(token==null||token.length()<32||token.length()>256)throw new IllegalArgumentException("Require ONIONALL_ADMISSION_TOKEN32..256");
        return new ServerInterceptor(){
            public <Q,S> ServerCall.Listener<Q> interceptCall(ServerCall<Q,S> call,Metadata headers,ServerCallHandler<Q,S> next){
                String method=call.getMethodDescriptor().getFullMethodName();
                if(CAPABILITY_METHODS.contains(method)){
                    String offered=headers.get(Metadata.Key.of("x-onionall-admission-token",Metadata.ASCII_STRING_MARSHALLER));
                    if(offered==null||!MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8),offered.getBytes(StandardCharsets.UTF_8))){call.close(Status.PERMISSION_DENIED.withDescription("Frontier capability required"),new Metadata());return new ServerCall.Listener<Q>(){};}
                }else {
                    String shortName=call.getMethodDescriptor().getBareMethodName();
                    // GetURLs acquires leases and PutURLs mutates scheduling: both are
                    // authenticated above for every crawl ID. Only real legacy reads
                    // remain public. Admin/unknown methods stay denied even with a token.
                    if(!method.startsWith(URLFrontierGrpc.SERVICE_NAME+"/")||!READ_METHODS.contains(shortName)){
                        call.close(Status.PERMISSION_DENIED.withDescription("Administrative mutation disabled on admission candidate"),new Metadata());return new ServerCall.Listener<Q>(){};
                    }
                }
                return next.startCall(call,headers);
            }
        };
    }
}
