// SPDX-License-Identifier: Apache-2.0
package crawlercommons.urlfrontier.service.rocksdb;
import com.google.gson.*;
import crawlercommons.urlfrontier.Urlfrontier.*;
import io.grpc.*;
import io.grpc.protobuf.ProtoUtils;
import io.grpc.stub.*;
import com.google.protobuf.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Exercises the actual owning RocksDB server implementation, never an admission mock. */
public final class AdmissionConformance {
    static final String HOST="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaam2dqd.onion";
    static String base;
    static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    static JsonObject command(String action,String id){JsonObject c=new JsonObject();c.addProperty("action",action);c.addProperty("session_id",id);return c;}
    static JsonObject open(String id,int pages,int depth){JsonObject c=command("open",id);c.addProperty("origin",base);c.addProperty("max_pages",pages);c.addProperty("max_depth",depth);c.addProperty("approved",true);JsonArray a=new JsonArray();a.add(base+"/seed");c.add("seeds",a);return c;}
    static JsonObject reserve(String id,String path,String source,int depth){JsonObject c=command("reserve",id);c.addProperty("url",base+path);c.addProperty("source_url",source.isEmpty()?"":base+source);c.addProperty("depth",depth);return c;}
    static RocksDBService service(Path path){return new RocksDBService(Map.of("rocksdb.path",path.toString()),"localhost",7071);}
    static String result(JsonObject r){return r.get("outcome").getAsString();}
    static void noMutation(RocksDBService s,String id,int count){check(s.admissionCommand(command("session",id)).get("reserved_count").getAsInt()==count,"count changed");}
    static List<URLInfo> dequeue(RocksDBService s,String id){List<URLInfo> rows=new ArrayList<>();s.getURLs(GetParams.newBuilder().setCrawlID(id).setMaxQueues(1).setMaxUrlsPerQueue(100).setDelayRequestable(120).build(),new StreamObserver<URLInfo>(){public void onNext(URLInfo info){rows.add(info);}public void onError(Throwable t){throw new AssertionError(t);}public void onCompleted(){}});return rows;}
    static URLItem item(String id,String path){return URLItem.newBuilder().setDiscovered(DiscoveredURLItem.newBuilder().setInfo(URLInfo.newBuilder().setCrawlID(id).setKey(java.net.URI.create(base).getHost()).setUrl(base+path))).build();}
    static String host()throws Exception {
        byte[] key=new byte[32];java.security.MessageDigest sha=java.security.MessageDigest.getInstance("SHA3-256");sha.update(".onion checksum".getBytes(StandardCharsets.US_ASCII));sha.update(key);sha.update((byte)3);byte[] sum=sha.digest();byte[] raw=Arrays.copyOf(key,35);raw[32]=sum[0];raw[33]=sum[1];raw[34]=3;
        String a="abcdefghijklmnopqrstuvwxyz234567";StringBuilder out=new StringBuilder();int acc=0,bits=0;for(byte b:raw){acc=(acc<<8)|(b&255);bits+=8;while(bits>=5){bits-=5;out.append(a.charAt((acc>>bits)&31));}}return out+".onion";
    }
    public static void main(String[] args)throws Exception {
        base="http://"+host();
        if(args.length==3&&args[0].equals("crash")){
            RocksDBService s=service(Path.of(args[2]));s.admissionCommand(open("crash",2,1));
            if(args[1].equals("before"))s.admission.beforeCommit=()->Runtime.getRuntime().halt(23);else s.admission.afterCommit=()->Runtime.getRuntime().halt(23);
            s.admissionCommand(reserve("crash","/seed","",0));throw new AssertionError("Crash hook not called");
        }
        check(AdmissionLedger.canonical(base+"/é").equals(base+"/%C3%A9"),"Unicode identity differs from transport");
        Path root=Path.of(args[0]);Files.createDirectories(root);
        String stable;
        try(RocksDBService s=service(root.resolve("quota"))){
            JsonObject unapproved=open("quota",10,2);unapproved.addProperty("approved",false);check(result(s.admissionCommand(unapproved)).equals("APPROVAL_REQUIRED"),"approval bypass");
            s.admissionCommand(open("quota",10,2));
            check(result(s.admissionCommand(reserve("quota","/seed","",0))).equals("RESERVED"),"seed reserve");
            ExecutorService pool=Executors.newFixedThreadPool(8);List<Future<String>> tasks=new ArrayList<>();for(int i=0;i<64;i++){final int n=i;tasks.add(pool.submit(()->result(s.admissionCommand(reserve("quota","/child"+n,"/seed",1)))));}
            int admitted=0;for(Future<String> f:tasks)if(f.get().equals("RESERVED"))admitted++;pool.shutdown();check(admitted==9,"quota overshot/undershot");noMutation(s,"quota",10);
            JsonObject replay=s.admissionCommand(reserve("quota","/seed","",0));stable=replay.get("reservation_id").getAsString();check(result(replay).equals("ALREADY_RESERVED"),"duplicate outcome");
            check(result(s.admissionCommand(open("quota",9,2))).equals("SESSION_CONFLICT"),"immutable spec");
            check(s.putURLItem(item("quota","/unreserved"))==AckMessage.Status.FAIL,"legacy creation bypass");
            check(dequeue(s,"quota").size()==10,"committed admissions not dequeueable");
            noMutation(s,"quota",10);
            s.admissionCommand(open("fault",2,1));s.admission.failBeforeCommit=true;
            try{s.admissionCommand(reserve("fault","/seed","",0));throw new AssertionError("Fault not injected");}catch(IllegalStateException expected){}
            noMutation(s,"fault",0);check(dequeue(s,"fault").isEmpty(),"failed transaction published queue");
            check(result(s.admissionCommand(reserve("fault","/seed","",0))).equals("RESERVED"),"retry after write failure");
            JsonObject foreign=reserve("fault","/other","/seed",1);foreign.addProperty("url","https://"+host()+"/other");check(result(s.admissionCommand(foreign)).equals("SCOPE_REJECTED"),"same-origin bypass");
            check(result(s.admissionCommand(reserve("fault","/other","/unknown",1))).equals("SOURCE_REJECTED"),"source bypass");
            check(result(s.admissionCommand(reserve("fault","/file.pdf","/seed",1))).equals("METADATA_ONLY"),"binary admission");
            s.admissionCommand(command("seal","fault"));check(result(s.admissionCommand(reserve("fault","/other","/seed",1))).equals("SESSION_SEALED"),"seal bypass");
        }
        try(RocksDBService s=service(root.resolve("quota"))){noMutation(s,"quota",10);check(s.admissionCommand(reserve("quota","/seed","",0)).get("reservation_id").getAsString().equals(stable),"restart identity");check(dequeue(s,"quota").size()==10,"restart index");}
        for(String phase:List.of("before","after")){
            Path db=root.resolve("crash-"+phase);
            Process child=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin/java").toString(),"-cp",System.getProperty("java.class.path"),AdmissionConformance.class.getName(),"crash",phase,db.toString()).inheritIO().start();
            check(child.waitFor(20,TimeUnit.SECONDS),"child timeout");check(child.exitValue()==23,"child did not halt at commit boundary");
            try(RocksDBService s=service(db)){noMutation(s,"crash",phase.equals("before")?0:1);check(dequeue(s,"crash").size()==(phase.equals("before")?0:1),"crash quota/queue divergence");JsonObject replay=s.admissionCommand(reserve("crash","/seed","",0));check(result(replay).equals(phase.equals("before")?"RESERVED":"ALREADY_RESERVED"),"ACK-loss replay");}
        }
        System.out.println("ADMISSION_CONFORMANCE_PASS concurrency64 quota10 duplicate restart writefailure scope source extension seal crash-before/after-WAL-before-ACK");
    }
}
