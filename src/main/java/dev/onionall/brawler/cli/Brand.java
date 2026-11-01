package dev.onionall.brawler.cli;

import java.io.PrintWriter;

final class Brand {
    private Brand() { }
    static String safe(String text) { return text == null ? "" : text.replaceAll("[\\p{Cc}\\p{Cf}]", " "); }
    static void welcome(PrintWriter out, boolean color, int width) {
        if (color) out.print("\033[38;2;167;139;250m");
        if (width >= 65) out.println("""
                      /\\  /\\
                   .-'  \\/  '-.
                 .'            '.
                /    .------.    \\
               |    | >    < |    |
               |    |  \\__/  |    |
                \\    '------'    /
                  '-.________.-'
                """);
        else out.println("  /\\   ( >_< )   \\__/  onionall");
        out.println("ONIONALL BRAWLER  /  TOR-NATIVE FETCH ENGINE");
        if (color) out.print("\033[0m");
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
