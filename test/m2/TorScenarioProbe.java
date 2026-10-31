package dev.onionall.brawler.m2;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.onionall.brawler.tor.TorProtocol;
import java.nio.file.*;
import java.util.*;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import org.apache.storm.Config;
import org.apache.stormcrawler.Metadata;

/** Real protocol negative scenarios. Runtime results are written only after execution. */
public final class TorScenarioProbe {
    private static String randomOnion() throws Exception {
        byte[] key=new byte[32]; new java.security.SecureRandom().nextBytes(key);
        byte[] prefix=".onion checksum".getBytes(StandardCharsets.US_ASCII);
        byte[] checksumInput=new byte[prefix.length+33];
        System.arraycopy(prefix,0,checksumInput,0,prefix.length);
        System.arraycopy(key,0,checksumInput,prefix.length,32); checksumInput[checksumInput.length-1]=3;
        byte[] checksum=MessageDigest.getInstance("SHA3-256").digest(checksumInput);
        byte[] raw=Arrays.copyOf(key,35); raw[32]=checksum[0];raw[33]=checksum[1];raw[34]=3;
        String alphabet="abcdefghijklmnopqrstuvwxyz234567"; StringBuilder text=new StringBuilder();int bits=0,value=0;
        for(byte part:raw) { value=(value<<8)|(part&255);bits+=8;while(bits>=5){bits-=5;text.append(alphabet.charAt((value>>bits)&31));} }
        return text+".onion";
    }
    public static void main(String[] args) throws Exception {
        String scenario=args[0];
        String host=Files.readString(Path.of("/evidence/onion-hostname.txt")).trim();
        String unreachable=randomOnion();
        Config config=new Config();
        config.put("tor.mode","ONLY");config.put("tor.socks.host",args.length>1?args[1]:"tor");config.put("tor.socks.port",9050);
        config.put("tor.allowed.hosts",List.of(host,unreachable));config.put("http.timeout",10000);config.put("http.content.limit",65536);
        config.put("http.agent.name","OnionallBrawlerMTwoPolicy");
        String path="/health"; boolean successExpected=false;
        switch(scenario) {
            case "socks_port_unavailable" -> config.put("tor.socks.port",1);
            case "invalid_socks_endpoint" -> config.put("tor.socks.host","");
            case "onion_unreachable" -> host=unreachable;
            case "malformed_onion" -> host="invalid.onion";
            case "out_of_scope_redirect" -> path="/escape";
            case "redirect_loop" -> {path="/loop-a";successExpected=true;}
            case "timeout" -> {path="/slow";config.put("http.timeout",1000);}
            case "connection_reset" -> path="/reset";
            case "tor_shutdown_active_crawl" -> {path="/slow";config.put("http.timeout",30000);}
            case "safe_retry_after_tor_return" -> successExpected=true;
            case "tor_stopped", "bootstrap_incomplete" -> { }
            default -> throw new IllegalArgumentException("Unknown scenario");
        }
        Map<String,Object> result=new LinkedHashMap<>(); result.put("scenario",scenario);
        long started=System.nanoTime(); TorProtocol protocol=new TorProtocol();
        try {
            protocol.configure(config);
            var response=protocol.getProtocolOutput("http://"+host+path,new Metadata());
            boolean passed=successExpected && (scenario.equals("redirect_loop") ? response.getStatusCode()==302 && "/loop-b".equals(response.getMetadata().getFirstValue("location")) : response.getStatusCode()==200 && "ready".equals(new String(response.getContent(),StandardCharsets.UTF_8)));
            result.put("passed",passed);result.put("http_status",response.getStatusCode());
            if(scenario.equals("redirect_loop")) result.put("location",response.getMetadata().getFirstValue("location"));
        } catch(Exception error) {
            String category=error.getMessage()==null?error.getClass().getSimpleName():error.getMessage();
            boolean passed=false;
            if(Set.of("socks_port_unavailable","bootstrap_incomplete","onion_unreachable","connection_reset","tor_shutdown_active_crawl").contains(scenario))
                passed=error instanceof java.io.IOException && Set.of("SOCKS_FAILURE","ONION_UNREACHABLE","REQUEST_TIMEOUT").contains(category);
            if(scenario.equals("tor_stopped")) passed=(error instanceof java.io.IOException && Set.of("SOCKS_FAILURE","ONION_UNREACHABLE","REQUEST_TIMEOUT").contains(category)) || (error instanceof IllegalArgumentException && category.equals("Invalid SOCKS endpoint"));
            if(scenario.equals("malformed_onion") || scenario.equals("out_of_scope_redirect")) passed=category.contains("SCOPE_REJECTED");
            if(scenario.equals("invalid_socks_endpoint")) passed=error instanceof IllegalArgumentException && category.contains("SOCKS");
            if(scenario.equals("timeout")) passed=error instanceof java.io.IOException && category.equals("REQUEST_TIMEOUT") && Files.exists(Path.of("/evidence/slow-started"));
            result.put("passed",passed); result.put("category",category);result.put("exception_type",error.getClass().getName());
        } finally {protocol.cleanup();}
        result.put("elapsed_seconds",(System.nanoTime()-started)/1e9);
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(Path.of("/evidence/scenario-"+scenario+".json").toFile(),result);
        if(!Boolean.TRUE.equals(result.get("passed"))) throw new IllegalStateException("Scenario failed: "+scenario);
    }
}
