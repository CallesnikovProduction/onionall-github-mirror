// SPDX-License-Identifier: Apache-2.0
package crawlercommons.urlfrontier.service.rocksdb;

import com.google.gson.*;
import crawlercommons.urlfrontier.CrawlID;
import crawlercommons.urlfrontier.Urlfrontier.*;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import org.rocksdb.*;

/** Server-owned v1 quota/reservation/enqueue transaction in the Frontier DB.
 * All entrypoints are serialized by the owning service's admissionLock.
 */
final class AdmissionLedger {
    private final RocksDBService owner;
    private final RocksDB db;
    private final ColumnFamilyHandle cf;
    private final Gson json = new Gson();
    boolean readOnly;
    Runnable beforeCommit, afterCommit;
    boolean failBeforeCommit; // package-only deterministic fault injection, never RPC exposed
    AdmissionLedger(RocksDBService owner, RocksDB db, ColumnFamilyHandle cf) {
        this.owner=owner; this.db=db; this.cf=cf;
        try {
            byte[] key=key("schema"), old=db.get(cf,key);
            if(old!=null && !Arrays.equals(old,new byte[]{1})) throw new IllegalStateException("Unsupported admission schema");
            if(old==null) try(WriteOptions options=new WriteOptions().setSync(true)){db.put(cf,options,key,new byte[]{1});}
        }catch(RocksDBException e){throw new IllegalStateException("Admission schema unavailable",e);}
    }
    static byte[] key(String...parts) {
        try {
            ByteArrayOutputStream bytes=new ByteArrayOutputStream(); DataOutputStream out=new DataOutputStream(bytes);
            out.writeByte(1);for(String part:parts){byte[] text=part.getBytes(StandardCharsets.UTF_8);out.writeInt(text.length);out.write(text);}return bytes.toByteArray();
        }catch(IOException e){throw new AssertionError(e);}
    }
    private JsonObject get(String...parts)throws RocksDBException {
        byte[] bytes=db.get(cf,key(parts));return bytes==null?null:JsonParser.parseString(new String(bytes,StandardCharsets.UTF_8)).getAsJsonObject();
    }
    private void put(WriteBatch batch,JsonObject value,String...parts)throws RocksDBException {
        batch.put(cf,key(parts),json.toJson(value).getBytes(StandardCharsets.UTF_8));
    }
    static String text(JsonObject o,String field,int max) {
        if(!o.has(field)||!o.get(field).isJsonPrimitive()||!o.get(field).getAsJsonPrimitive().isString())throw new IllegalArgumentException("Require "+field);
        String v=o.get(field).getAsString();if(v.length()>max||v.indexOf(0)>=0)throw new IllegalArgumentException("Invalid "+field);return v;
    }
    static int number(JsonObject o,String field,int min,int max) {
        if(!o.has(field)||!o.get(field).isJsonPrimitive()||!o.get(field).getAsJsonPrimitive().isNumber())throw new IllegalArgumentException("Require "+field);
        String v=o.get(field).getAsString();if(!v.matches("[0-9]{1,8}"))throw new IllegalArgumentException("Invalid "+field);
        int n=Integer.parseInt(v);if(n<min||n>max)throw new IllegalArgumentException("Invalid "+field);return n;
    }
    static String canonical(String value) {
        try {
            if(value.length()>2048)throw new IllegalArgumentException("URL bound");
            URI u=new URI(value);String h=u.getHost();String s=u.getScheme();
            if(!Set.of("http","https").contains(s)||h==null||!validOnion(h)||u.getRawUserInfo()!=null||u.getRawFragment()!=null)throw new IllegalArgumentException("Invalid onion URL");
            if(u.getPort()!=-1&&u.getPort()!=(s.equals("http")?80:443))throw new IllegalArgumentException("Invalid port");
            String path=u.getRawPath().isEmpty()?"/":u.getRawPath();
            if(!path.equals(u.normalize().getRawPath())&&!u.getRawPath().isEmpty())throw new IllegalArgumentException("Noncanonical path");
            for(String part:path.split("/"))if(Set.of(".","..").contains(part.replaceAll("(?i)%2e",".")))throw new IllegalArgumentException("Dot segment");
            if(!u.getRawAuthority().equals(h+(u.getPort()==-1?"":":"+u.getPort())))throw new IllegalArgumentException("Invalid authority");
            return s+"://"+h+path+(u.getRawQuery()==null?"":"?"+u.getRawQuery());
        }catch(java.net.URISyntaxException e){throw new IllegalArgumentException("Invalid URL",e);}
    }
    static String origin(String url){URI u=URI.create(url);return u.getScheme()+"://"+u.getHost();}
    static boolean validOnion(String h) {
        if(!h.matches("[a-z2-7]{56}\\.onion"))return false;
        byte[] raw=new byte[35];int acc=0,bits=0,pos=0;String alphabet="abcdefghijklmnopqrstuvwxyz234567";
        for(char c:h.substring(0,56).toCharArray()){acc=(acc<<5)|alphabet.indexOf(c);bits+=5;if(bits>=8){bits-=8;raw[pos++]=(byte)(acc>>bits);}}
        if(raw[34]!=3)return false;
        try {
            MessageDigest digest=MessageDigest.getInstance("SHA3-256");digest.update(".onion checksum".getBytes(StandardCharsets.US_ASCII));digest.update(raw,0,32);digest.update((byte)3);byte[] sum=digest.digest();return raw[32]==sum[0]&&raw[33]==sum[1];
        }catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    private static JsonObject outcome(String result){JsonObject o=new JsonObject();o.addProperty("outcome",result);return o;}
    JsonObject command(JsonObject command) {
        try {
            String action=text(command,"action",20), id=text(command,"session_id",64);
            if(!id.matches("[A-Za-z0-9-]{1,64}"))throw new IllegalArgumentException("Invalid session id");
            JsonObject state=get("session",id);
            if(readOnly && Set.of("open","reserve","seal").contains(action))return outcome("ADMISSION_READ_ONLY");
            if(action.equals("open"))return open(id,state,command);
            if(state==null)return outcome("SESSION_NOT_FOUND");
            if(action.equals("session"))return state.deepCopy();
            if(action.equals("reservation")){JsonObject r=get("reservation",id,canonical(text(command,"url",2048)));return r==null?outcome("RESERVATION_NOT_FOUND"):r;}
            if(action.equals("seal")){state.addProperty("sealed",true);try(WriteBatch b=new WriteBatch();WriteOptions w=new WriteOptions().setSync(true)){put(b,state,"session",id);db.write(w,b);}return state;}
            if(!action.equals("reserve"))throw new IllegalArgumentException("Unknown action");
            return reserve(id,state,command);
        }catch(RocksDBException e){throw new IllegalStateException("Admission storage failed",e);}
    }
    private JsonObject open(String id,JsonObject state,JsonObject c)throws RocksDBException {
        if(!c.has("approved")||!c.get("approved").isJsonPrimitive()||!c.get("approved").getAsJsonPrimitive().isBoolean()||!c.get("approved").getAsBoolean())return outcome("APPROVAL_REQUIRED");
        int max=number(c,"max_pages",1,100),depth=number(c,"max_depth",0,5);
        String allowed=origin(canonical(text(c,"origin",256)+"/"));
        if(!allowed.equals(text(c,"origin",256)))throw new IllegalArgumentException("Require canonical origin");
        if(!c.has("seeds")||!c.get("seeds").isJsonArray()||c.getAsJsonArray("seeds").size()<1||c.getAsJsonArray("seeds").size()>100)throw new IllegalArgumentException("Require seeds");
        SortedSet<String> seeds=new TreeSet<>();for(JsonElement e:c.getAsJsonArray("seeds")){String u=canonical(e.getAsString());if(!origin(u).equals(allowed))throw new IllegalArgumentException("Seed scope");seeds.add(u);}if(seeds.size()>max)throw new IllegalArgumentException("Seeds exceed quota");
        JsonObject spec=new JsonObject();spec.addProperty("session_id",id);spec.addProperty("origin",allowed);spec.addProperty("max_pages",max);spec.addProperty("max_depth",depth);spec.add("seeds",json.toJsonTree(seeds));spec.addProperty("approved",true);spec.addProperty("policy_version",1);
        if(state!=null){if(!state.getAsJsonObject("spec").equals(spec))return outcome("SESSION_CONFLICT");return state;}
        if(owner.crawlExists(id))return outcome("CRAWL_CONFLICT");
        state=outcome("SESSION_OPEN");state.add("spec",spec);state.addProperty("reserved_count",0);state.addProperty("sealed",false);state.addProperty("created_at",Instant.now().toString());
        try(WriteBatch b=new WriteBatch();WriteOptions w=new WriteOptions().setSync(true)){put(b,state,"session",id);db.write(w,b);}return state;
    }
    private JsonObject reserve(String id,JsonObject state,JsonObject c)throws RocksDBException {
        JsonObject spec=state.getAsJsonObject("spec");String url=canonical(text(c,"url",2048)),source=text(c,"source_url",2048);int depth=number(c,"depth",0,6);
        if(!origin(url).equals(spec.get("origin").getAsString()))return outcome("SCOPE_REJECTED");
        if(source.isEmpty()) { if(depth!=0||!spec.getAsJsonArray("seeds").contains(new JsonPrimitive(url)))return outcome("SEED_REJECTED"); }
        else {source=canonical(source);JsonObject parent=get("reservation",id,source);if(parent==null||depth!=parent.get("depth").getAsInt()+1)return outcome("SOURCE_REJECTED");}
        JsonObject previous=get("reservation",id,url);if(previous!=null){previous.addProperty("outcome","ALREADY_RESERVED");return previous;}
        if(depth>spec.get("max_depth").getAsInt())return outcome("DEPTH_REJECTED");
        if(state.get("sealed").getAsBoolean())return outcome("SESSION_SEALED");
        if(state.get("reserved_count").getAsInt()>=spec.get("max_pages").getAsInt())return outcome("LIMIT_REJECTED");
        String leaf=URI.create(url).getPath().toLowerCase(Locale.ROOT);leaf=leaf.substring(leaf.lastIndexOf('/')+1);if(leaf.contains(".")&&!leaf.endsWith(".html")&&!leaf.endsWith(".htm"))return outcome("METADATA_ONLY");
        JsonObject r=outcome("RESERVED");r.addProperty("session_id",id);r.addProperty("url",url);r.addProperty("source_url",source);r.addProperty("depth",depth);r.addProperty("reservation_id",UUID.nameUUIDFromBytes(key(id,url)).toString());r.addProperty("ordinal",state.get("reserved_count").getAsInt()+1);r.addProperty("discovered_at",Instant.now().toString());r.addProperty("enqueued",true);
        JsonObject next=state.deepCopy();next.addProperty("reserved_count",r.get("ordinal").getAsInt());
        URLInfo info=URLInfo.newBuilder().setCrawlID(id).setKey(URI.create(url).getHost()).setUrl(url)
            .putMetadata("discovery.depth",StringList.newBuilder().addValues(Integer.toString(depth)).build())
            .putMetadata("discovery.source",StringList.newBuilder().addValues(source.isEmpty()?url:source).build())
            .putMetadata("discovery.at",StringList.newBuilder().addValues(r.get("discovered_at").getAsString()).build()).build();
        try(WriteBatch b=new WriteBatch();WriteOptions w=new WriteOptions().setSync(true)){
            owner.stageAdmission(info,b);put(b,r,"reservation",id,url);put(b,next,"session",id);
            if(failBeforeCommit){failBeforeCommit=false;throw new RocksDBException("Injected precommit failure");}
            if(beforeCommit!=null)beforeCommit.run();
            db.write(w,b);
            if(afterCommit!=null)afterCommit.run();
        }
        owner.publishAdmission(info);return r;
    }
    boolean permitsLegacy(URLItem item) {
        URLInfo info=item.hasDiscovered()?item.getDiscovered().getInfo():item.getKnown().getInfo();
        try {
            String id=CrawlID.normaliseCrawlID(info.getCrawlID());if(get("session",id)==null)return true;
            String u=canonical(info.getUrl());JsonObject r=get("reservation",id,u);
            return r!=null&&u.equals(info.getUrl())&&URI.create(u).getHost().equals(info.getKey());
        }catch(RocksDBException|IllegalArgumentException e){return false;}
    }
}
