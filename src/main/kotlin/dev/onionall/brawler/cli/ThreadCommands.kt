package dev.onionall.brawler.cli

import java.nio.file.Path
import java.util.concurrent.Callable
import picocli.CommandLine.*

/**
 * **Назначение:** Предоставить thread workflow поверх существующих approval, Admission/Frontier и Storm worker.
 *
 * @author Stephan Kolesnikov
 * @author GPT-6.1 Sol
 */
@Command(
    name = "thread",
    mixinStandardHelpOptions = true,
    subcommands = [
        ThreadCommands.Plan::class,
        ThreadCommands.Crawl::class,
        ThreadCommands.Show::class
    ]
)
class ThreadCommands : Callable<Int> {

    /**
     * **Назначение:** Получить родительскую команду, назначенную Picocli.
     *
     * @author Stephan Kolesnikov
     * @author GPT-6.1 Sol
     */
    @ParentCommand
    lateinit var parent: ResearchCommands

    /**
     * **Назначение:** Вывести доступные подкоманды и признак обязательного одобрения.
     *
     * @author Stephan Kolesnikov
     * @author GPT-6.1 Sol
     */
    override fun call(): Int {
        parent.sessions().print(mapOf("commands" to listOf("plan", "crawl", "show"), "approval_required" to true))
        return 0
    }

    /**
     * **Назначение:** Создать thread-only immutable scope для последующего одобрения и crawl.
     *
     * @author Stephan Kolesnikov
     * @author GPT-6.1 Sol
     */
    @Command(
        name = "plan",
        mixinStandardHelpOptions = true,
        description = ["Create a thread-only immutable scope; inspect and research approve before crawl."]
    )
    class Plan : Callable<Int> {

        /**
         * **Назначение:** Получить родительскую команду, назначенную Picocli.
         *
         * @author Stephan Kolesnikov
         * @author GPT-6.1 Sol
         */
        @ParentCommand
        lateinit var parent: ThreadCommands

        /**
         * **Назначение:** Задать исходный URL для создания плана.
         *
         * @author Stephan Kolesnikov
         * @author GPT-6.1 Sol
         */
        @Parameters(index = "0")
        lateinit var url: String

        /**
         * **Назначение:** Ограничить число страниц создаваемого плана.
         *
         * @author Stephan Kolesnikov
         * @author GPT-6.1 Sol
         */
        @Option(names = ["--max-pages"], defaultValue = "5")
        var pages = 5

        /**
         * **Назначение:** Создать thread-план и вывести его scope.
         *
         * @author Stephan Kolesnikov
         * @author GPT-6.1 Sol
         */
        override fun call(): Int {
            parent.parent.sessions().print(parent.parent.sessions().threadPlan(url, pages));
            return 0
        }
    }

    /**
     * **Назначение:** Запустить уже одобренную thread-сессию через существующий Frontier/Storm workflow.
     *
     * @author Stephan Kolesnikov
     * @author GPT-6.1 Sol
     */
    @Command(
        name = "crawl",
        mixinStandardHelpOptions = true,
        description = ["Run an already approved thread session through Frontier/Storm."]
    )
    class Crawl : Callable<Int> {

        /**
         * **Назначение:** Получить родительскую команду, назначенную Picocli.
         *
         * @author Stephan Kolesnikov
         * @author GPT-6.1 Sol
         */
        @ParentCommand
        lateinit var parent: ThreadCommands

        /**
         * **Назначение:** Указать идентификатор research-сессии.
         *
         * @author Stephan Kolesnikov
         * @author GPT-6.1 Sol
         */
        @Parameters(index = "0")
        lateinit var id: String

        /**
         * **Назначение:** Указать путь к существующему runtime descriptor.
         *
         * @author Stephan Kolesnikov
         * @author GPT-6.1 Sol
         */
        @Option(names = ["--runtime"], required = true)
        lateinit var runtime: Path

        /**
         * **Назначение:** Указать путь к файлу capability для обращения к Admission/Frontier.
         *
         * @author Stephan Kolesnikov
         * @author GPT-6.1 Sol
         */
        @Option(names = ["--admission-token-file"], required = true)
        lateinit var token: Path

        /**
         * **Назначение:** Ограничить общее время исполнения thread-сессии в секундах.
         *
         * @author Stephan Kolesnikov
         * @author GPT-6.1 Sol
         */
        @Option(names = ["--total-seconds"], defaultValue = "600")
        var total = 600

        /**
         * **Назначение:** Задать продолжительность одного интервала исполнения thread-сессии в секундах.
         *
         * @author Stephan Kolesnikov
         * @author GPT-6.1 Sol
         */
        @Option(names = ["--slice-seconds"], defaultValue = "90")
        var slice = 90

        /**
         * **Назначение:** Задать timeout сетевого запроса thread-сессии в миллисекундах.
         *
         * @author Stephan Kolesnikov
         * @author GPT-6.1 Sol
         */
        @Option(names = ["--request-timeout-ms"], defaultValue = "30000")
        var timeout = 30000

        /**
         * **Назначение:** Запросить продолжение исполнения существующей сессии.
         *
         * @author Stephan Kolesnikov
         * @author GPT-6.1 Sol
         */
        @Option(names = ["--resume"])
        var resume = false

        /**
         * **Назначение:** Проверить режим THREAD, запустить workflow и вывести результат.
         *
         * @author Stephan Kolesnikov
         * @author GPT-6.1 Sol
         */
        override fun call(): Int {
            val workflow = parent.parent.sessions()
            require(workflow.session(id)["mode"] == "THREAD") { "Require thread session" }
            val result = workflow.run(id, runtime, token, total, slice, timeout, resume)
            workflow.print(result)
            return if ((result["metrics"] as Map<*, *>)["seeds_execution_failed"] == 0) 0 else 6
        }
    }

    /**
     * **Назначение:** Показать захваченные thread-страницы, сообщения, пагинацию и ограничения.
     *
     * @author Stephan Kolesnikov
     * @author GPT-6.1 Sol
     */
    @Command(
        name = "show",
        mixinStandardHelpOptions = true,
        description = ["Read captured thread pages, posts, pagination and limitations."]
    )
    class Show : Callable<Int> {

        /**
         * **Назначение:** Получить родительскую команду, назначенную Picocli.
         *
         * @author Stephan Kolesnikov
         * @author GPT-6.1 Sol
         */
        @ParentCommand
        lateinit var parent: ThreadCommands

        /**
         * **Назначение:** Указать идентификатор research-сессии.
         *
         * @author Stephan Kolesnikov
         * @author GPT-6.1 Sol
         */
        @Parameters(index = "0")
        lateinit var id: String

        /**
         * **Назначение:** Вывести захваченные результаты thread-сессии.
         *
         * @author Stephan Kolesnikov
         * @author GPT-6.1 Sol
         */
        override fun call(): Int {
            parent.parent.sessions().print(parent.parent.sessions().results(id, null, null));
            return 0
        }
    }
}
