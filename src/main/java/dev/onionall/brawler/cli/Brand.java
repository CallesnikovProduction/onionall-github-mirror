package dev.onionall.brawler.cli;

import java.io.PrintWriter;

final class Brand {
    private Brand() { }
    static String safe(String text) { return text == null ? "" : text.replaceAll("[\\p{Cc}\\p{Cf}]", " "); }
    // Three explored silhouettes: teardrop (too egg-like), squat layered bulb
    // (too pumpkin-like), and tapered bulb with three shoots (chosen below).
    // ASCII is intentional: every geometry character occupies one terminal column.
    private static final java.util.List<String> FULL = java.util.List.of(
            "           /\\  /\\",
            "          / /\\/ /\\",
            "          \\ \\| / /",
            "           \\ | /",
            "        .-' \\|/ '-.",
            "      .'  .-----.  '.",
            "     /  .'       '.  \\",
            "    /  / \\___ ___/ \\  \\",
            "   |  |   \\O/ \\O/   |  |",
            "   |  |    ._____.    |  |",
            "    \\  \\    \\___/    /  /",
            "     '. '._       _.' .'",
            "       '-._'-----'_.-'",
            "           '---'");
    private static final java.util.List<String> COMPACT = java.util.List.of(
            "         /\\ | /\\",
            "         \\ \\|/ /",
            "       .-' \\|/ '-.",
            "     .' .------. '.",
            "    / .'        '. \\",
            "   | | \\___ ___/ | |",
            "   | |  \\O/ \\O/  | |",
            "    \\ \\  \\___/  / /",
            "     '._'------'_." ,
            "        '------'");
    private static final java.util.List<String> TINY = java.util.List.of(
            "       /\\|/\\",
            "     .'     '.",
            "    / >O   O< \\",
            "    \\  \\___/  /",
            "      '.___.'");
    private static final String[] RGB = {"124;58;237", "139;92;246", "167;139;250", "196;181;253", "233;213;255"};
    private static final int[] COLORS_256 = {99, 141, 147, 183, 189};

    static java.util.List<String> header(int width, int height, java.util.Map<String,String> env, boolean color) {
        int columns = Math.max(1, width - 1), budget = Math.max(1, height - 6);
        java.util.List<String> lines = new java.util.ArrayList<>();
        if (width >= 100 && budget >= FULL.size() + 3) {
            lines.addAll(beside(FULL, wordmark("ONIONALL BRAWLER"), 3));
        } else if (width >= 70 && budget >= COMPACT.size() + 3) {
            java.util.List<String> words = new java.util.ArrayList<>(wordmark("ONIONALL"));
            words.add(""); words.addAll(wordmark("BRAWLER"));
            lines.addAll(beside(COMPACT, words, 3));
        } else if (budget >= TINY.size() + 3) {
            lines.addAll(TINY);
        }
        // Accessible caption also makes the product name searchable in terminal logs.
        lines.add("ONIONALL BRAWLER");
        lines.add("Tor-native fetch engine");
        lines.add("-".repeat(Math.min(columns, width >= 100 ? 97 : 64)));
        if (lines.size() > budget) {
            lines = new java.util.ArrayList<>(java.util.List.of("ONIONALL BRAWLER", "-".repeat(columns)));
            if (budget == 1) lines = new java.util.ArrayList<>(java.util.List.of("ONIONALL BRAWLER"));
        }
        boolean enabled = color && !env.containsKey("NO_COLOR") && !"dumb".equals(env.get("TERM"));
        java.util.List<String> rendered = new java.util.ArrayList<>();
        for (String line : lines) {
            String plain = line.substring(0, Math.min(columns, line.length()));
            rendered.add(!enabled ? plain : line.equals("ONIONALL BRAWLER") ? tone(2, env) + plain + "\033[0m" : line.equals("Tor-native fetch engine")
                    ? tone(3, env) + plain + "\033[0m" : gradient(plain, env));
        }
        return java.util.List.copyOf(rendered);
    }

