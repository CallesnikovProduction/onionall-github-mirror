package dev.onionall.brawler.admission;

import io.grpc.CallCredentials;
import io.grpc.Metadata;
import io.grpc.Status;
import java.io.IOException;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/** File and completion boundary; loopback adapter tests establish no RPC fallback. */
public class FrontierCapabilityTest {

    private static final String TOKEN = "synthetic-capability-aaaaaaaaaaaaaaaaaaaaaaaa";
    private static final Metadata.Key<String> HEADER = Metadata.Key.of(
            "x-onionall-admission-token", Metadata.ASCII_STRING_MARSHALLER);

    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void acceptsExactByteBoundaryAndExistingWhitespaceContract() throws Exception {
        Path file = tokenFile(TOKEN);
        for (String valid : List.of("a".repeat(32), "b".repeat(256), "b".repeat(256) + "\n",
                " \t" + TOKEN + "\r\n")) {
            Files.writeString(file, valid);
            assertEquals(valid.strip(), FrontierCapability.token(file));
        }
    }

    @Test
    public void rejectsMissingDirectoryMalformedAndOversizedFilesWithoutDetails() throws Exception {
        Path file = temporary.newFolder().toPath().resolve("secret-path-tripwire");
        assertUnavailable(file);
        assertUnavailable(null);
        assertUnavailable(file.getParent());
        for (String invalid : List.of("", "short", "a".repeat(31), "a".repeat(257),
                TOKEN + " invalid-content-tripwire", "a".repeat(256) + "\r\n", "a".repeat(4096))) {
            Files.writeString(file, invalid);
            assertUnavailable(file);
        }
        Files.write(file, new byte[] {(byte) 0xff, (byte) 0xfe});
        assertUnavailable(file);
        IllegalArgumentException invalidPath = assertThrows(IllegalArgumentException.class,
                () -> FrontierCapability.from(Map.of("admission.token.file", "secret-path-tripwire\0")));
        assertEquals("Frontier capability unavailable", invalidPath.getMessage());
        assertNull(invalidPath.getCause());
    }

