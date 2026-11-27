package dev.onionall.brawler.admission

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import java.util.HexFormat

/**
 * Наблюдает локальную идентичность процессов для ownership и статуса worker.
 * Не читает журнал, не формирует статус и не посылает сигналы процессам.
 *
 * @author Стефан Колесников
 * @author GPT-6.1 Sol
 */
internal object WorkerProcessIdentity {
    /**
     * Хэш Windows host либо Linux boot/PID namespace; ошибка оставляет scope неизвестным.
     * Scope ограничивает применимость PID, но сам по себе не доказывает containment.
     */
    @JvmStatic
    fun scope(): String? {
        return try {
            val identity = if (System.getProperty("os.name").startsWith("Windows")) {
                val host = System.getenv("COMPUTERNAME")
                // Match Java String.isBlank, including its Unicode whitespace semantics.
                if (host == null || host.codePoints().allMatch { Character.isWhitespace(it) }) return null
                "windows:$host"
            } else {
                Files.readString(Path.of("/proc/sys/kernel/random/boot_id"))
                    .trim { Character.isWhitespace(it) } + ":" +
                    Files.readSymbolicLink(Path.of("/proc/self/ns/pid"))
            }
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(identity.toByteArray(StandardCharsets.UTF_8)))
        } catch (_: Exception) {
            null
        }
    }

    /** Возвращает start instant PID либо null, если идентичность недоступна. */
    @JvmStatic
    fun start(pid: Long): String? = try {
        ProcessHandle.of(pid).flatMap { it.info().startInstant() }.map(Instant::toString).orElse(null)
    } catch (_: RuntimeException) {
        null
    }

    /**
     * Сохраняет три состояния: true для живого совпадающего процесса, false для
     * отсутствующего процесса или другого start instant, null для неизвестной идентичности.
     * Принимает только Integer/Long PID из существующих JSON-наблюдений.
     */
    @JvmStatic
    fun alive(pid: Any?, expected: Any?): Boolean? {
        if ((pid !is Int && pid !is Long) || pid !is Number || pid.toLong() < 1 || expected !is String) {
            return null
        }
        return try {
            val instant = Instant.parse(expected)
            val process = ProcessHandle.of(pid.toLong())
            if (process.isEmpty || !process.get().isAlive) {
                false
            } else {
                val actual = process.get().info().startInstant()
                if (actual.isEmpty) null else actual.get() == instant && process.get().isAlive
            }
        } catch (_: RuntimeException) {
            null
        }
    }
}
