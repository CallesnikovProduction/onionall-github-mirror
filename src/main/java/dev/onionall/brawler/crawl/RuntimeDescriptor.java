package dev.onionall.brawler.crawl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Map;

/**
 * <b>Загрузка и валидация конфигурации среды выполнения crawler.</b>
 *
 * <p>Предоставляет методы для чтения JSON-дескриптора
 * и извлечения строковых и числовых параметров с проверкой
 * их типов, допустимых значений и ограничений.
 *
 * @author Stephan Kolesnikov
 * @author GPT-6.1 Sol
 */
public final class RuntimeDescriptor {

    private static final ObjectMapper DESCRIPTOR_JSON_MAPPER = new ObjectMapper();

    /**
     * <b>Загрузка конфигурации среды выполнения из JSON-файла.</b>
     *
     * <p>Проверяет, что указанный путь ведёт к обычному файлу
     * размером не более 32 КиБ, без перехода по символическим ссылкам
     * при проверке. Затем считывает JSON и десериализует его
     * в карту параметров {@code Map<String, Object>}.
     *
     * @param file путь к JSON-файлу конфигурации
     * @return карта параметров среды выполнения
     * @throws IOException при ошибке доступа, превышении размера
     *                     или некорректном формате JSON
     */
    public static Map<String, Object> descriptor(Path file) throws IOException {
        if (// Не обычный файл
        // Ограничение на размер
        !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > 32768) {
            throw new IOException("Require regular runtime JSON descriptor <= 32KiB");
        }
        return // ВАЖНО: открывает файл обычным способом.
        DESCRIPTOR_JSON_MAPPER.// ВАЖНО: открывает файл обычным способом.
        readValue(// LinkOption.NOFOLLOW_LINKS учитывается только для if, здесь уже его нет!
        Files.readAllBytes(file), new TypeReference<>() {
        });
    }

    /**
     * <b>Извлечение и проверка строкового параметра конфигурации.</b>
     *
     * <p>Получает значение по указанному ключу и проверяет,
     * что оно является непустой строкой длиной не более 4096.
     *
     * @param value карта параметров конфигурации
     * @param key ключ запрашиваемого параметра
     * @return проверенное строковое значение
     * @throws IOException если параметр отсутствует или некорректен
     */
    public static String text(Map<String, Object> value, String key) throws IOException {
        // Ищется в карте значение + тип Object, потому что JSON многотипизированный :)
        Object field = value.get(key);
        if (!(field instanceof String s) || s.isBlank() || s.length() > 4096) {
            throw new IOException("Invalid or missing runtime field: " + key);
        }
        return s;
    }

    /**
     * <b>Извлечение и проверка целочисленного параметра конфигурации.</b>
     *
     * <p>Получает числовое значение по ключу либо использует
     * значение по умолчанию. Проверяет целочисленность
     * и принадлежность диапазону от 1 до указанного максимума.
     *
     * @param value карта параметров конфигурации
     * @param key ключ запрашиваемого параметра
     * @param fallback значение при отсутствии параметра
     * @param max максимально допустимое значение
     * @return проверенное целочисленное значение
     * @throws IOException если значение некорректно или вне диапазона
     */
    public static int number(Map<String, Object> value, String key, int fallback, int max) throws IOException {
        // Ищется в карте значение + тип Object, потому что JSON многотипизированный :)
        Object n = value.get(key);
        int result = n == null ? fallback : n instanceof Number ? ((Number) n).intValue() : -1;
        if (result < 1 || result > max || n instanceof Number number && number.doubleValue() != result) {
            throw new IOException("Invalid runtime " + key);
        }
        return result;
    }
}
