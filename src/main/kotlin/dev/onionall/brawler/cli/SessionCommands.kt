package dev.onionall.brawler.cli

import dev.onionall.brawler.research.ResearchExporter
import dev.onionall.brawler.research.ResearchWorkflow
import java.nio.file.Path
import java.util.concurrent.Callable
import picocli.CommandLine.*

/**
 * Команды M6 сохраняют explicit selection/approval и отдельные discovery/crawl/export outcomes.
 * @author Стефан Колесников
 * @author GPT-6.1 Sol
 */
object SessionCommands {
    abstract class Base : Callable<Int> {
        @ParentCommand lateinit var parent: ResearchCommands
        fun workflow() = parent.sessions()
        fun show(value: Map<String, Any?>): Int { workflow().print(value); return 0 }
    }
    @Command(name="discover", mixinStandardHelpOptions=true, description=["Execute authorized Ahmia form search through Tor; no candidate targets are fetched."])
    class Discover : Base() {
        @Option(names=["--provider"], defaultValue="ahmia") lateinit var provider: String
        @Option(names=["--query"], required=true) lateinit var query: String
        @Option(names=["--limit"], defaultValue="20") var limit=20
        @Option(names=["--runtime"], required=true) lateinit var runtime: Path
        override fun call(): Int {
            require(provider=="ahmia") { "Only the verified Ahmia adapter is available" }
            val result=workflow().discover(query,limit,runtime)
            show(result)
            return if(result["discovery_state"]=="FAILED") 6 else 0
        }
    }
    @Command(name="import", mixinStandardHelpOptions=true, description=["Persist explicitly supplied candidate seeds; no provider/network claim."])
    class Import : Base() {
        @Option(names=["--seed"], required=true) lateinit var seeds: List<String>
        @Option(names=["--label"], defaultValue="User supplied seeds") lateinit var label: String
        override fun call()=show(workflow().importSeeds(seeds,label))
    }
    @Command(name="candidates", mixinStandardHelpOptions=true, description=["Read original discovery evidence and selection without crawling."])
    class Candidates : Base() {
        @Parameters(index="0") lateinit var id: String
        override fun call()=show(workflow().session(id))
    }
    @Command(name="select", mixinStandardHelpOptions=true)
    class Select : Base() {
        @Parameters(index="0") lateinit var id: String
        @Option(names=["--ids"], split=",") var ids: List<String> = emptyList()
        @Option(names=["--all-valid"]) var all=false
        @Option(names=["--exclude"], split=",") var exclude: List<String> = emptyList()
        override fun call(): Int {
            require(all || ids.isNotEmpty() || exclude.isNotEmpty()) { "Choose --all-valid, --ids or --exclude" }
            return show(workflow().select(id,ids,all,exclude))
        }
    }
    @Command(name="approve", mixinStandardHelpOptions=true, description=["Approve the exact stored immutable batch scope."])
    class Approve : Base() {
        @Parameters(index="0") lateinit var id: String
        override fun call()=show(workflow().approve(id))
    }
    @Command(name="status", mixinStandardHelpOptions=true, description=["Separate acquisition, child execution and unknown Frontier terminal provenance."])
    class Status : Base() {
        @Parameters(index="0") lateinit var id: String
        @Option(names=["--runtime"]) var runtime: Path?=null
        @Option(names=["--admission-token-file"]) var token: Path?=null
        override fun call()=show(workflow().results(id,runtime,token))
    }
    @Command(name="pages", mixinStandardHelpOptions=true)
    class Pages : Base() {
        @Parameters(index="0") lateinit var id: String
        override fun call(): Int {
            val result=workflow().results(id,null,null)
            return show(mapOf("session_id" to id,"pages" to result["pages"]))
        }
    }
    @Command(name="export", mixinStandardHelpOptions=true, description=["Bounded local export of untrusted captured text and source evidence; no AI upload."])
    class Export : Base() {
        @Parameters(index="0") lateinit var id: String
        @Option(names=["--format"], defaultValue="markdown") lateinit var format: String
        @Option(names=["--output"], required=true) lateinit var output: Path
        override fun call(): Int {
            val result=workflow().results(id,null,null)
            return show(ResearchExporter.export(result,workflow().pageDetails(result),format,output))
        }
    }
}