    private static java.util.List<String> beside(java.util.List<String> art, java.util.List<String> words, int gap) {
        int artWidth = art.stream().mapToInt(String::length).max().orElse(0);
        java.util.List<String> rows = new java.util.ArrayList<>();
        int offset = Math.max(0, (art.size() - words.size()) / 2);
        for (int row = 0; row < Math.max(art.size(), words.size()); row++) {
            String left = row < art.size() ? art.get(row) : "";
            int right = row - offset;
            rows.add(left + " ".repeat(artWidth - left.length() + gap)
                    + (right >= 0 && right < words.size() ? words.get(right) : ""));
        }
        return rows;
    }

    private static java.util.List<String> wordmark(String text) {
        StringBuilder[] rows = {new StringBuilder(), new StringBuilder(), new StringBuilder()};
        for (char letter : text.toCharArray()) {
            String[] glyph = switch (letter) {
                case 'O' -> new String[]{" _ ", "| |", "|_|"};
                case 'N' -> new String[]{"|\\ |", "| \\|", "|  |"};
                case 'I' -> new String[]{"___", " | ", "_|_"};
                case 'A' -> new String[]{" _ ", "|_|", "| |"};
                case 'L' -> new String[]{"|  ", "|  ", "|__"};
                case 'B' -> new String[]{"|_ ", "|_)", "|_)"};
                case 'R' -> new String[]{"|_ ", "|_)", "| \\"};
                case 'W' -> new String[]{"|   |", "| | |", "|_|_|"};
                case 'E' -> new String[]{" __", "|_ ", "|__"};
                default -> new String[]{"  ", "  ", "  "};
            };
            for (int i = 0; i < rows.length; i++) rows[i].append(glyph[i]).append(' ');
        }
        return java.util.List.of(rows[0].toString().stripTrailing(), rows[1].toString().stripTrailing(), rows[2].toString().stripTrailing());
    }

    private static String tone(int shade, java.util.Map<String,String> env) {
        String capability = env.getOrDefault("COLORTERM", "");
        if (capability.equalsIgnoreCase("truecolor") || capability.equalsIgnoreCase("24bit"))
            return "\033[38;2;" + RGB[shade] + "m";
        if (env.getOrDefault("TERM", "").contains("256color")) return "\033[38;5;" + COLORS_256[shade] + "m";
        return shade < 3 ? "\033[35m" : "\033[95m";
    }

    private static String gradient(String line, java.util.Map<String,String> env) {
        StringBuilder result = new StringBuilder(); int previous = -1;
        for (int i = 0; i < line.length(); i++) {
            int shade = Math.min(4, i * 5 / Math.max(1, line.length()));
            if (line.charAt(i) == 'O') shade = 4;
            if (shade != previous) {
                result.append(tone(shade, env));
                previous = shade;
            }
            result.append(line.charAt(i));
        }
        return result.append("\033[0m").toString();
    }

    static void welcome(PrintWriter out, boolean color, int width) {
        for (String line : header(width, 40, java.util.Map.of("TERM", "xterm-256color", "COLORTERM", "truecolor"), color)) out.println(line);
        out.flush();
    }

    static final class Activity implements AutoCloseable {
        private final Thread thread;
        private final PrintWriter err;
        Activity(PrintWriter err, boolean animate) {
            this.err = err;
            if (!animate) { thread = null; return; }
            long start = System.nanoTime();
            thread = Thread.ofPlatform().daemon().start(() -> {
                String[] frames = {"|", "/", "-", "\\"}; int i = 0;
                try {
                    while (!Thread.currentThread().isInterrupted()) {
                        err.printf("\r%s Waiting for TorProtocol response... %.1fs", frames[i++ % 4], (System.nanoTime()-start)/1e9);
                        err.flush(); Thread.sleep(120);
                    }
                } catch (InterruptedException stopped) { Thread.currentThread().interrupt(); }
            });
        }
        public void close() {
            if (thread != null) {
                thread.interrupt();
                try { thread.join(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                err.print("\r\033[2K"); err.flush();
            }
        }
    }
}