    @Test
    public void rejectsFinalComponentSymlinkWithoutFollowingTarget() throws Exception {
        Path target = tokenFile(TOKEN);
        Path link = temporary.newFolder().toPath().resolve("credential-link");
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | IOException | SecurityException unavailable) {
            Assume.assumeNoException("Symlinks unavailable in this environment", unavailable);
        }
        assertUnavailable(link);
        assertEquals(TOKEN, Files.readString(target));
    }

    @Test
    public void openedReadKeepsItsHandleWhenPathIsReplacedAndRejectsGrowth() throws Exception {
        Path file = tokenFile(TOKEN);
        try (SeekableByteChannel opened = Files.newByteChannel(file,
                Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
            Path replacement = tokenFile("b".repeat(4096));
            Files.move(replacement, file, StandardCopyOption.REPLACE_EXISTING);
            assertEquals(TOKEN, FrontierCapability.readToken(opened));
        }
        Files.writeString(file, TOKEN);
        try (SeekableByteChannel opened = Files.newByteChannel(file,
                Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
            Files.writeString(file, "b".repeat(4096));
            IOException failure = assertThrows(IOException.class, () -> FrontierCapability.readToken(opened));
            assertEquals("Frontier capability unavailable", failure.getMessage());
        }
    }

    @Test
    public void preservesOperatorManagedReadablePosixFiles() throws Exception {
        Path file = tokenFile(TOKEN);
        Assume.assumeTrue(Files.getFileStore(file).supportsFileAttributeView("posix"));
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"));
        assertEquals(TOKEN, FrontierCapability.token(file));
    }

    @Test
    public void unreadableFileFailsWithoutPathOrCauseWhenAclIsEffective() throws Exception {
        Path file = tokenFile(TOKEN);
        Assume.assumeTrue(Files.getFileStore(file).supportsFileAttributeView("posix"));
        try {
            Files.setPosixFilePermissions(file, Set.of());
            Assume.assumeFalse("Process can bypass file permissions", Files.isReadable(file));
            assertUnavailable(file);
        } finally {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        }
    }

    @Test
    public void credentialsReloadOnEveryRpcAndFailWithoutHeadersAfterDeletion() throws Exception {
        Path file = tokenFile(TOKEN);
        FrontierCapability credentials = FrontierCapability.fromFile(file);
        RecordingApplier first = new RecordingApplier();
        credentials.applyRequestMetadata(null, Runnable::run, first);
        assertEquals(TOKEN, first.headers.get(HEADER));
        String changed = "synthetic-capability-bbbbbbbbbbbbbbbbbbbbbbbb";
        Files.writeString(file, changed);
        RecordingApplier second = new RecordingApplier();
        credentials.applyRequestMetadata(null, Runnable::run, second);
        assertEquals(changed, second.headers.get(HEADER));
        Files.delete(file);
        RecordingApplier missing = new RecordingApplier();
        credentials.applyRequestMetadata(null, Runnable::run, missing);
        assertDeniedOnce(missing);
        Files.writeString(file, "secret-content-tripwire invalid token with spaces");
        RecordingApplier malformed = new RecordingApplier();
        credentials.applyRequestMetadata(null, Runnable::run, malformed);
        assertDeniedOnce(malformed);
    }

    @Test
    public void rejectedAndScheduledThenRejectedWorkTerminatesExactlyOnce() throws Exception {
        FrontierCapability credentials = FrontierCapability.fromFile(tokenFile(TOKEN));
        RecordingApplier rejected = new RecordingApplier();
        credentials.applyRequestMetadata(null,
                task -> { throw new RejectedExecutionException("secret-path-tripwire"); }, rejected);
        assertDeniedOnce(rejected);
        AtomicReference<Runnable> queued = new AtomicReference<>();
        RecordingApplier scheduled = new RecordingApplier();
        Executor queueThenReject = task -> {
            queued.set(task);
            throw new RejectedExecutionException("secret-path-tripwire");
        };
        credentials.applyRequestMetadata(null, queueThenReject, scheduled);
        queued.get().run();
        assertDeniedOnce(scheduled);
    }

    @Test
    public void repeatedTasksAndThrowingSuccessCallbackDoNotTriggerFailureCallback() throws Exception {
        FrontierCapability credentials = FrontierCapability.fromFile(tokenFile(TOKEN));
        RecordingApplier repeated = new RecordingApplier();
        credentials.applyRequestMetadata(null, task -> {
            task.run();
            task.run();
            throw new RejectedExecutionException("executor threw after completion");
        }, repeated);
        assertEquals(1, repeated.applied);
        assertEquals(0, repeated.failed);
        RecordingApplier throwing = new RecordingApplier();
        throwing.throwOnApply = true;
        credentials.applyRequestMetadata(null, Runnable::run, throwing);
        assertEquals(1, throwing.applied);
        assertEquals(0, throwing.failed);
    }

    @Test
    public void throwingFailureCallbackIsNotInvokedTwice() throws Exception {
        Path file = tokenFile(TOKEN);
        FrontierCapability credentials = FrontierCapability.fromFile(file);
        Files.delete(file);
        RecordingApplier throwing = new RecordingApplier();
        throwing.throwOnFail = true;
        credentials.applyRequestMetadata(null, Runnable::run, throwing);
        assertDeniedOnce(throwing);
    }

    private Path tokenFile(String content) throws IOException {
        Path file = temporary.newFile().toPath();
        Files.writeString(file, content);
        return file;
    }

    private static void assertUnavailable(Path file) {
        IOException failure = assertThrows(IOException.class, () -> FrontierCapability.token(file));
        assertEquals("Frontier capability unavailable", failure.getMessage());
        assertNull(failure.getCause());
        assertEquals(0, failure.getSuppressed().length);
    }

    private static void assertDeniedOnce(RecordingApplier applier) {
        assertEquals(0, applier.applied);
        assertEquals(1, applier.failed);
        assertNull(applier.headers);
        assertEquals(Status.Code.PERMISSION_DENIED, applier.status.getCode());
        assertEquals("Frontier capability unavailable", applier.status.getDescription());
        assertNull(applier.status.getCause());
    }

    private static final class RecordingApplier extends CallCredentials.MetadataApplier {
        int applied;
        int failed;
        Metadata headers;
        Status status;
        boolean throwOnApply;
        boolean throwOnFail;

        @Override
        public void apply(Metadata headers) {
            applied++;
            this.headers = headers;
            if (throwOnApply) {
                throw new IllegalStateException("callback failed");
            }
        }

        @Override
        public void fail(Status status) {
            failed++;
            this.status = status;
            if (throwOnFail) {
                throw new IllegalStateException("callback failed");
            }
        }
    }
}
