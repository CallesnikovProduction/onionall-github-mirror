// SPDX-License-Identifier: Apache-2.0
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.zip.*;

/** Build tooling only. No application classes or dependency versions are changed. */
public class FrontierBuild {
    static final String PIN = "ff1b774bf81343234e6f34878f8b7c9a826371b3e305e96bf0e204895e3562df";
    static final String VERSION = "2.4-ff1b774b-deps1";
    static final String ARTIFACT = "urlfrontier-runtime-dependencies";
    static final String SERVICE = "crawlercommons/urlfrontier/service/";

    static String sha(Path file, String algorithm) throws Exception {
        MessageDigest digest = MessageDigest.getInstance(algorithm);
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[65536];
            for (int n; (n = input.read(buffer)) != -1;) digest.update(buffer, 0, n);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    static Path repo(Path module) {
        return module.resolve("../.runtime/frontier-maven-repository").normalize();
    }

    static Path artifact(Path module) {
        return repo(module).resolve("dev/onionall/pinned/" + ARTIFACT + "/" + VERSION);
    }

    static Path dependencies(Path module) {
        return artifact(module).resolve(ARTIFACT + "-" + VERSION + ".jar");
    }

    static Path baseline(Path module) {
        return repo(module).resolve("upstream/urlfrontier-service.jar");
    }

    static void checkBaseline(Path path) throws Exception {
        require(Files.isRegularFile(path), "Pinned service JAR missing. Run FrontierBuild.java prepare <JAR> <module> first.");
        require(PIN.equals(sha(path, "SHA-256")), "Baseline service JAR checksum mismatch: " + path);
    }

    static boolean replaced(String name) {
        return name.startsWith(SERVICE) && name.endsWith(".class");
    }

    static void prepare(Path input, Path module) throws Exception {
        checkBaseline(input); // Check before producing any dependency or copying input.
        Path directory = artifact(module);
        Files.createDirectories(directory);
        Files.createDirectories(baseline(module).getParent());
        if (!input.toRealPath().equals(baseline(module).toAbsolutePath().normalize()))
            Files.copy(input, baseline(module), StandardCopyOption.REPLACE_EXISTING);
        Path temporary = Files.createTempFile(directory, "dependencies-", ".jar");
        try (ZipFile source = new ZipFile(input.toFile());
             ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(temporary))) {
            for (ZipEntry entry : Collections.list(source.entries())) {
                if (replaced(entry.getName())) continue;
                ZipEntry copy = new ZipEntry(entry.getName());
                copy.setTimeLocal(java.time.LocalDateTime.of(2026, 11, 3, 0, 0));
                output.putNextEntry(copy);
                if (!entry.isDirectory()) try (InputStream data = source.getInputStream(entry)) { data.transferTo(output); }
                output.closeEntry();
            }
        }
        Files.move(temporary, dependencies(module), StandardCopyOption.REPLACE_EXISTING);
        Path pom = directory.resolve(ARTIFACT + "-" + VERSION + ".pom");
        Files.writeString(pom, "<project xmlns=\"http://maven.apache.org/POM/4.0.0\"><modelVersion>4.0.0</modelVersion>"
                + "<groupId>dev.onionall.pinned</groupId><artifactId>" + ARTIFACT + "</artifactId><version>"
                + VERSION + "</version><description>Dependencies from SHA256 " + PIN
                + "; vendored service classes excluded</description></project>\n");
        for (Path file : List.of(dependencies(module), pom)) {
            Files.writeString(Path.of(file + ".sha1"), sha(file, "SHA-1"));
            Files.writeString(Path.of(file + ".sha256"), sha(file, "SHA-256"));
        }
        verify(module);
        System.out.println("Prepared pinned dependency repository: " + repo(module));
    }

