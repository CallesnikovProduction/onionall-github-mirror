package dev.onionall.brawler.cli

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.concurrent.Callable
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.ParentCommand
import picocli.CommandLine.ScopeType.INHERIT

/**
 * Настройка SOCKS в существующем runtime descriptor и bounded проверка протокола.
 * Не меняет host proxy/DNS, не запрашивает onion и не доказывает Tor bootstrap.
 *
 * @author Стефан Колесников
 * @author GPT-6 Astra
 */
class RuntimeCommands(private val printer: Printer) {
    fun interface Printer { @Throws(IOException::class) fun print(value: Map<String, Any?>) }
    fun config() = Config(printer)
    fun system() = SystemCommands(printer)

    @Command(name = "config", mixinStandardHelpOptions = true, subcommands = [Proxy::class])
    class Config(val printer: Printer) {
        @Option(names = ["--runtime"], required = true, scope = INHERIT) lateinit var runtime: Path
    }

    @Command(name = "proxy", mixinStandardHelpOptions = true, subcommands = [Show::class, Set::class])
    class Proxy { @ParentCommand lateinit var parent: Config }

    @Command(name = "show", mixinStandardHelpOptions = true, description = ["Show the configured worker SOCKS endpoint only."])
    class Show : Callable<Int> {
        @ParentCommand lateinit var parent: Proxy
        override fun call(): Int {
            parent.parent.printer.print(endpoint(read(parent.parent.runtime)))
            return 0
        }
    }

    @Command(name = "set", mixinStandardHelpOptions = true,
        description = ["Atomically update only SOCKS host/port for the next worker; no host settings change."])
    class Set : Callable<Int> {
        @ParentCommand lateinit var parent: Proxy
        @Option(names = ["--type"], defaultValue = "socks5") lateinit var type: String
        @Option(names = ["--host"], required = true) lateinit var host: String
        @Option(names = ["--port"], required = true) var port = 0
        override fun call(): Int {
            require(type == "socks5") { "Only socks5 is supported; DIRECT fallback is forbidden" }
            val file = parent.parent.runtime.toAbsolutePath()
            val value = read(file)
            value["socks_host"] = host
            value["socks_port"] = port
            val output = endpoint(value)
            val bytes = json.writerWithDefaultPrettyPrinter().writeValueAsBytes(value)
            require(bytes.size <= 32768) { "Runtime descriptor exceeds 32 KiB" }
            // Same-directory atomic replace: при ошибке старый descriptor остаётся целым.
            val temporary = Files.createTempFile(file.parent, ".proxy-", ".tmp")
            try {
                Files.write(temporary, bytes)
                Files.move(temporary, file, ATOMIC_MOVE, REPLACE_EXISTING)
            } finally {
                Files.deleteIfExists(temporary)
            }
            parent.parent.printer.print(output + mapOf("applies_to" to "NEXT_WORKER_START", "network_requests" to 0))
            return 0
        }
    }

    @Command(name = "system", mixinStandardHelpOptions = true, subcommands = [Doctor::class])
    class SystemCommands(val printer: Printer) {
        @Option(names = ["--runtime"], required = true, scope = INHERIT) lateinit var runtime: Path
    }

    @Command(name = "doctor", mixinStandardHelpOptions = true,
        description = ["Check configured SOCKS5 negotiation in this process network namespace; no onion request."])
    class Doctor : Callable<Int> {
        @ParentCommand lateinit var parent: SystemCommands
        override fun call(): Int {
            val proxy = endpoint(read(parent.runtime))
            val socket = Socket()
            var state = "FAILED"
            var category: String? = null
            var future: java.util.concurrent.Future<Boolean>? = null
            try {
                future = probes.submit<Boolean> {
                    socket.connect(InetSocketAddress(proxy["host"] as String, proxy["port"] as Int), 2000)
                    socket.soTimeout = 2000
                    socket.getOutputStream().write(byteArrayOf(5, 1, 0))
                    socket.getInputStream().read() == 5 && socket.getInputStream().read() == 0
                }
                if (future.get(5, TimeUnit.SECONDS)) state = "READY" else category = "SOCKS5_NEGOTIATION_REJECTED"
            } catch (error: java.util.concurrent.TimeoutException) {
                category = "PROXY_PROBE_TIMEOUT"
            } catch (error: java.util.concurrent.RejectedExecutionException) {
                category = "PROXY_PROBE_BUSY"
            } catch (error: java.util.concurrent.ExecutionException) {
                category = "PROXY_UNREACHABLE_OR_IO_ERROR"
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                category = "PROXY_PROBE_CANCELLED"
            } finally {
                socket.close()
                future?.cancel(true)
            }
            parent.printer.print(proxy + mapOf("socks5_negotiation" to state, "error_category" to category,
                "tor_bootstrap" to "NOT_CHECKED", "frontier" to "NOT_CHECKED", "onion_requests" to 0))
            return if (state == "READY") 0 else 6
        }
    }

    companion object {
        private val json = ObjectMapper()
        // Не более одной проверки даже при зависшем системном DNS gateway; CLI bounded, поток daemon.
        private val probes = ThreadPoolExecutor(0, 1, 30, TimeUnit.SECONDS, SynchronousQueue(),
            { task -> Thread(task, "onionall-socks-doctor").apply { isDaemon = true } }, ThreadPoolExecutor.AbortPolicy())

        private fun read(file: Path): MutableMap<String, Any?> {
            if (!Files.isRegularFile(file, NOFOLLOW_LINKS)) throw IOException("Require regular runtime descriptor; start runtime first")
            val bytes = Files.newInputStream(file, NOFOLLOW_LINKS).use { it.readNBytes(32769) }
            if (bytes.size > 32768) throw IOException("Runtime descriptor exceeds 32 KiB")
            return try { json.readValue(bytes, object : TypeReference<MutableMap<String, Any?>>() {}) }
            catch (error: IOException) { throw IOException("Invalid runtime descriptor JSON") }
        }

        private fun endpoint(value: Map<String, Any?>): Map<String, Any?> {
            val host = value["socks_host"] as? String ?: throw IllegalArgumentException("Missing SOCKS host")
            require(host.matches(Regex("[A-Za-z0-9](?:[A-Za-z0-9.-]{0,251}[A-Za-z0-9])?")) &&
                !host.lowercase().endsWith(".onion")) { "Use a local gateway hostname or IPv4, without credentials or URL" }
            val port = value["socks_port"] as? Number ?: throw IllegalArgumentException("Missing SOCKS port")
            require(port.toInt() in 1..65535 && port.toDouble() == port.toInt().toDouble()) { "Require SOCKS port 1..65535" }
            return mapOf("type" to "socks5", "host" to host, "port" to port.toInt(), "direct_fallback" to false,
                "network_namespace" to "CURRENT_PROCESS; container loopback is not the host")
        }
    }
}
