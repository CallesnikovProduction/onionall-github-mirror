package dev.onionall.brawler.admission;

import java.nio.file.*;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/** Launch identity mismatches are simulated; never signals an unrelated or recycled OS process. */
public class CrawlExecutionCleanupTest {
    @Rule public TemporaryFolder temporary=new TemporaryFolder();
    private Process child()throws Exception {
        Path root=temporary.newFolder().toPath();
        return new ProcessBuilder(CrawlWorkerTest.java(root,CrawlWorkerTest.Fixture.class.getName(),"30000"))
            .redirectErrorStream(true).redirectOutput(root.resolve("child.log").toFile()).start();
    }
    @Test public void unknownOrDifferentBirthCannotAuthorizeCliCleanup()throws Exception {
        Process child=child();Instant actual=child.info().startInstant().orElseThrow();
        try {
            CrawlExecution.stop(child,null);assertTrue(child.isAlive());
            CrawlExecution.stop(child,Instant.EPOCH);assertTrue(child.isAlive());
            assertEquals(actual,child.info().startInstant().orElseThrow());
        }finally{child.destroyForcibly();assertTrue(child.waitFor(5,TimeUnit.SECONDS));}
    }
    @Test public void matchingLaunchIdentityStopsOnlyTheOwnedWrapper()throws Exception {
        Process child=child();Instant actual=child.info().startInstant().orElseThrow();
        try {
            CrawlExecution.stop(child,actual);assertTrue(child.waitFor(5,TimeUnit.SECONDS));
            // Repeated cleanup of an exited Process must not look up a new PID/tree.
            CrawlExecution.stop(child,actual);assertFalse(child.isAlive());
        }finally{if(child.isAlive()){child.destroyForcibly();child.waitFor(5,TimeUnit.SECONDS);}}
    }
}
