package dev.onionall.brawler.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.onionall.brawler.archive.ArchiveStore;
import dev.onionall.brawler.archive.SearchProvider;
import dev.onionall.brawler.admission.CrawlExecution;
import dev.onionall.brawler.tor.TorPolicy;
import java.net.URI;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Callable;
import picocli.CommandLine.*;

/** Offline product commands. Catalog persistence is deliberately not a scheduling queue. */
public final class ArchiveCommands {
    private ArchiveCommands() { }
    private static ArchiveStore store(Onionall root) throws IOException { return new ArchiveStore(root.archiveDirectory()); }
    private static final String ADMISSION_BLOCKER = "Configure a compatible durable Frontier server and pass --runtime with --admission-token-file; stock Frontier has no safe admission fallback";

    private static List<Map<String,Object>> manualLinks(byte[] html, String source) throws IOException {
        URI base = URI.create(source);
        var policy = new TorPolicy(Map.of("tor.mode","ONLY","tor.socks.host","127.0.0.1",
            "tor.socks.port",1,"tor.allowed.hosts",List.of(Objects.requireNonNull(base.getHost(),"Missing onion host"))));
        String canonical = policy.normalize(source);
        var document = HtmlFiles.parse(html,canonical);
        document.select("base").remove();
        List<Map<String,Object>> links = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for(var anchor:document.select("a[href]")) {
            if(links.size()>=100) break;
            String target="", disposition="SCOPE_REJECTED";
            try {
                String raw=anchor.attr("href");
                if(raw.length()>2048) throw new IllegalArgumentException("Link too long");
                target=policy.normalize(URI.create(canonical).resolve(raw).toString());
                disposition="MANUAL_UNSCHEDULED";
            } catch(Exception ignored) { /* A rejected link is never admitted. */ }
            String identity=target.isEmpty()?disposition:target;
            if(seen.add(identity)) links.add(Map.of("source_url",canonical,"target_url",target,"disposition",disposition));
        }
        return links;
    }

