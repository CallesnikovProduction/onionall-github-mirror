package dev.onionall.brawler.research

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.StreamReadConstraints
import com.fasterxml.jackson.core.StreamReadFeature
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.StandardCharsets.UTF_8
import java.util.Locale

/**
 * Validates a bounded JSON batch manifest without acquiring source approval or starting research.
 *
 * @author Стефан Колесников
 * @author GPT-6.2 Sol
 */
internal object ResearchBatchValidation {
    const val MAX_BYTES = 256 * 1024
    private const val MAX_OBJECTIVES = 200
    private const val MAX_QUERY_CHARS = 256
    private const val EXECUTION_PROFILE = "bounded-research"
    private const val SOURCE_POLICY = "approved-sources"
    private const val DOWNLOAD_POLICY = "DISCOVER_ONLY"
    private val idFormat = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
    private val batchFields = setOf("batchId", "executionProfile", "objectives", "budget")
    private val objectiveFields = setOf("id", "query", "intent", "requirements", "sourcePolicy", "downloadPolicy", "budget")
    private val requirementFields = setOf("sourceTypes", "extract")
    private val objectiveBudgetFields = setOf("depth", "pagesPerSeed", "globalPages", "totalSeconds", "sliceSeconds", "requestTimeoutMs")
    private val requiredSource = mapOf("DOCUMENTATION_RESEARCH" to "DOCUMENTATION", "FORUM_RESEARCH" to "FORUM")
    private val sourceTypes = requiredSource.values.toSet()
    private val extractTypes = setOf("TEXT", "CODE", "QUOTATIONS", "THREADS", "POSTS")
    private val json = ObjectMapper(JsonFactory.builder()
        .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(8).maxStringLength(1024).build())
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .build()).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)

    private class Invalid(val code: String, val path: String, message: String) : Exception(message)

    /** Reads at most one byte beyond the cap and leaves stream ownership with the caller. */
    @JvmStatic
    fun validate(input: InputStream): Map<String, Any?> = try {
        validate(input.readNBytes(MAX_BYTES + 1))
    } catch (_: IOException) {
        invalid("INPUT_READ_FAILED", "$", "Could not read batch input")
    }

    @JvmStatic
    fun validate(input: ByteArray): Map<String, Any?> {
        if (input.size > MAX_BYTES) return invalid("LIMIT_EXCEEDED", "$", "Batch input exceeds 256 KiB")
        val text = try {
            UTF_8.newDecoder().decode(ByteBuffer.wrap(input)).toString().removePrefix("\uFEFF")
        } catch (_: CharacterCodingException) {
            return invalid("INVALID_ENCODING", "$", "Batch input must be UTF-8")
        }
        val root = try {
            json.readTree(text)
        } catch (_: IOException) {
            // Parser messages can contain input and are deliberately kept out of the result.
            return invalid("INVALID_JSON", "$", "Expected one bounded JSON document with unique object keys")
        }
        return try {
            validateManifest(root)
        } catch (failure: Invalid) {
            invalid(failure.code, failure.path, failure.message.orEmpty())
        }
    }

    private fun validateManifest(root: JsonNode?): Map<String, Any?> {
        val batch = obj(root, "$", batchFields)
        val batchId = identifier(batch.get("batchId"), "$.batchId")
        if (batch.path("executionProfile").textValue() != EXECUTION_PROFILE) {
            fail("UNSUPPORTED_VALUE", "$.executionProfile", "Expected bounded-research execution profile")
        }
        val sharedPages = batch.get("budget")?.let {
            val budget = obj(it, "$.budget", setOf("globalPages"))
            integer(budget.get("globalPages"), "$.budget.globalPages", 1..100)
        }
        val objectives = batch.path("objectives")
        if (!objectives.isArray) fail("INVALID_SHAPE", "$.objectives", "Expected an objective array")
        if (objectives.size() !in 1..MAX_OBJECTIVES) {
            fail("LIMIT_EXCEEDED", "$.objectives", "Batch requires between 1 and 200 objectives")
        }

        var allocatedPages = 0
        val ids = mutableSetOf<String>()
        val declaredIds = objectives.mapIndexed { index, entry ->
            val path = "$.objectives[$index]"
            val objective = obj(entry, path, objectiveFields)
            val id = identifier(objective.get("id"), "$path.id")
            if (!ids.add(id.uppercase(Locale.ROOT))) {
                fail("DUPLICATE_OBJECTIVE_ID", "$path.id", "Objective identifiers must be unique")
            }
            validateObjective(objective, path)
            val pages = objective.get("budget")?.let { validateBudget(it, "$path.budget") }
            if (sharedPages != null) {
                allocatedPages += pages ?: fail("INVALID_BUDGET", "$path.budget.globalPages",
                    "Declare each objective page allocation when using a shared page cap")
                if (allocatedPages > sharedPages) {
                    fail("INVALID_BUDGET", "$.budget.globalPages", "Objective allocations exceed the shared page cap")
                }
            }
            id
        }

        return envelope("VALID") + mapOf(
            "batch_id" to batchId,
            "execution_profile" to EXECUTION_PROFILE,
            "objective_count" to declaredIds.size,
            "objective_ids" to declaredIds,
            "source_policy" to SOURCE_POLICY,
            "download_policy" to DOWNLOAD_POLICY,
            "shared_global_pages" to sharedPages,
            "allocated_global_pages" to (if (sharedPages == null) null else allocatedPages)
        )
    }

    private fun validateObjective(objective: JsonNode, path: String) {
        val query = objective.path("query").textValue()
        if (query == null || query.isBlank() || query.length > MAX_QUERY_CHARS ||
            query.any { it.isISOControl() } || !UTF_8.newEncoder().canEncode(query)) {
            fail("INVALID_QUERY", "$path.query", "Require a nonblank query of at most 256 characters without control codes")
        }
        val source = requiredSource[objective.path("intent").textValue()]
            ?: fail("UNSUPPORTED_VALUE", "$path.intent", "Unsupported research intent")
        if (objective.path("sourcePolicy").textValue() != SOURCE_POLICY) {
            fail("UNSUPPORTED_POLICY", "$path.sourcePolicy", "Require explicit approved-sources policy")
        }
        if (objective.path("downloadPolicy").textValue() != DOWNLOAD_POLICY) {
            fail("UNSUPPORTED_POLICY", "$path.downloadPolicy", "Only DISCOVER_ONLY download policy is supported")
        }
        val requirements = obj(objective.get("requirements"), "$path.requirements", requirementFields)
        val requestedSources = values(requirements.path("sourceTypes"), "$path.requirements.sourceTypes", sourceTypes)
        val requestedExtract = values(requirements.path("extract"), "$path.requirements.extract", extractTypes)
        if (source !in requestedSources || ("THREADS" in requestedExtract || "POSTS" in requestedExtract) &&
            "FORUM" !in requestedSources) {
            fail("INVALID_REQUIREMENTS", "$path.requirements", "Source types must match the intent and requested evidence")
        }
    }

    private fun validateBudget(value: JsonNode, path: String): Int? {
        val budget = obj(value, path, objectiveBudgetFields)
        if (budget.isEmpty) fail("INVALID_BUDGET", path, "Provide at least one objective budget field")
        optionalInteger(budget, path, "depth", 0..3)
        val perSeed = optionalInteger(budget, path, "pagesPerSeed", 1..20)
        val global = optionalInteger(budget, path, "globalPages", 1..100)
        if (perSeed != null && global != null && perSeed > global) {
            fail("INVALID_BUDGET", "$path.globalPages", "Objective cap must cover at least one seed allocation")
        }
        val total = optionalInteger(budget, path, "totalSeconds", 30..3600)
        val slice = optionalInteger(budget, path, "sliceSeconds", 10..180)
        optionalInteger(budget, path, "requestTimeoutMs", 1000..30000)
        if ((total == null) != (slice == null) || (total != null && slice != null && total < slice + 180)) {
            fail("INVALID_BUDGET", path, "Declare both time budgets and include 180 seconds for bounded cleanup")
        }
        return global
    }

    private fun identifier(node: JsonNode?, path: String): String =
        node?.textValue()?.takeIf { idFormat.matches(it) }
            ?: fail("INVALID_ID", path, "Require a 1..64 character identifier starting with an ASCII letter or digit")

    private fun values(node: JsonNode, path: String, allowed: Set<String>): Set<String> {
        if (!node.isArray || node.size() !in 1..allowed.size) {
            fail("INVALID_SHAPE", path, "Require a nonempty bounded array")
        }
        val seen = mutableSetOf<String>()
        for (item in node) {
            val value = item.textValue()
            if (value == null || value !in allowed || !seen.add(value)) {
                fail("UNSUPPORTED_VALUE", path, "Unknown or repeated option")
            }
        }
        return seen
    }

    private fun obj(node: JsonNode?, path: String, fields: Set<String>): JsonNode {
        if (node == null || !node.isObject) fail("INVALID_SHAPE", path, "Expected an object")
        if (!node.fieldNames().asSequence().all { it in fields }) {
            fail("UNKNOWN_FIELD", path, "Object contains an unsupported field")
        }
        return node
    }

    private fun optionalInteger(node: JsonNode, path: String, key: String, range: IntRange): Int? =
        node.get(key)?.let { integer(it, "$path.$key", range) }

    private fun integer(node: JsonNode?, path: String, range: IntRange): Int {
        if (node == null || !node.isIntegralNumber || !node.canConvertToInt() || node.intValue() !in range) {
            fail("INVALID_BUDGET", path, "Require an integer between ${range.first} and ${range.last}")
        }
        return node.intValue()
    }

    private fun fail(code: String, path: String, message: String): Nothing = throw Invalid(code, path, message)

    private fun invalid(code: String, path: String, message: String): Map<String, Any?> =
        envelope("INVALID") + ("error" to mapOf("code" to code, "path" to path, "message" to message))

    private fun envelope(status: String): Map<String, Any?> = mapOf(
        "schema_version" to 1,
        "valid" to (status == "VALID"),
        "status" to status,
        "source_approval" to "NOT_GRANTED",
        "execution" to "NOT_STARTED",
        "network_requests" to 0,
        "persistent_writes" to 0
    )
}
