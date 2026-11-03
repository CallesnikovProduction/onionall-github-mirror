package dev.onionall.brawler.admission;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

/** Test-only readback: never admits a URL or alters authoritative scheduling. */
public final class AdmissionProbe {
    public static void main(String[] args) throws Exception {
        if (args.length != 4 || !Set.of("session", "reservation").contains(args[0])
                || !args[1].matches("[a-f0-9-]{36}") || !args[3].matches("[a-z-]+\\.json"))
            throw new IllegalArgumentException("readback action, fixture job, URL and filename required");
        String token = Files.readString(Path.of("/evidence/admission-token")).strip();
        try (var client = new AdmissionClient("frontier", 7071, token)) {
            Map<String,Object> request = args[0].equals("session")
                ? Map.of("action", "session", "session_id", args[1])
                : Map.of("action", "reservation", "session_id", args[1], "url", args[2]);
            new ObjectMapper().writeValue(Path.of("/evidence", args[3]).toFile(), client.command(request));
        }
    }
}
