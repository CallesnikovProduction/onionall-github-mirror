package dev.onionall.brawler.tools;

import dev.onionall.brawler.admission.AdmissionClient;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Path;
import java.util.Map;


/**
 * <b>Выполняет ограниченные проверки готовности инфраструктуры runtime.</b>
 *
 * <p>Предоставляет два независимых диагностических режима:
 *
 * <ul>
 *     <li>frontier — проверка аутентифицированного взаимодействия
 *     с Admission RPC посредством capability credentials;</li>
 *     <li>socks — проверка TCP-доступности SOCKS endpoint
 *     и согласования SOCKS5 No Authentication.</li>
 * </ul>
 *
 * <p>Frontier-проверка выполняет чтение состояния специальной
 * session по диагностическому идентификатору и принимает
 * SESSION_NOT_FOUND либо SESSION_OPEN.
 *
 * <p>SOCKS-проверка ограничивается установлением TCP-соединения
 * и обменом приветственными сообщениями SOCKS5. Не выполняет
 * CONNECT к целевому ресурсу и не подтверждает готовность
 * Tor bootstrap или доступность onion-сервисов.
 *
 * <p>Класс не создаёт crawl sessions, не получает leases,
 * не запускает Storm topology и не выполняет краулинг.
 *
 * @Reviewer: Stephan Kolesnikov
 * @author GPT-6.1 Sol Max
 */
public final class RuntimeProbe {

    /**
     * <b>Запрещает создание экземпляров диагностического инструмента.</b>
     *
     * <p>RuntimeProbe предоставляет только статическую точку входа
     * и не хранит изменяемое состояние экземпляра.
     *
     * @Reviewer: Stephan Kolesnikov
     * @author GPT-6.1 Sol Max
     */
    private RuntimeProbe() {
    }

    /**
     * <b>Запускает выбранную проверку готовности runtime.</b>
     *
     * <p>В режиме frontier создаёт AdmissionClient с использованием
     * capability-файла и выполняет диагностический запрос session.
     *
     * <p>В режиме socks устанавливает TCP-соединение с указанным
     * SOCKS endpoint и проверяет согласование SOCKS5 No Authentication.
     *
     * <p>При успешном выполнении выводит READY с названием режима.
     * При неверных аргументах, недоступности сервиса или нарушении
     * ожидаемого протокольного контракта завершает выполнение ошибкой.
     *
     * @param args аргументы запуска:
     *             frontier HOST PORT TOKEN_FILE либо socks HOST PORT
     * @throws Exception если проверка не подтвердила готовность
     *                   соответствующего компонента
     * @Reviewer: Stephan Kolesnikov
     * @author GPT-6.1 Sol Max
     */
    public static void main(String[] args) throws Exception {
        if (args.length == 4 && "frontier".equals(args[0])) {
            try (var client =
                         AdmissionClient.fromCapabilityFile(
                                 args[1],
                                 Integer.parseInt(args[2]),
                                 Path.of(args[3])
                         )) {

                var response = client.command(
                        Map.of(
                                "action", "session",
                                "session_id",
                                "00000000-0000-0000-0000-000000000000"
                        )
                );

                if (!java.util.Set.of(
                        "SESSION_NOT_FOUND",
                        "SESSION_OPEN"
                ).contains(response.get("outcome"))) {

                    throw new IOException(
                            "Unexpected authenticated Frontier readback"
                    );
                }
            }
        } else if (args.length == 3 && "socks".equals(args[0])) {
            try (var socket = new Socket()) {
                socket.connect(
                        new InetSocketAddress(
                                args[1],
                                Integer.parseInt(args[2])
                        ),
                        3000
                );

                socket.setSoTimeout(3000);

                socket.getOutputStream().write(
                        new byte[] { 5, 1, 0 }
                );

                if (
                        socket.getInputStream().read() != 5 ||
                        socket.getInputStream().read() != 0
                ) {
                    throw new IOException(
                            "SOCKS5 NO_AUTH negotiation failed"
                    );
                }
            }
        } else {
            throw new IllegalArgumentException("Use frontier HOST PORT TOKEN_FILE or socks HOST PORT");
        }
        IO.println("READY " + args[0]);
    }
}
