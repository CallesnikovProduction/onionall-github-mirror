package dev.onionall.brawler.admission;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import org.apache.stormcrawler.Metadata;
import org.apache.stormcrawler.parse.ParseFilter;
import org.apache.stormcrawler.parse.ParseResult;
import org.jsoup.Jsoup;
import org.w3c.dom.DocumentFragment;

/** The Frontier server owns quota, depth and scheduling. No local admission cache.
 * Graph extraction runs on bounded HTML independently of upstream outlink filters,
 * retaining rejected relationships without making them fetchable.
 */
public final class DurableDiscoveryFilter extends ParseFilter {
    private String host, session, token;
    private int port;
    private dev.onionall.brawler.tor.TorPolicy policy;
    private final ObjectMapper json = new ObjectMapper();

    @Override public void configure(Map<String,Object> conf, JsonNode params, String name) {
        super.configure(conf,params,name);
        if (!"durable-v1".equals(conf.get("discovery.budget.mode")))
            throw new IllegalArgumentException("Require durable-v1 admission");
        policy = new dev.onionall.brawler.tor.TorPolicy(conf);
        host = required(conf,"admission.host");
        port = Integer.parseInt(required(conf,"admission.port"));
        if (port<1 || port>65535) throw new IllegalArgumentException("Invalid admission port");
        session = required(conf,"admission.session.id");
        if (!session.equals(conf.get("archive.job.id")) || !session.equals(conf.get("urlfrontier.crawlid")))
            throw new IllegalArgumentException("Archive, Frontier and admission session must agree");
        try { token = Files.readString(Path.of(required(conf,"admission.token.file"))).strip(); }
        catch (IOException e) { throw new IllegalArgumentException("Admission capability unavailable",e); }
        if (token.length()<32 || token.length()>256) throw new IllegalArgumentException("Invalid admission capability");
    }
    private static String required(Map<String,Object> conf,String key) {
        Object v=conf.get(key); if(v==null || v.toString().isBlank()) throw new IllegalArgumentException("Missing "+key);
        return v.toString();
    }
    private String canonicalTarget(String url) throws IOException { return policy.normalize(url); }
    @Override public boolean needsDOM() { return false; }
    @Override public void filter(String url,byte[] content,DocumentFragment fragment,ParseResult result) {
        if (host==null) throw new IllegalStateException("Admission filter not configured");
        if (content.length>131072) throw new IllegalArgumentException("HTML exceeds admission inspection limit");
        try (AdmissionClient client=new AdmissionClient(host,port,token)) {
            Map<String,Object> parent=client.command(Map.of("action","reservation","session_id",session,"url",url));
            if (!parent.containsKey("depth")) throw new IOException("Source has no authoritative reservation");
            int depth=((Number)parent.get("depth")).intValue();
            Metadata md=Objects.requireNonNull(result.get(url),"Missing parent").getMetadata();
            md.setValue("discovery.depth",Integer.toString(depth));
            md.setValue("discovery.source",Objects.toString(parent.get("source_url"),url).isBlank() ? url : Objects.toString(parent.get("source_url")));
            var document=Jsoup.parse(new String(content,java.nio.charset.StandardCharsets.UTF_8),url);
            document.select("base").remove();
            List<String> edges=new ArrayList<>();Set<String> seen=new HashSet<>();
            int examined=0;
            for(var anchor:document.select("a[href]")) {
                if (++examined>100) break;
                String target="", disposition="INVALID_URL";
                String raw=anchor.attr("href");
                if(raw.length()>2048) continue;
                try {
                    URI resolved=URI.create(url).resolve(raw);
                    // Keep HTTP graph identities, including foreign origins, as data only.
                    if (!Set.of("http","https").contains(resolved.getScheme()) || resolved.getHost()==null
                            || resolved.getRawUserInfo()!=null) continue;
                    target=resolved.toASCIIString().split("#",2)[0];
                    if(target.length()>2048 || !seen.add(target)) continue;
                    URI candidate=URI.create(target), origin=URI.create(url);
                    if (!Objects.equals(candidate.getHost(),origin.getHost()) || !Objects.equals(candidate.getScheme(),origin.getScheme())) {
                        disposition="SCOPE_REJECTED";
                    } else {
                        boolean safe=true;
                        try { target=canonicalTarget(target); }
                        catch (IOException rejected) { safe=false; disposition="INVALID_URL"; }
                        if (safe) {
                            Map<String,Object> admission=client.command(Map.of("action","reserve","session_id",session,
                                "url",target,"source_url",url,"depth",depth+1));
                            disposition=Objects.toString(admission.get("outcome"),"UNKNOWN");
                            target=Objects.toString(admission.get("url"),target);
                            if(!Set.of("RESERVED","ALREADY_RESERVED","LIMIT_REJECTED","SCOPE_REJECTED","DEPTH_REJECTED","DEPTH_LIMIT","INVALID_URL","METADATA_ONLY").contains(disposition))
                                throw new IOException("Admission refused source/session contract: "+disposition);
                        }
                    }
                } catch (IllegalArgumentException invalid) { disposition="INVALID_URL"; }
                edges.add(json.writeValueAsString(Map.of("source_url",url,"target_url",target,"disposition",disposition,
                    "discovered_at",Instant.now().toString(),"job_id",session)));
            }
            md.setValues("discovery.edges",edges.toArray(String[]::new));
            md.setValue("discovery.links.truncated",Boolean.toString(examined>100));
            // reserve already enqueued accepted URLs atomically. Never use legacy discovery.
            result.setOutlinks(Collections.emptyList());
        } catch (IOException e) { throw new IllegalStateException("Durable admission failed; parser must not acknowledge success",e); }
    }
}
