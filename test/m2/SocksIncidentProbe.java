package dev.onionall.brawler.m2;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.onionall.brawler.tor.*;
import java.net.*;
import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.apache.storm.Config;
import org.apache.stormcrawler.Metadata;

/** One owned /health request; native JDK SOCKS versus the production protocol. */
public final class SocksIncidentProbe {
    public static void main(String[] args) throws Exception {
        if(args.length!=2 || !Set.of("native","protocol").contains(args[0]) || !args[1].matches("[a-z0-9-]+\\.json"))
            throw new IllegalArgumentException("mode and safe evidence filename required");
        String host=Files.readString(Path.of("/evidence/onion-hostname.txt")).trim();
        Config c=new Config();c.put("tor.mode","ONLY");c.put("tor.socks.host","tor");c.put("tor.socks.port",9050);
        c.put("tor.allowed.hosts",List.of(host));c.put("protocol.md.prefix","protocol.");
        c.put("http.timeout",20000);c.put("http.content.limit",65536);c.put("http.agent.name","OnionallIncident");c.put("tor.diagnostics.enabled",true);
        Map<String,Object> r=new LinkedHashMap<>();r.put("passed",false);r.put("mode",args[0]);r.put("deadline_ms",20000);
        r.put("network_namespace",Files.readSymbolicLink(Path.of("/proc/self/ns/net")).toString());
        long started=System.nanoTime(),deadline=started+20_000_000_000L;
        try {
            r.put("phase","policy");String url=normalize(c,"http://"+host+"/health");
            r.put("onion_dns","unresolved SOCKS hostname");
            if(args[0].equals("protocol")) {
                TorProtocol p=new TorProtocol();
                try {p.configure(c);r.put("phase","protocol_connect_or_http");var out=p.getProtocolOutput(url,new Metadata());
                    r.put("http_status",out.getStatusCode());r.put("passed",out.getStatusCode()==200 && Arrays.equals(out.getContent(),"ready".getBytes(StandardCharsets.US_ASCII)));
                }finally{p.cleanup();}
            } else {
                InetSocketAddress proxy=new InetSocketAddress("tor",9050);
                try(Socket s=new Socket(new Proxy(Proxy.Type.SOCKS,proxy))) {
                    r.put("phase","native_socks_connect");s.connect(InetSocketAddress.createUnresolved(host,80),20000);
                    r.put("phase","http");s.getOutputStream().write(("GET /health HTTP/1.1\r\nHost: "+host+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                    ByteArrayOutputStream b=new ByteArrayOutputStream();byte[] buf=new byte[4096];
                    while(true){long left=(deadline-System.nanoTime())/1_000_000;if(left<=0)throw new SocketTimeoutException();s.setSoTimeout((int)Math.max(1,left));int n=s.getInputStream().read(buf);if(n<0)break;b.write(buf,0,n);if(b.size()>65536)throw new IOException("SIZE_LIMIT");}
                    String response=b.toString(StandardCharsets.US_ASCII);r.put("http_status",response.startsWith("HTTP/1.0 200")?200:0);r.put("passed",response.startsWith("HTTP/1.0 200") && response.endsWith("ready"));
                }
            }
            if(Boolean.TRUE.equals(r.get("passed")))r.put("phase","complete");
        } catch(IOException e){r.put("error_type",e.getClass().getSimpleName());}
        finally {r.put("elapsed_ms",(System.nanoTime()-started)/1_000_000);new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(Path.of("/evidence",args[1]).toFile(),r);}
        if(!Boolean.TRUE.equals(r.get("passed")))System.exit(1);
    }
    static String normalize(Config c,String u)throws IOException{return new TorPolicy(c).normalize(u);}
}
