package dev.onionall.brawler.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.onionall.brawler.tools.OnionSmoke;
import dev.onionall.brawler.tor.*;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Callable;
import org.apache.storm.Config;
import org.apache.stormcrawler.Metadata;
import org.apache.stormcrawler.protocol.ProtocolResponse;
import picocli.CommandLine;
import picocli.CommandLine.*;

@Command(name="onionall", description="Bounded Tor-native HTML fetch and offline inspection.",
        mixinStandardHelpOptions=true, version="onionall 0.1.0-SNAPSHOT (Java 25)",
        subcommands={Onionall.Fetch.class, Onionall.Inspect.class, Onionall.Status.class, Onionall.Discover.class})
public final class Onionall implements Callable<Integer> {
    @Option(names="--json", scope=ScopeType.INHERIT, description="Machine-readable result, no branding or animation.") boolean json;
    @Option(names="--no-color", scope=ScopeType.INHERIT) boolean noColor;
    @Option(names="--socks-port", scope=ScopeType.INHERIT, defaultValue="19050") int port;
    @Option(names="--timeout-ms", scope=ScopeType.INHERIT, defaultValue="30000") long timeout;
    @Option(names="--max-bytes", scope=ScopeType.INHERIT, defaultValue="131072") int limit;
    @Option(names="--download-dir", scope=ScopeType.INHERIT, description="Default output directory (or ONIONALL_DOWNLOAD_DIR).") Path directory;
    @Spec Model.CommandSpec spec;
    final Map<String,String> env;
    final boolean tty;
    private boolean interactiveContent;
    final Transport transport;
    interface Transport { ProtocolResponse fetch(Config config, String url) throws IOException; }
    Onionall(boolean tty, Map<String,String> env, Transport transport) { this.tty=tty; this.env=env; this.transport=transport; }
    private PrintWriter out() { return spec.commandLine().getOut(); }
    private PrintWriter err() { return spec.commandLine().getErr(); }
    private boolean color() { return tty && !json && !noColor && !env.containsKey("NO_COLOR") && !"dumb".equals(env.get("TERM")); }
    Path downloads() {
        if (directory != null) return directory;
        if (env.containsKey("ONIONALL_DOWNLOAD_DIR")) return Path.of(env.get("ONIONALL_DOWNLOAD_DIR"));
        try {
            Path pom=Path.of("pom.xml");
            if (Files.isRegularFile(pom) && Files.size(pom)<65536 && Files.readString(pom).contains("<artifactId>onionall-brawler</artifactId>"))
                return Path.of(".runtime/downloads");
        } catch(IOException ignored) { /* Installed mode does not require a source checkout. */ }
        return Path.of(env.getOrDefault("XDG_DATA_HOME", System.getProperty("user.home")+"/.local/share"), "onionall", "downloads");
    }
    Config config() {
        if (port < 1 || port > 65535 || timeout < 1 || timeout > 120000 || limit < 1 || limit > 131072)
            throw new IllegalArgumentException("Require port 1..65535, timeout 1..120000 ms, max-bytes 1..131072");
        Config c = new Config(); c.put("tor.mode","ONLY"); c.put("tor.socks.host","127.0.0.1"); c.put("tor.socks.port",port);
        c.put("tor.allowed.hosts",List.of(OnionSmoke.HOST));c.put("protocol.md.prefix","protocol.");
        c.put("http.timeout",timeout);c.put("http.content.limit",limit);c.put("tor.discovery.html.only",true);
        c.put("http.agent.name","OnionallBrawlerCLI");return c;
    }
    static String normalize(Config config, String url) throws IOException {
        return new TorPolicy(config).normalize(url);
    }
    void print(Map<String,Object> result) throws IOException {
        if (json) out().println(new ObjectMapper().writeValueAsString(result).replaceAll("[\\p{Cf}]", ""));
        else if(result.containsKey("socks_tcp_reachable")) { for(String line:CliPresentation.status(result,interactiveContent)) out().println(CliPresentation.style(line,color())); }
        else if(interactiveContent) { for(String line:InteractiveSession.summary(result)) out().println(Brand.safe(line)); }
        else {
            if (color()) out().print("\033[38;2;167;139;250mONIONALL BRAWLER\033[0m\n");
            for (var entry : result.entrySet()) out().printf("%-18s %s%n", entry.getKey(), Brand.safe(String.valueOf(entry.getValue())));
        }
        out().flush();
    }
    public Integer call() throws Exception {
        if (json) { print(new LinkedHashMap<>(Map.of("commands",List.of("fetch","inspect","status","discover"),"version","0.1.0-SNAPSHOT"))); return 0; }
        if (!tty) { spec.commandLine().usage(out()); return 0; }
        try(var session=new InteractiveSession(out(),env,color())) {
            PrintWriter screen=session.writer();
            while(true) {
                session.begin();
                screen.println("(1)  Fetch onion page\n(2)  Inspect HTML\n(3)  Tor SOCKS status\n(4)  Help\n(0)  Quit");
                if(session.availableRows()>=7)screen.println();
                String choice=prompt(screen,Brand.blocks(env)?"Choose ❯ ":"Choose > ");
                if(choice==null || choice.equals("0"))return 0;
                session.begin();String[] args;
                switch(choice) {
                    case "1" -> {String url=prompt(screen,"URL > ");if(url==null)return 0;args=new String[]{"fetch",url};}
                    case "2" -> {String file=prompt(screen,"File > ");if(file==null)return 0;args=new String[]{"inspect",file};}
                    case "3" -> args=new String[]{"status"};
                    default -> args=new String[]{"--help"};
                }
                session.begin();
                var next=new ArrayList<>(List.of(args));
                next.addAll(List.of("--socks-port",String.valueOf(port),"--timeout-ms",String.valueOf(timeout),"--max-bytes",String.valueOf(limit),"--download-dir",downloads().toString()));
                next.add("--no-color");
                java.util.concurrent.ScheduledExecutorService feedback=null;
                if(choice.equals("1") && color()) {
                    long start=System.nanoTime();
                    feedback=java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"onionall-menu-progress");t.setDaemon(true);return t;});
                    feedback.scheduleAtFixedRate(()->session.progress("Running fetch... "+String.format(java.util.Locale.ROOT,"%.1f s",(System.nanoTime()-start)/1e9)),0,120,java.util.concurrent.TimeUnit.MILLISECONDS);
                }
                try {execute(next.toArray(String[]::new),screen,screen,false,env,transport,true);}
                finally {if(feedback!=null){feedback.shutdownNow();feedback.awaitTermination(1,java.util.concurrent.TimeUnit.SECONDS);}session.stopProgress();}
                if(prompt(screen,"Enter to return > ")==null)return 0;
            }
        }
    }
    private static String prompt(PrintWriter screen,String label) {
        screen.print(label);screen.flush();
        String value=System.console().readLine();screen.println();return value;
    }
    @Command(name="fetch", mixinStandardHelpOptions=true, description="One HTML GET. Only the approved Ahmia v3 host; no redirects or retries.")
    static final class Fetch implements Callable<Integer> {
        @ParentCommand Onionall root;
        @Parameters(index="0", paramLabel="URL") String url;
        @Option(names={"-o","--output"}) Path output;
        @Option(names="--overwrite") boolean overwrite;
        public Integer call() throws Exception {
            Config c=root.config(); String safe;
            try { safe=normalize(c,url); } catch(IOException e) { return root.failure(3,e); }
            Path target=output==null?root.downloads().resolve("ahmia.html"):output;
            if (!overwrite && Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return root.failure(6,new IOException("Output exists; choose another path or --overwrite"));
            if(root.tty && !root.json && !root.color()) { root.err().println("Waiting for TorProtocol response (SOCKS ONLY)...");root.err().flush(); }
            long start=System.nanoTime();ProtocolResponse response;
            try (var activity=new Brand.Activity(root.err(),root.color())) { response=root.transport.fetch(c,safe); }
            catch (IOException e) { return root.failure(4,e); }
            Map<String,Object> result=new LinkedHashMap<>();result.put("http_status",response.getStatusCode());result.put("bytes",response.getContent().length);
            result.put("elapsed_seconds",(System.nanoTime()-start)/1e9);
            if (response.getStatusCode()!=200 || response.getContent().length==0) {
                result.put("passed",false);result.put("error","Expected nonempty HTTP 200 HTML; redirects are not followed");root.print(result);return 5;
            }
            String type=response.getMetadata().getFirstValue("content-type");
            if (type==null || !type.split(";",2)[0].trim().equalsIgnoreCase("text/html") || response.getContent().length>root.limit)
                return root.failure(5,new IOException("Expected bounded text/html"));
            String title=HtmlFiles.parse(response.getContent(),safe).title();
            HtmlFiles.save(response.getContent(),target,overwrite);
            result.put("title",title);result.put("saved",target.toAbsolutePath().toString());result.put("passed",true);root.print(result);return 0;
        }
    }
    @Command(name="discover", mixinStandardHelpOptions=true,
            description="Offline bounded onion link report. No network or Frontier admission.")
    static final class Discover implements Callable<Integer> {
        @ParentCommand Onionall root;
        @Parameters(index="0") Path file;
        @Option(names="--base-url", required=true) String base;
        @Option(names="--export", description="Create deterministic JSONL (no overwrite); not an admission queue.") Path export;
        public Integer call() throws Exception {
            var report=DiscoveryReport.discover(file,base,root.config());
            if(export!=null) {
                if(Files.exists(export) && Files.isSameFile(file,export))
                    throw new IOException("Export must not overwrite input");
                var json=new ObjectMapper();StringBuilder lines=new StringBuilder();
                for(var link:report.links())lines.append(json.writeValueAsString(link)).append('\n');
                HtmlFiles.save(lines.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8),export,false);
            }
            if(root.json)root.print(report.summary());
            else {
                root.out().println("DISCOVERY RESULTS");
                for(var item:report.summary().entrySet()) {
                    if(item.getKey().equals("links"))continue;
                    root.out().printf("%-18s %s%n",item.getKey(),Brand.safe(String.valueOf(item.getValue())));
                }
                root.out().println("Discovery completed. No network requests performed.");root.out().flush();
            }
            return 0;
        }
    }
    @Command(name="inspect", mixinStandardHelpOptions=true, description="Offline HTML summary, at most 4 MiB; never follows links.")
    static final class Inspect implements Callable<Integer> {
        @ParentCommand Onionall root; @Parameters(index="0") Path file;
        public Integer call() throws Exception { root.print(HtmlFiles.inspect(file));return 0; }
    }
    @Command(name="status", mixinStandardHelpOptions=true, description="Local SOCKS TCP reachability only; no Tor/onion connectivity claim.")
    static final class Status implements Callable<Integer> {
        @ParentCommand Onionall root;
        public Integer call() throws Exception {
            root.config();boolean reachable=false;
            try(var socket=new Socket()){socket.connect(new InetSocketAddress("127.0.0.1",root.port),1500);reachable=true;}catch(IOException ignored) { }
            Map<String,Object> r=new LinkedHashMap<>();r.put("version","0.1.0-SNAPSHOT");r.put("java",Runtime.version().toString());
            r.put("socks_endpoint","127.0.0.1:"+root.port);r.put("socks_tcp_reachable",reachable);r.put("tor_connectivity","NOT_TESTED");r.put("onion_connectivity","NOT_TESTED");
            r.put("downloads",root.downloads().toAbsolutePath().toString());r.put("timeout_ms",root.timeout);r.put("max_bytes",root.limit);
            r.put("allowed_host",OnionSmoke.HOST);r.put("features",List.of("fetch","inspect","status","discover"));r.put("m2_m3_acceptance","BLOCKED");root.print(r);return 0;
        }
    }
    int failure(int code, Exception e) throws IOException {
        List<String> causes=new ArrayList<>();for(Throwable t=e;t!=null;t=t.getCause()) causes.add(t.getClass().getSimpleName()+": "+Brand.safe(t.getMessage()));
        if(json) print(new LinkedHashMap<>(Map.of("passed",false,"exit_code",code,"errors",causes)));
        else { err().println("Error: "+String.join(" -> ",causes));err().flush(); }return code;
    }
    static int execute(String[] args, PrintWriter out, PrintWriter err, boolean tty, Map<String,String> env, Transport transport) {
        return execute(args,out,err,tty,env,transport,false);
    }
    private static int execute(String[] args, PrintWriter out, PrintWriter err, boolean tty, Map<String,String> env, Transport transport, boolean interactiveContent) {
        Onionall root=new Onionall(tty,env,transport);root.interactiveContent=interactiveContent;
        CommandLine cli=new CommandLine(root).setOut(out).setErr(err).setColorScheme(CommandLine.Help.defaultColorScheme(CommandLine.Help.Ansi.OFF));
        cli.setParameterExceptionHandler((e,a)->{ root.json=root.json || Arrays.asList(args).contains("--json") || Arrays.asList(args).contains("--json=true"); return root.failure(2,e); });
        cli.setExecutionExceptionHandler((e,line,parse)->root.failure(e instanceof IllegalArgumentException?2:e instanceof IOException?6:1,e));
        cli.setExecutionStrategy(parsed -> {
            var leaf=parsed;
            while(leaf.hasSubcommand()) leaf=leaf.subcommand();
            if(root.json && (leaf.isUsageHelpRequested() || leaf.isVersionHelpRequested())) {
                try { root.print(new LinkedHashMap<>(Map.of("version","0.1.0-SNAPSHOT","commands",List.of("fetch","inspect","status","discover"),"help","Use --help without --json for option details"))); }
                catch(IOException e) { return 6; }
                return 0;
            }
            return new CommandLine.RunLast().execute(parsed);
        });
        try { return cli.execute(args); }
        catch(LinkageError e) {
            try { return root.failure(1,new IllegalStateException("Incomplete CLI runtime",e)); }
            catch(IOException output) { err.println("Cannot write CLI result");return 6; }
        }
    }
    private static ProtocolResponse realFetch(Config config,String url) throws IOException {
        TorProtocol protocol=new TorProtocol();Thread shutdown=new Thread(protocol::cleanup,"onionall-cancel");
        Runtime.getRuntime().addShutdownHook(shutdown);
        try { protocol.configure(config);return protocol.getProtocolOutput(url,new Metadata()); }
        finally { protocol.cleanup();try{Runtime.getRuntime().removeShutdownHook(shutdown);}catch(IllegalStateException stopping){ } }
    }
    public static void main(String[] args) {
        System.exit(execute(args,new PrintWriter(System.out,true),new PrintWriter(System.err,true),System.console()!=null,System.getenv(),Onionall::realFetch));
    }
}
