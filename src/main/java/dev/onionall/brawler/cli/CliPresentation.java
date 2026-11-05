package dev.onionall.brawler.cli;

import java.util.List;
import java.util.Map;

/**
 * <b>Оформление известных строк интерфейса.</b>
 *
 * <p>Цвет применяется к доверенным типам строк после очистки и clipping внешнего текста.
 */
final class CliPresentation {

    private CliPresentation() {
    }

    /**
     * <b>Формирует строки локального TCP-статуса.</b>
     *
     * <p>Не трактует TCP listener как Tor/onion readiness.
     *
     * @param result карта результата
     * @param compact нужна ли краткая форма
     * @return строки отображения
     */
    static List<String> status(Map<String, Object> result, boolean compact) {
        String state = Boolean.TRUE.equals(result.get("socks_tcp_reachable")) ? "TCP reachable" : "TCP unavailable";
        if (compact) {
            return List.of("TOR CONNECTION STATUS", "Tor SOCKS  : " + state + " | " + result.get("socks_endpoint"),
                "Tor / onion: NOT_TESTED (TCP only; SOCKS5 configured)", "Downloads  : " +
                Brand.safe(String.valueOf(result.get("downloads"))), "Java       : " + result.get("java") +
                " | M2/M3 BLOCKED");
        }
        return List.of("TOR CONNECTION STATUS", "======================================", "", "Tor SOCKS     : " +
            state, "Endpoint      : " + result.get("socks_endpoint"), "Transport     : SOCKS5 configured",
            "Tor / onion   : NOT_TESTED (TCP reachability only)", "Downloads     : " +
            Brand.safe(String.valueOf(result.get("downloads"))), "Java          : " + result.get("java"),
            "Timeout       : " + result.get("timeout_ms") + " ms", "Response limit: " + result.get("max_bytes") +
            " bytes", "M2/M3         : BLOCKED");
    }

    /**
     * <b>Окрашивает известные строки представления.</b>
     *
     * <p>При отключённом оформлении возвращает исходную строку.
     *
     * @param text текст для отображения
     * @param enabled разрешено ли оформление
     * @return строка с разрешённым оформлением
     */
    static String style(String text, boolean enabled) {
        if (!enabled) {
            return text;
        }
        if (text.matches("\\([0-4]\\)  .*")) {
            String tone = text.startsWith("(1)") ? "32" : text.startsWith("(0)") ? "91" : text.startsWith("(4)") ?
                "35" : "37";
            return "\033[95m" + text.substring(0, 3) + "\033[0m  \033[" + tone + "m" + text.substring(5) + "\033[0m";
        }
        if (text.startsWith("Choose ")) {
            return "\033[95m" + text + "\033[0m";
        }
        if (text.equals("TOR CONNECTION STATUS")) {
            return "\033[1;95m" + text + "\033[0m";
        }
        int colon = text.indexOf(':');
        if (colon >= 0 &&
            text.matches("(?:Tor SOCKS|Endpoint|Transport|Tor / onion|Downloads|Java|Timeout|Response limit|M2/M3) *:.*")) {
            String value = text.substring(colon + 1);
            String tone = value.contains("TCP unavailable") ? "91" : value.contains("TCP reachable") ? "32" :
                value.contains("NOT_TESTED") || value.contains("BLOCKED") ? "33" : "37";
            return "\033[1;95m" + text.substring(0, colon + 1) + "\033[0m\033[" + tone + "m" + value + "\033[0m";
        }
        return text;
    }
}