    static void verify(Path module) throws Exception {
        checkBaseline(baseline(module));
        // Compare every retained entry with the pinned binary, not just a mutable checksum sidecar.
        try (ZipFile original = new ZipFile(baseline(module).toFile());
             ZipFile filtered = new ZipFile(dependencies(module).toFile())) {
            int retained = 0;
            for (ZipEntry entry : Collections.list(original.entries())) {
                if (replaced(entry.getName())) {
                    String top = entry.getName().split("\\$", 2)[0].replaceAll("\\.class$", "") + ".java";
                    require(Files.isRegularFile(module.resolve("src/main/java").resolve(top)), "Unvendored service class: " + entry.getName());
                    continue;
                }
                retained++;
                ZipEntry copy = filtered.getEntry(entry.getName());
                require(copy != null, "Missing pinned entry: " + entry.getName());
                try (InputStream a = original.getInputStream(entry); InputStream b = filtered.getInputStream(copy)) {
                    require(Arrays.equals(a.readAllBytes(), b.readAllBytes()), "Changed pinned entry: " + entry.getName());
                }
            }
            require(filtered.size() == retained, "Unexpected entries in filtered dependency JAR");
        }
        // Also verify the dependency Maven actually resolved, if supplied by the caller.
        System.out.println("PINNED_DEPENDENCIES_PASS " + PIN);
    }

    static void run(List<String> command, Path log) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        if (log == null) builder.inheritIO(); else builder.redirectOutput(log.toFile());
        Process process = builder.start();
        if (!process.waitFor(180, TimeUnit.SECONDS)) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            throw new IllegalStateException("Build subprocess timed out; log: " + log);
        }
        require(process.exitValue() == 0, "Build subprocess exit " + process.exitValue() + "; log: " + log);
    }

    static String jdk(String executable) {
        return Path.of(System.getProperty("java.home"), "bin", executable).toString();
    }

    static void test(Path module) throws Exception {
        Path evidence = Files.createTempDirectory(module.resolve("target"), "maven-conformance-");
        String cp = module.resolve("target/classes") + File.pathSeparator + module.resolve("target/test-classes")
                + File.pathSeparator + dependencies(module);
        for (String name : List.of("AdmissionConformance", "AdmissionMigrationConformance",
                "AdmissionShutdownConformance", "AdmissionRpcConformance",
                "RecoveryCountersConformance", "AdmissionStateConformance",
                "AdmissionAmbiguousRpcConformance")) {
            List<String> command = new ArrayList<>(List.of(jdk("java"), "--enable-native-access=ALL-UNNAMED", "-cp", cp,
                    "crawlercommons.urlfrontier.service.rocksdb." + name, evidence.resolve(name).toString()));
            if (name.equals("AdmissionMigrationConformance")) command.add(baseline(module).toString());
            run(command, evidence.resolve(name + ".log"));
            System.out.println(name + " PASS");
        }
        System.out.println("Conformance evidence: " + evidence);
    }

    static void assemble(Path module) throws Exception {
        verify(module);
        Path output = module.resolve("target/urlfrontier-admission.jar");
        Files.copy(baseline(module), output, StandardCopyOption.REPLACE_EXISTING);
        run(List.of(jdk("jar"), "--update", "--file", output.toString(), "--date=2026-11-03T00:00:00Z",
                "-C", module.resolve("target/classes").toString(), "."), null);
        System.out.println("Candidate SHA256 " + sha(output, "SHA-256"));
    }

    public static void main(String[] args) throws Exception {
        require(args.length >= 2, "Usage: FrontierBuild.java prepare <pinned-JAR> <module> | verify/test/package <module> [resolved-dependency-JAR]");
        Path module = Path.of(args[args[0].equals("prepare") ? 2 : 1]).toAbsolutePath().normalize();
        switch (args[0]) {
            case "prepare": prepare(Path.of(args[1]).toAbsolutePath().normalize(), module); break;
            case "verify":
                verify(module);
                if (args.length == 3) require(sha(Path.of(args[2]), "SHA-256").equals(sha(dependencies(module), "SHA-256")), "Resolved Maven dependency differs from prepared JAR");
                break;
            case "test": test(module); break;
            case "package": assemble(module); break;
            default: throw new IllegalArgumentException("Unknown command: " + args[0]);
        }
    }
}
