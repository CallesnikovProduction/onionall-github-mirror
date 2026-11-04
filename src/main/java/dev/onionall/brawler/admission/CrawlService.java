package dev.onionall.brawler.admission;

import dev.onionall.brawler.archive.ArchiveStore;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/** Shared crawl application boundary. Frontier remains the only admission/scheduling authority. */
public final class CrawlService {
    public static final String ADMISSION_BLOCKER="Configure a compatible durable Frontier server and pass --runtime with --admission-token-file; stock Frontier has no safe admission fallback";
    public interface Execution {
        Map<String,Object> status(Path archive,String id,Path runtime,Path token)throws Exception;
        Map<String,Object> start(Path archive,String id,Path runtime,Path token)throws Exception;
    }
    private final Path archive;private final Execution execution;
    public CrawlService(Path archive) {
        this(archive,new Execution(){
            public Map<String,Object> status(Path root,String id,Path runtime,Path token)throws Exception {return CrawlExecution.status(root,id,runtime,token);}
            public Map<String,Object> start(Path root,String id,Path runtime,Path token)throws Exception {return CrawlExecution.start(root,id,runtime,token);}
        });
    }
    public CrawlService(Path archive,Execution execution) {this.archive=Objects.requireNonNull(archive);this.execution=Objects.requireNonNull(execution);}
    public Map<String,Object> createPlan(List<String> seeds,int depth,int maxPages,String provider)throws IOException {
        try(var catalog=new ArchiveStore(archive)){return catalog.createPlan(seeds,depth,maxPages,provider);}
    }
    public Map<String,Object> plans(String cursor,int limit)throws IOException {
        try(var catalog=new ArchiveStore(archive)){return catalog.plans(cursor,limit);}
    }
    public Map<String,Object> plan(String id)throws IOException {
        try(var catalog=new ArchiveStore(archive)){return catalog.plan(id);}
    }
    public Map<String,Object> approve(String id)throws IOException {
        try(var catalog=new ArchiveStore(archive)){return catalog.approvePlan(id);}
    }
    public Map<String,Object> errors(String id,String cursor,int limit)throws IOException {
        try(var catalog=new ArchiveStore(archive)){return catalog.failureHistory(id,cursor,limit);}
    }
    public Map<String,Object> status(String id,Path runtime,Path token)throws Exception {return execution.status(archive,id,runtime,token);}
    public Map<String,Object> start(String id,Path runtime,Path token)throws Exception {
        if(runtime!=null)return execution.start(archive,id,runtime,token);
        // Preserve the existing offline CLI contract: check the plan but never admit anonymously.
        try(var catalog=new ArchiveStore(archive)){catalog.job(id);}
        return Map.of("passed",false,"exit_code",7,"plan_id",id,"admission","BLOCKED","blocker",ADMISSION_BLOCKER,"network_requests",0);
    }
}
