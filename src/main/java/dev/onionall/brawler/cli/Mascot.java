package dev.onionall.brawler.cli;

import java.awt.*;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.util.*;
import java.util.List;

/**
 * <b>Растровый рендеринг векторного талисмана вне экрана.</b>
 *
 * <p>Java2D создаёт цветные поверхности; ANSI half-block и ASCII используют один растр. DISPLAY/X11 не требуется.
 */
final class Mascot {

    // Rendering is off-screen only; never depend on the user's DISPLAY/X11 session.
    static {
        System.setProperty("java.awt.headless", "true");
    }

    private Mascot() {
    }

    private static Path2D shape(double... points) {
        Path2D p = new Path2D.Double();
        p.moveTo(points[0], points[1]);
        for (int i = 2; i < points.length; i += 6) {
            p.curveTo(points[i], points[i + 1], points[i + 2], points[i + 3], points[i + 4], points[i + 5]);
        }
        p.closePath();
        return p;
    }

    /**
     * <b>Рисует талисман в памяти через Java2D.</b>
     *
     * <p>Graphics2D освобождается в finally; внешние изображения и шрифты не читаются.
     *
     * @param width ширина изображения или строки
     * @param height высота растра
     * @return ARGB-растр
     */
    static BufferedImage raster(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.scale(width / 100.0, height / 125.0);
            // Three swept shoots converge into the neck, rather than straight sticks.
            fill(g, shape(51, 51, 39, 29, 49, 10, 67, 2, 55, 17, 54, 32, 55, 45, 54, 48, 52, 50, 51, 51),
                "#e9d5ff", "#7c3aed");
            fill(g, shape(54, 48, 58, 24, 75, 10, 92, 12, 71, 21, 63, 34, 59, 52, 57, 52, 55, 51, 54, 48),
                "#c084fc", "#6d16ce");
            // Onion bulb, tapered at the base with curved, shaded peeling layers.
            fill(g, shape(39, 12, 57, 31, 55, 43, 71, 55, 107, 78, 104, 111, 52, 124, 42, 115, 12, 120, 4, 95, -5,
                70, 17, 57, 35, 42, 48, 30, 43, 22, 39, 12), "#b75aff", "#3b0878");
            fill(g, shape(39, 12, 53, 41, 36, 53, 24, 73, 6, 104, 33, 113, 52, 124, 26, 115, 7, 108, 11, 86, 14,
                61, 49, 51, 39, 12), "#e1a3ff", "#6814b1");
            fill(g, shape(44, 28, 56, 47, 62, 51, 76, 63, 102, 96, 79, 113, 52, 124, 66, 104, 71, 93, 63, 78, 57,
                60, 45, 47, 44, 28), "#d8a2ff", "#7a25d2");
            fill(g, shape(46, 33, 51, 53, 35, 66, 30, 85, 22, 105, 42, 115, 52, 124, 47, 106, 36, 99, 38, 85, 38,
                64, 55, 57, 46, 33), "#f2cbff", "#8735cb");
            fill(g, shape(49, 44, 54, 62, 67, 66, 69, 84, 75, 103, 62, 112, 52, 124, 48, 109, 31, 102, 36, 83, 41,
                69, 48, 59, 49, 44), "#fff0ff", "#d9a1ef");
            // Curved wedge eyes and asymmetric smile: reference-inspired confident face.
            g.setColor(new Color(30, 4, 53));
            g.fill(shape(35, 78, 39, 83, 43, 86, 47, 89, 40, 90, 32, 86, 35, 78));
            g.fill(shape(70, 78, 67, 83, 61, 87, 57, 89, 65, 89, 73, 86, 70, 78));
            g.fill(shape(44, 94, 50, 97, 57, 97, 64, 92, 59, 102, 49, 102, 44, 94));
        } finally {
            g.dispose();
        }
        return image;
    }

    private static void fill(Graphics2D g, Path2D path, String light, String dark) {
        g.setPaint(new GradientPaint(24, 35, Color.decode(light), 75, 119, Color.decode(dark)));
        g.fill(path);
    }

    /**
     * <b>Кодирует растр в строки ANSI или ASCII.</b>
     *
     * <p>Выбор block-режима зависит от color и locale.
     *
     * @param width ширина изображения или строки
     * @param rows число строк терминала
     * @param env окружение процесса
     * @param color разрешены ли цвета
     * @return строки изображения
     */
    static List<String> render(int width, int rows, Map<String, String> env, boolean color) {
        BufferedImage image = raster(width, rows * 2);
        boolean blocks = color && Brand.blocks(env);
        List<String> lines = new ArrayList<>();
        for (int y = 0; y < rows * 2; y += 2) {
            StringBuilder line = new StringBuilder();
            for (int x = 0; x < width; x++) {
                int top = image.getRGB(x, y), bottom = image.getRGB(x, y + 1);
                boolean a = (top >>> 24) >= 128, b = (bottom >>> 24) >= 128;
                if (!blocks) {
                    if (!a && !b) {
                        line.append(' ');
                    } else {
                        int rgb = a ? top : bottom;
                        int value = (((rgb >> 16) & 255) + ((rgb >> 8) & 255) + (rgb & 255)) / 3;
                        line.append(" .:-=+*#%@".charAt(Math.min(9, value * 10 / 256)));
                    }
                } else if (a || b) {
                    line.append("\033[0m");
                    if (a) {
                        line.append(ink(top, false, env));
                    }
                    if (b) {
                        line.append(ink(bottom, a, env));
                    }
                    line.append(a ? '\u2580' : '\u2584');
                } else {
                    line.append("\033[0m ");
                }
            }
            if (blocks) {
                line.append("\033[0m");
            }
            lines.add(line.toString());
        }
        return lines;
    }

    private static String ink(int rgb, boolean background, Map<String, String> env) {
        int r = (rgb >> 16) & 255, g = (rgb >> 8) & 255, b = rgb & 255;
        String mode = env.getOrDefault("COLORTERM", "");
        String prefix = background ? "48" : "38";
        if (mode.equalsIgnoreCase("truecolor") || mode.equalsIgnoreCase("24bit")) {
            return "\033[" + prefix + ";2;" + r + ";" + g + ";" + b + "m";
        }
        if (env.getOrDefault("TERM", "").contains("256color")) {
            return "\033[" + prefix + ";5;" + (16 + 36 * Math.round(r / 51f) + 6 * Math.round(g / 51f) +
                Math.round(b / 51f)) + "m";
        }
        return "\033[" + (background ? 45 : (r + g + b > 520 ? 95 : 35)) + "m";
    }
}
