package dev.onionall.brawler.admission;

import java.time.Instant;
import org.junit.Test;
import static org.junit.Assert.*;

public class JobStatusTest {
    private static FrontierReadback.Counts counts(int pending, int held, int wait, int unscheduled, int unknown,
                                                 Integer done, Integer success, Integer terminal) {
        return new FrontierReadback.Counts("s", Instant.EPOCH, pending + held + wait + unscheduled + unknown,
            pending, held, wait, unscheduled, unknown, done, success, terminal, null,
            pending + held + wait == 0 ? null : Instant.EPOCH);
    }
    private static JobStatus.Assessment assess(FrontierReadback.Counts counts, Boolean alive, Integer exit) {
        return JobStatus.assess(true, true, true, counts, new JobStatus.Worker(alive, exit, "TEST_PROCESS_EVIDENCE"));
    }
    @Test public void stoppedWorkerWithTwoUnscheduledAndPendingIsPausedForAnyExitCode() {
        for (int exit : new int[]{0, 1, 124, 137}) {
            var result = assess(counts(1,0,0,2,0,null,null,null), false, exit);
            assertEquals(JobStatus.State.PAUSED, result.state());
            assertEquals(JobStatus.Completion.INCOMPLETE, result.completion());
        }
    }
    @Test public void longRetryOutlivesBoundedWorkerWithoutBecomingFailedOrComplete() {
        var result = assess(counts(0,0,1,2,0,null,null,null), false, 0);
        assertEquals(JobStatus.State.PAUSED, result.state());
        assertEquals(JobStatus.Completion.INCOMPLETE, result.completion());
    }
    @Test public void allUnscheduledWithoutTerminalProvenanceIsUnknownEvenAfterExitZero() {
        var result = assess(counts(0,0,0,3,0,null,null,null), false, 0);
        assertEquals(JobStatus.State.UNKNOWN, result.state());
        assertEquals(JobStatus.Completion.UNKNOWN, result.completion());
    }
    @Test public void unreachableSchedulerAndUnknownProcessDoNotProduceFalseCompletion() {
        assertEquals(JobStatus.State.UNKNOWN, assess(null, true, null).state());
        assertEquals(JobStatus.Completion.UNKNOWN, assess(null, false, 0).completion());
        assertEquals(JobStatus.State.UNKNOWN, assess(counts(1,0,0,2,0,null,null,null), null, 0).state());
        assertEquals(JobStatus.State.UNKNOWN, assess(counts(0,0,0,2,1,null,null,null), false, 0).state());
    }
    @Test public void runningProcessRemainsSeparateFromCompletion() {
        assertEquals(JobStatus.State.RUNNING, assess(counts(0,1,0,2,0,null,null,null), true, null).state());
        assertEquals(JobStatus.Completion.INCOMPLETE, assess(counts(0,1,0,2,0,null,null,null), true, null).completion());
        assertEquals(JobStatus.Completion.UNKNOWN, assess(counts(0,0,0,3,0,null,null,null), true, 0).completion());
    }
    @Test public void completeAndPartialRequireExplicitOutcomeProofNotStorageCompletion() {
        // Pure predicate fixtures; current real v1 Frontier cannot supply these nonnull proofs.
        assertEquals(JobStatus.State.COMPLETE, assess(counts(0,0,0,3,0,3,3,0), false, 0).state());
        assertEquals(JobStatus.Completion.COMPLETE, assess(counts(0,0,0,3,0,3,3,0), false, 0).completion());
        assertEquals(JobStatus.State.PARTIAL, assess(counts(0,0,0,3,0,2,2,1), false, 0).state());
        assertEquals(JobStatus.State.UNKNOWN, assess(counts(0,0,0,3,0,2,2,null), false, 0).state());
        assertEquals(JobStatus.State.UNKNOWN, assess(counts(0,0,0,3,0,3,2,0), false, 0).state());
    }
    @Test public void catalogApprovalDoesNotInventAdmissionOrCompletion() {
        var unknown = new JobStatus.Worker(null, null, "NO_OBSERVATION");
        assertEquals(JobStatus.State.DRAFT, JobStatus.assess(false,false,false,null,unknown).state());
        assertEquals(JobStatus.State.APPROVED, JobStatus.assess(true,false,false,null,unknown).state());
        assertEquals(JobStatus.State.UNKNOWN, JobStatus.assess(true,true,false,null,unknown).state());
        assertEquals(JobStatus.Completion.UNKNOWN, JobStatus.assess(true,false,false,null,unknown).completion());
    }
}
