// SPDX-License-Identifier: Apache-2.0
package crawlercommons.urlfrontier.service.rocksdb;
import com.google.protobuf.*;
import crawlercommons.urlfrontier.URLFrontierGrpc;
import crawlercommons.urlfrontier.Urlfrontier.*;
import io.grpc.*;
import io.grpc.stub.*;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
public final class AdmissionRpcConformance {
 static void check(boolean value){if(!value)throw new AssertionError();}
 static void denied(Runnable action){try{action.run();throw new AssertionError("missing denial");}catch(StatusRuntimeException e){check(e.getStatus().getCode()==Status.Code.PERMISSION_DENIED);}}
 public static void main(String[] args)throws Exception{
  String token="0123456789012345678901234567890123456789";
  try(RocksDBService service=new RocksDBService(java.util.Map.of("rocksdb.path",args[0]),"localhost",7071)){
   ServerInterceptor guard=AdmissionRpc.guard(token);
   Server server=ServerBuilder.forPort(0).addService(ServerInterceptors.intercept(service,guard)).addService(ServerInterceptors.intercept(AdmissionRpc.bind(service),guard)).build().start();
   ManagedChannel raw=ManagedChannelBuilder.forAddress("127.0.0.1",server.getPort()).usePlaintext().build();
   try{
    BytesValue request=BytesValue.newBuilder().setValue(ByteString.copyFromUtf8("{\"action\":\"session\",\"session_id\":\"missing\"}")).build();
    denied(()->ClientCalls.blockingUnaryCall(raw,AdmissionRpc.COMMAND,CallOptions.DEFAULT.withDeadlineAfter(3,TimeUnit.SECONDS),request));
    Metadata headers=new Metadata();headers.put(Metadata.Key.of("x-onionall-admission-token",Metadata.ASCII_STRING_MARSHALLER),token);
    Channel authorized=ClientInterceptors.intercept(raw,MetadataUtils.newAttachHeadersInterceptor(headers));
    check(ClientCalls.blockingUnaryCall(authorized,AdmissionRpc.COMMAND,CallOptions.DEFAULT.withDeadlineAfter(3,TimeUnit.SECONDS),request).getValue().toStringUtf8().contains("SESSION_NOT_FOUND"));
    denied(()->ClientCalls.blockingUnaryCall(authorized,URLFrontierGrpc.getDeleteCrawlMethod(),CallOptions.DEFAULT.withDeadlineAfter(3,TimeUnit.SECONDS),DeleteCrawlMessage.getDefaultInstance()));
    // Legacy read/lease remains usable without admission capability inside the isolated deployment.
    URLFrontierGrpc.newBlockingStub(raw).withDeadlineAfter(3,TimeUnit.SECONDS).getActive(Local.getDefaultInstance());
   }finally{raw.shutdownNow().awaitTermination(5,TimeUnit.SECONDS);server.shutdownNow().awaitTermination(5,TimeUnit.SECONDS);}
  }
  System.out.println("ADMISSION_RPC_CONFORMANCE_PASS capability rejection/authenticated read/admin denial/legacy read");
 }
}
