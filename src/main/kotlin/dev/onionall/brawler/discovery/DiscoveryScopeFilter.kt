package dev.onionall.brawler.discovery

import com.fasterxml.jackson.databind.JsonNode
import dev.onionall.brawler.tor.TorPolicy
import org.apache.stormcrawler.Metadata
import org.apache.stormcrawler.filtering.URLFilter
import java.io.IOException
import java.net.URL
import java.time.Instant

/**
 * URL-фильтр Discovery для pipeline Apache StormCrawler.
 *
 * Проверяет контекстные metadata и нормализует целевой URL
 * с использованием централизованной политики [TorPolicy].
 *
 * Основные обязанности:
 * - проверять переданные metadata через TorPolicy;
 * - нормализовать целевые адреса;
 * - отклонять ссылки при IOException;
 * - учитывать количество отклонений в metadata;
 * - фиксировать время последнего отклонения.
 *
 * Фильтр не выполняет HTTP-запросов и не управляет
 * очередью URLFrontier, глубиной обхода или бюджетами.
 *
 * Согласно архитектуре Discovery, StatusEmitterBolt передаёт
 * metadata исходной страницы до переноса metadata outlink.
 *
 * ВАЖНО:
 * Класс не осуществляет durable admission.
 * Его нельзя рассматривать как замену Frontier-backed
 * механизму резервирования URL.
 *
 * @see TorPolicy
 * @see DiscoveryPlanner
 * @see DiscoveryParseFilter
 * @author Stephan Kolesnikov
 * @author GPT-6 Astra
 */
class DiscoveryScopeFilter : URLFilter() {

    /**
     * Централизованная политика проверки и нормализации URL.
     *
     * Инициализируется во время configure().
     *
     * Конкретные ограничения по host, scheme и metadata
     * определяются реализацией TorPolicy.
     */
    private lateinit var policy: TorPolicy

    /**
     * Настраивает URL-фильтр из конфигурации StormCrawler.
     *
     * Сначала выполняет конфигурацию базового URLFilter,
     * затем создаёт TorPolicy для последующих проверок.
     *
     * @param conf конфигурация StormCrawler.
     * @param params параметры данного фильтра.
     * @param name имя фильтра в pipeline.
     */
    override fun configure(
        conf: MutableMap<String, Any>,
        params: JsonNode,
        name: String
    ) {
        // Сохраняем стандартную конфигурацию родительского фильтра.
        super.configure(conf, params, name)

        // Используем единую политику вместо самостоятельной
        // реализации проверок URL внутри Discovery.
        policy = TorPolicy(conf)
    }

    /**
     * Проверяет целевой URL и возвращает нормализованный адрес.
     *
     * Последовательность:
     * 1. Если metadata присутствуют, проверить их через TorPolicy.
     * 2. Нормализовать target.
     * 3. Вернуть нормализованный URL при успехе.
     * 4. При IOException зафиксировать отклонение и вернуть null.
     *
     * Важно: отсутствие metadata приводит к пропуску
     * checkMetadata(), но не проверки normalize().
     *
     * @param source исходный URL, предоставленный StormCrawler;
     *   текущая реализация его не использует.
     * @param metadata контекстные metadata, переданные pipeline;
     *   могут быть null.
     * @param target целевой URL для проверки и нормализации.
     *
     * @return нормализованный URL при успехе;
     *   null при перехваченном IOException.
     *
     * @see TorPolicy
     */
    override fun filter(
        source: URL?,
        metadata: Metadata?,
        target: String
    ): String? = try {

        // Проверяем контекст Discovery, если metadata переданы.
        //
        // Точные требования к обязательным полям находятся
        // внутри TorPolicy.checkMetadata().
        metadata?.let {
            policy.checkMetadata(it)
        }

        // Возвращаем нормализованный URL.
        //
        // Важно: здесь не выполняется разрешение относительной
        // ссылки относительно source. Это должен обеспечивать
        // предыдущий этап либо сама реализация TorPolicy.
        policy.normalize(target)

    } catch (_: IOException) {

        // Ошибка проверки или нормализации означает,
        // что ссылка отклоняется данным фильтром.
        metadata?.let {

            // Читаем ранее накопленное количество отклонений.
            // При отсутствии или некорректном числе начинаем с 0.
            val count = it
                .getFirstValue("discovery.scope.rejected")
                ?.toIntOrNull() ?: 0

            // Ограничиваем счётчик диапазоном 0..1000.
            // Это защищает metadata от бесконечного роста числа.
            it.setValue(
                "discovery.scope.rejected",
                (count.coerceIn(0, 1000) + 1)
                    .coerceAtMost(1000)
                    .toString()
            )

            // Фиксируем время последнего отклонения.
            // Это не время обнаружения или загрузки страницы.
            it.setValue(
                "discovery.scope.rejected.at",
                Instant.now().toString()
            )
        }

        // В контракте URLFilter null означает,
        // что ссылка не проходит данный фильтр.
        null
    }
}
