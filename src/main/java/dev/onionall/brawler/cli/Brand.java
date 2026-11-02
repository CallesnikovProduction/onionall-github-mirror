package dev.onionall.brawler.cli;

import java.io.PrintWriter;

final class Brand {
    private Brand() { }
    static String safe(String text) { return text == null ? "" : text.replaceAll("[\\p{Cc}\\p{Cf}]", " "); }
    private static final String[] RGB = {"124;58;237", "139;92;246", "167;139;250", "196;181;253", "233;213;255"};
    private static final int[] COLORS_256 = {99, 141, 147, 183, 189};

    static java.util.List<String> header(int width, int height, java.util.Map<String,String> env, boolean color) {
        int columns = Math.max(1, width - 1), budget = Math.max(1, height - (width >= 100 ? 7 : 6));
        boolean enabled = color && !env.containsKey("NO_COLOR") && !"dumb".equals(env.get("TERM"));
        java.util.List<String> lines = new java.util.ArrayList<>();
        if(budget>=10) {
            int rows=Math.min(20,budget-5);
            int mascotWidth=Math.min(width<70?columns:width>=100?34:32,Math.max(10,(int)Math.round(rows*1.65)));
            if(width>=70)mascotWidth=Math.min(mascotWidth,columns-artWidth(wordsFor(0))-3);
            rows=Math.min(rows,Math.max(5,(int)Math.round(mascotWidth/1.65)));
            var art=Mascot.render(mascotWidth,rows,env,enabled);
            if(width>=70) {
                var words=wordsFor(columns-mascotWidth-3);
                if(Math.max(rows,words.size())+5<=budget) lines.addAll(beside(art,words,3,env,enabled));
                else lines.addAll(art);
            } else lines.addAll(art);
        }
        if (!lines.isEmpty()) lines.addAll(java.util.List.of("", "", ""));
        lines.add("Tor-native fetch engine");
        lines.add("=".repeat(Math.min(columns, width >= 100 ? 97 : 64)));
        if (lines.size() > budget) {
            lines = new java.util.ArrayList<>(java.util.List.of("Tor-native fetch engine", "=".repeat(columns)));
            if (budget == 1) lines = new java.util.ArrayList<>(java.util.List.of("Tor-native fetch engine"));
        }
        java.util.List<String> rendered = new java.util.ArrayList<>();
        for (String line : lines) {
            if(line.contains("\033[")) { rendered.add(line); continue; }
            String plain = line.substring(0, Math.min(columns, line.length()));
            rendered.add(!enabled ? plain : line.equals("Tor-native fetch engine")
                    ? tone(3, env) + plain + "\033[0m" : gradient(plain, env));
        }
        return java.util.List.copyOf(rendered);
    }

    private static java.util.List<String> beside(java.util.List<String> art, java.util.List<String> words, int gap, java.util.Map<String,String> env, boolean color) {
        int artWidth = artWidth(art);
        java.util.List<String> rows = new java.util.ArrayList<>();
        int offset = Math.max(0, (art.size() - words.size()) / 2);
        for (int row = 0; row < Math.max(art.size(), words.size()); row++) {
            String left = row < art.size() ? art.get(row) : "";
            int right = row - offset;
            rows.add(left + " ".repeat(artWidth - visibleWidth(left) + gap)
                    + (right >= 0 && right < words.size() ? (color ? gradient(words.get(right),env) : words.get(right)) : ""));
        }
        return rows;
    }

    private static int visibleWidth(String line) {return line.replaceAll("\u001b\\[[0-9;]*m", "").length();}
    private static int artWidth(java.util.List<String> art) {
        return art.stream().mapToInt(Brand::visibleWidth).max().orElse(0);
    }
    private static java.util.List<String> wordsFor(int columns) {
        var single=wordmark("ONIONALL BRAWLER");
        if(artWidth(single)<=columns)return single;
        var stacked=new java.util.ArrayList<>(wordmark("ONIONALL"));
        stacked.add("");stacked.addAll(wordmark("BRAWLER"));return stacked;
    }
    private static java.util.List<String> wordmark(String text) {
        StringBuilder[] front=new StringBuilder[5];
        for(int row=0;row<5;row++)front[row]=new StringBuilder(" ".repeat((4-row)/2));
        for(char letter:text.toCharArray()) {
            String[] glyph=switch(letter) {
                case 'O' -> new String[]{" ### ","#   #","#   #","#   #"," ### "};
                case 'N' -> new String[]{"#   #","##  #","# # #","#  ##","#   #"};
                case 'I' -> new String[]{"###"," # "," # "," # ","###"};
                case 'A' -> new String[]{" ## ","#  #","####","#  #","#  #"};
                case 'L' -> new String[]{"#   ","#   ","#   ","#   ","####"};
                case 'B' -> new String[]{"### ","#  #","### ","#  #","### "};
                case 'R' -> new String[]{"### ","#  #","### ","# # ","#  #"};
                case 'W' -> new String[]{"#   #","#   #","# # #","# # #"," # # "};
                case 'E' -> new String[]{"####","#   ","### ","#   ","####"};
                default -> new String[]{"  ","  ","  ","  ","  "};
            };
            for(int row=0;row<5;row++)front[row].append(glyph[row]).append(' ');
        }
        int width=java.util.Arrays.stream(front).mapToInt(StringBuilder::length).max().orElse(0)+1;
        char[][] pixels=new char[6][width];for(char[] row:pixels)java.util.Arrays.fill(row,' ');
        // Offset extrusion first; opaque letter faces cover their own shadow.
        for(int row=0;row<5;row++)for(int col=0;col<front[row].length();col++)
            if(front[row].charAt(col)=='#')pixels[row+1][col+1]='+';
        for(int row=0;row<5;row++)for(int col=0;col<front[row].length();col++)
            if(front[row].charAt(col)=='#')pixels[row][col]='#';
        return java.util.Arrays.stream(pixels).map(row->new String(row).stripTrailing()).toList();
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
            char pixel=line.charAt(i);
            if(pixel=='#')shade=2+Math.min(2,i*3/Math.max(1,line.length()));
            if(pixel=='.' || pixel=='O')shade=4;
            if(pixel==':' || pixel=='*')shade=3;
            if(pixel=='@' || pixel=='+')shade=0;
            if (shade != previous) {
                result.append(tone(shade, env));
                previous = shade;
            }
            result.append(displayPixel(pixel, env));
        }
        return result.append("\033[0m").toString();
    }

    private static char displayPixel(char pixel, java.util.Map<String,String> env) {
        if(!blocks(env))return pixel;
        return switch(pixel) {
            case '#' -> '\u2588';
            case '@' -> '\u2593';
            case ':' -> '\u2592';
            case '+', '.' -> '\u2591';
            default -> pixel;
        };
    }

    static boolean blocks(java.util.Map<String,String> env) {
        String locale=env.getOrDefault("LC_ALL",env.getOrDefault("LC_CTYPE",env.getOrDefault("LANG",""))).toLowerCase(java.util.Locale.ROOT);
        boolean blocks=(locale.contains("utf-8") || locale.contains("utf8"))
                && !locale.matches("^(zh|ja|ko).*")
                && !env.containsKey("ONIONALL_ASCII");
        return blocks;
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
