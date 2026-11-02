package dev.onionall.brawler.m2;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.onionall.brawler.tor.TorProtocol;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.apache.stormcrawler.Metadata;
import org.apache.storm.Config;

/** Bounded real protocol readiness, not a guarantee about future Tor circuits. */
public final class RecoverySeriesProbe {
    public static void main(String[] args) throws Exception {
        if(args.length>1)throw new IllegalArgumentException("At most one evidence filename");
        String filename=args.length==0?"recovery-series.json":args[0];
        if(!filename.matches("[a-z][a-z0-9-]{0,63}\\.json"))
            throw new IllegalArgumentException("Use a safe local evidence JSON filename");
        String host=Files.readString(Path.of("/evidence/onion-hostname.txt")).trim();
        Config config=new Config();
        config.put("protocol.md.prefix","protocol."); config.put("tor.mode","ONLY");
        config.put("tor.socks.host","tor"); config.put("tor.socks.port",9050);
        config.put("tor.allowed.hosts",List.of(host)); config.put("http.timeout",20000);
        config.put("http.agent.name","OnionallBrawlerMTwoRecovery");
        if("1".equals(System.getenv("M2_TOR_DIAGNOSTICS")))config.put("tor.diagnostics.enabled",true);
        Map<String,Object> result=new LinkedHashMap<>();
        List<String> completed=new ArrayList<>(); result.put("completed",completed); result.put("passed",false);
        TorProtocol protocol=new TorProtocol();
        try {
            protocol.configure(config);
            for(int i=0;i<3;i++) {
                for(String path:List.of("/robots.txt","/health")) {
                    var response=protocol.getProtocolOutput("http://"+host+path,new Metadata());
                    if(response.getStatusCode()!=200) throw new IllegalStateException("Non-200 readiness");
                    String body=new String(response.getContent(),StandardCharsets.UTF_8);
                    if(path.equals("/health") ? !body.equals("ready") : !body.contains("Disallow: /blocked"))
                        throw new IllegalStateException("Unexpected controlled content");
                    completed.add(path);
                }
            }
            if(!protocol.getRobotRules("http://"+host+"/a").isAllowed("http://"+host+"/a"))
                throw new IllegalStateException("Controlled robots denied allowed path");
            result.put("passed",true);
        } finally {
            protocol.cleanup();
            new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(Path.of("/evidence",filename).toFile(),result);
        }
    }
}