    @Command(name="crawl", mixinStandardHelpOptions=true,
            description="Create, approve and execute bounded plans through a configured durable Frontier.",
            subcommands={Plan.class, ShowPlan.class, Approve.class, Start.class, Status.class, Errors.class})
    public static final class Crawl implements Callable<Integer> {
        @ParentCommand Onionall root;
        public Integer call() throws IOException {
            root.print(Map.of("commands",List.of("plan","show","approve","start","status","errors"),"admission","BLOCKED","blocker",ADMISSION_BLOCKER));
            return 0;
        }
    }
    @Command(name="plan", mixinStandardHelpOptions=true, description="Persist a restrictive plan without network requests or admission.")
    public static final class Plan implements Callable<Integer> {
        @ParentCommand Crawl parent;
        @Option(names="--seed", description="Permitted onion seed URL; repeat for multiple seeds.") List<String> seeds = new ArrayList<>();
        @Option(names="--import", description="UTF-8 URL list, one URL per line; max32 KiB/100 lines.") Path imported;
        @Option(names="--scope", defaultValue="same-host") String scope;
        @Option(names="--depth", defaultValue="2") int depth;
        @Option(names="--max-pages", defaultValue="50") int maxPages;
        @Option(names="--provider", defaultValue="user-provided", description="User-supplied discovery attribution; no provider is contacted.") String provider;
        public Integer call() throws Exception {
            if (!"same-host".equals(scope)) throw new IllegalArgumentException("Only explicit same-host scope is supported");
            List<String> all = new ArrayList<>(seeds);
            if (imported != null) {
                if (!Files.isRegularFile(imported,LinkOption.NOFOLLOW_LINKS)) throw new IOException("Import must be a regular URL-list file");
                byte[] bytes;
                try(var input=Files.newInputStream(imported)) { bytes=input.readNBytes(32769); }
                if(bytes.length>32768) throw new IOException("URL import limited to32 KiB");
                String text=StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();
                var lines=text.lines().toList();
                if(lines.size()>100) throw new IOException("URL import limited to100 lines");
                for(String line:lines) if(!line.isBlank() && !line.stripLeading().startsWith("#")) all.add(line.strip());
            }
            if(all.isEmpty() || all.size()>100 || all.stream().anyMatch(url->url.length()>2048))
                throw new IllegalArgumentException("Require1..100 bounded seed URLs");
            try(var archive=store(parent.root)) { parent.root.print(archive.createPlan(all,depth,maxPages,provider)); }
            return 0;
        }
    }
    @Command(name="show", mixinStandardHelpOptions=true, description="Inspect the stored immutable crawl plan.")
    public static final class ShowPlan implements Callable<Integer> {
        @ParentCommand Crawl parent;
        @Parameters(index="0") String id;
        public Integer call() throws Exception { try(var archive=store(parent.root)) { parent.root.print(archive.plan(id)); } return 0; }
    }
    @Command(name="approve", mixinStandardHelpOptions=true, description="Explicitly approve a stored plan. Does not start crawling.")
    public static final class Approve implements Callable<Integer> {
        @ParentCommand Crawl parent;
        @Parameters(index="0") String id;
        public Integer call() throws Exception { try(var archive=store(parent.root)) { parent.root.print(archive.approvePlan(id)); } return 0; }
    }
    @Command(name="start", mixinStandardHelpOptions=true, description="Commit approved seeds through durable Frontier admission and run a bounded Storm worker.")
    public static final class Start implements Callable<Integer> {
        @ParentCommand Crawl parent;
        @Option(names="--plan", required=true) String id;
        @Option(names="--runtime", description="Operator-provided local Storm runtime JSON.") Path runtime;
        @Option(names="--admission-token-file", description="Private admission capability file.") Path token;
        public Integer call() throws Exception {
            if(runtime != null) {
                var result=CrawlExecution.start(parent.root.archiveDirectory(),id,runtime,token);
                parent.root.print(result);
                return Boolean.TRUE.equals(result.get("passed")) ? 0 : 6;
            }
            try(var archive=store(parent.root)) { archive.job(id); }
            parent.root.print(Map.of("passed",false,"exit_code",7,"plan_id",id,"admission","BLOCKED","blocker",ADMISSION_BLOCKER,"network_requests",0));
            return 7;
        }
    }
    @Command(name="status", mixinStandardHelpOptions=true, description="Read catalog outcomes and optional authoritative Frontier admission; never accesses Tor.")
    public static final class Status implements Callable<Integer> {
        @ParentCommand Crawl parent;
        @Parameters(index="0") String id;
        @Option(names="--runtime") Path runtime;
        @Option(names="--admission-token-file") Path token;
        public Integer call() throws Exception {
            var result=CrawlExecution.status(parent.root.archiveDirectory(),id,runtime,token);parent.root.print(result);
            return runtime!=null && !Boolean.TRUE.equals(result.get("authoritative_readback_current"))?6:0;
        }
    }
    @Command(name="errors", mixinStandardHelpOptions=true, description="Read bounded failure observations; not a verified fetch-attempt count.")
    public static final class Errors implements Callable<Integer> {
        @ParentCommand Crawl parent;
        @Parameters(index="0") String id;
        @Option(names="--limit",defaultValue="20") int limit;
        @Option(names="--cursor") String cursor;
        public Integer call() throws Exception {
            try(var archive=store(parent.root)){parent.root.print(archive.failureHistory(id,cursor,limit));}
            return 0;
        }
    }
    @Command(name="pages", mixinStandardHelpOptions=true, description="List locally archived snapshots; never opens or renders HTML.")
    public static final class Pages implements Callable<Integer> {
        @ParentCommand Onionall root;
        @Option(names="--job") String job;
        @Option(names="--limit", defaultValue="50") int limit;
        public Integer call() throws Exception { try(var archive=store(root)) { root.print(archive.pages(job,limit)); } return 0; }
    }
    @Command(name="page", mixinStandardHelpOptions=true, description="Inspect archived page metadata.", subcommands={ShowPage.class})
    public static final class Page implements Callable<Integer> {
        @ParentCommand Onionall root;
        public Integer call() throws IOException { root.print(Map.of("commands",List.of("show"))); return 0; }
    }
    @Command(name="show", mixinStandardHelpOptions=true, description="Show snapshot metadata, provenance and storage reference; no HTML execution.")
    public static final class ShowPage implements Callable<Integer> {
        @ParentCommand Page parent;
        @Parameters(index="0") String id;
        public Integer call() throws Exception { try(var archive=store(parent.root)) { parent.root.print(archive.page(id)); } return 0; }
    }
    @Command(name="search-local", mixinStandardHelpOptions=true, description="Search titles and extracted text in the local archive only.")
    public static final class SearchLocal implements Callable<Integer> {
        @ParentCommand Onionall root;
        @Parameters(index="0") String query;
        @Option(names="--job") String job;
        @Option(names="--limit", defaultValue="50") int limit;
        public Integer call() throws Exception { try(var archive=store(root)) { root.print(archive.search(query,job,limit)); } return 0; }
    }
    @Command(name="search-provider", mixinStandardHelpOptions=true,
            description="Prepare an official provider search URL for manual use; no provider HTTP request or scraping.")
    public static final class ProviderSearch implements Callable<Integer> {
        @ParentCommand Onionall root;
        @Parameters(index="0") String query;
        @Option(names="--provider",defaultValue="ahmia") String provider;
        @Option(names="--limit",defaultValue="20") int limit;
        public Integer call() throws Exception {
            if(!"ahmia".equals(provider)) throw new IllegalArgumentException("Only assisted Ahmia is supported; no Torch endpoint is configured");
            if(query.isBlank() || query.length()>256 || limit<1 || limit>100) throw new IllegalArgumentException("Require query1..256 characters and limit1..100");
            root.print(new SearchProvider.AssistedAhmia().search(query,limit));
            return 0;
        }
    }
    @Command(name="archive", mixinStandardHelpOptions=true, description="Import saved HTML or export bounded local metadata.", subcommands={ImportHtml.class, Export.class})
    public static final class Archive implements Callable<Integer> {
        @ParentCommand Onionall root;
        public Integer call() throws IOException { root.print(Map.of("commands",List.of("import","export"),"network_requests",0)); return 0; }
    }
    @Command(name="import", mixinStandardHelpOptions=true, description="Archive existing HTML under an approved plan; offline, not proof of a Tor fetch or robots permission.")
    public static final class ImportHtml implements Callable<Integer> {
        @ParentCommand Archive parent;
        @Parameters(index="0") Path file;
        @Option(names="--plan",required=true) String plan;
        @Option(names="--url",required=true) String url;
        public Integer call() throws Exception {
            if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)) throw new IOException("HTML import must be a regular file");
            byte[] body;
            try(var input=Files.newInputStream(file)) { body=input.readNBytes(131073); }
            if(body.length==0 || body.length>131072) throw new IOException("HTML import limited to1..131072 bytes");
            try(var archive=store(parent.root)) {
                var response=new LinkedHashMap<String,Object>(archive.importHtml(plan,url,body,manualLinks(body,url)));
                response.put("import_mode","MANUAL_IMPORT");response.put("transport_verified",false);response.put("robots_verified",false);response.put("network_requests",0);
                parent.root.print(response);
            }
            return 0;
        }
    }
    @Command(name="export", mixinStandardHelpOptions=true, description="Atomically export bounded snapshot metadata as JSON, refusing overwrite.")
    public static final class Export implements Callable<Integer> {
        @ParentCommand Archive parent;
        @Option(names="--job",required=true) String job;
        @Option(names="--limit",defaultValue="50") int limit;
        @Option(names="--output",required=true) Path output;
        public Integer call() throws Exception {
            Map<String,Object> summary;
            try(var archive=store(parent.root)) { summary=archive.exportSummary(job,limit); }
            HtmlFiles.save(new ObjectMapper().writeValueAsBytes(summary),output,false);
            parent.root.print(Map.of("passed",true,"saved",output.toAbsolutePath().toString(),"network_requests",0));
            return 0;
        }
    }
}
