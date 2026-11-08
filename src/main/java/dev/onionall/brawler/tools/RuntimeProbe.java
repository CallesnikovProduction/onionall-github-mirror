package dev.onionall.brawler.tools;

import dev.onionall.brawler.admission.AdmissionClient;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Path;
import java.util.Map;

/**
 * <b>Ограниченные проверки готовности локального runtime.</b>
 * <p>Read-only Admission RPC не создаёт сессию и не получает lease.
 * SOCKS negotiation проверяет протокол, но не заменяет Tor bootstrap healthcheck.
 */
public final class RuntimeProbe {
    private RuntimeProbe() {
    }

    /**
     * <b>Проверяет authenticated Frontier либо SOCKS5 без запроса к onion.</b>
     * @param args режим, host, port и для Frontier путь к capability
     * @throws Exception если readiness не подтверждена
     */
    public static void main(String[] args) throws Exception {
        if (args.length == 4 && "frontier".equals(args[0])) {
            try (var client = AdmissionClient.fromCapabilityFile(args[1], Integer.parseInt(args[2]), Path.of(args[3]))) {
                var response = client.command(Map.of("action", "session", "session_id",
                    "00000000-0000-0000-0000-000000000000"));
                if (!java.util.Set.of("SESSION_NOT_FOUND", "SESSION_OPEN").contains(response.get("outcome"))) {
                    throw new IOException("Unexpected authenticated Frontier readback");
                }
            }
        } else if (args.length == 3 && "socks".equals(args[0])) {
            try (var socket = new Socket()) {
                socket.connect(new InetSocketAddress(args[1], Integer.parseInt(args[2])), 3000);
                socket.setSoTimeout(3000);
                socket.getOutputStream().write(new byte[] {5, 1, 0});
                if (socket.getInputStream().read() != 5 || socket.getInputStream().read() != 0) {
                    throw new IOException("SOCKS5 NO_AUTH negotiation failed");
                }
            }
        } else {
            throw new IllegalArgumentException("Use frontier HOST PORT TOKEN_FILE or socks HOST PORT");
        }
        System.out.println("READY " + args[0]);
    }
}
