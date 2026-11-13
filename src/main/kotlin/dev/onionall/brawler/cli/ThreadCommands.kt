package dev.onionall.brawler.cli

import java.nio.file.Path
import java.util.concurrent.Callable
import picocli.CommandLine.*

/**
 * Thread workflow переиспользует approval, Admission/Frontier и существующий Storm worker.
 * @author Стефан Колесников
 * @author GPT-6.1 Sol
 */
@Command(name="thread", mixinStandardHelpOptions=true,
    subcommands=[ThreadCommands.Plan::class, ThreadCommands.Crawl::class, ThreadCommands.Show::class])
class ThreadCommands : Callable<Int> {
    @ParentCommand lateinit var parent: ResearchCommands
    override fun call(): Int {
        parent.sessions().print(mapOf("commands" to listOf("plan", "crawl", "show"), "approval_required" to true))
        return 0
    }
    @Command(name="plan", mixinStandardHelpOptions=true, description=["Create a thread-only immutable scope; inspect and research approve before crawl."])
    class Plan : Callable<Int> {
        @ParentCommand lateinit var parent: ThreadCommands
        @Parameters(index="0") lateinit var url: String
        @Option(names=["--max-pages"], defaultValue="5") var pages=5
        override fun call(): Int {
            parent.parent.sessions().print(parent.parent.sessions().threadPlan(url,pages)); return 0
        }
    }
    @Command(name="crawl", mixinStandardHelpOptions=true, description=["Run an already approved thread session through Frontier/Storm."])
    class Crawl : Callable<Int> {
        @ParentCommand lateinit var parent: ThreadCommands
        @Parameters(index="0") lateinit var id: String
        @Option(names=["--runtime"], required=true) lateinit var runtime: Path
        @Option(names=["--admission-token-file"], required=true) lateinit var token: Path
        @Option(names=["--total-seconds"], defaultValue="600") var total=600
        @Option(names=["--slice-seconds"], defaultValue="90") var slice=90
        @Option(names=["--request-timeout-ms"], defaultValue="30000") var timeout=30000
        @Option(names=["--resume"]) var resume=false
        override fun call(): Int {
            val workflow=parent.parent.sessions()
            require(workflow.session(id)["mode"]=="THREAD") { "Require thread session" }
            val result=workflow.run(id,runtime,token,total,slice,timeout,resume)
            workflow.print(result)
            return if ((result["metrics"] as Map<*, *>)["seeds_execution_failed"]==0) 0 else 6
        }
    }
    @Command(name="show", mixinStandardHelpOptions=true, description=["Read captured thread pages, posts, pagination and limitations."])
    class Show : Callable<Int> {
        @ParentCommand lateinit var parent: ThreadCommands
        @Parameters(index="0") lateinit var id: String
        override fun call(): Int {
            parent.parent.sessions().print(parent.parent.sessions().results(id,null,null)); return 0
        }
    }
}
