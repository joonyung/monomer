package cc.monomer.agent.api

import java.text.Normalizer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

object AgentJsonPatch {
    fun safeDiagnosticPath(path: String): String? {
        if (path.length > 2_048 || path.any(Char::isISOControl) ||
            !AgentJsonCanonicalizer.hasOnlyUnicodeScalars(path) ||
            Normalizer.normalize(path, Normalizer.Form.NFC) != path
        ) return null
        val tokens = try {
            pointerTokens(path)
        } catch (_: IllegalArgumentException) {
            return null
        }
        return path.takeIf {
            tokens.withIndex().all { (index, token) ->
                token in diagnosticProperties || (
                    tokens.getOrNull(index - 1) in diagnosticArrayProperties &&
                        (token == "-" || (token.matches(CANONICAL_ARRAY_INDEX) && token.toIntOrNull() != null))
                    )
            }
        }
    }

    private val CANONICAL_ARRAY_INDEX = Regex("0|[1-9][0-9]*")

    private val diagnosticDocuments: List<JsonElement> by lazy {
        listOf(
            "contracts/agent-workspace.schema.json",
            "contracts/metric-display.schema.json",
            "contracts/semantic/model.schema.json",
            "contracts/semantic/metric.schema.json",
            "contracts/semantic/query.schema.json",
        ).map { resource ->
            requireNotNull(AgentJsonPatch::class.java.classLoader.getResourceAsStream(resource))
                .bufferedReader().use { Json.parseToJsonElement(it.readText()) }
        }
    }

    private val diagnosticProperties: Set<String> by lazy {
        diagnosticDocuments.flatMap(::declaredProperties).toSet()
    }

    private val diagnosticArrayProperties: Set<String> by lazy {
        diagnosticDocuments.flatMap(::declaredArrayProperties).toSet()
    }

    private fun declaredArrayProperties(node: JsonElement): List<String> = when (node) {
        is JsonObject -> (node["properties"] as? JsonObject).orEmpty().filterValues { definition ->
            definition is JsonObject && definition.containsKey("items")
        }.keys.toList() + node.values.flatMap(::declaredArrayProperties)
        is JsonArray -> node.flatMap(::declaredArrayProperties)
        else -> emptyList()
    }

    private fun declaredProperties(node: JsonElement): List<String> = when (node) {
        is JsonObject -> (node["properties"] as? JsonObject)?.keys.orEmpty().toList() +
            node.values.flatMap(::declaredProperties)
        is JsonArray -> node.flatMap(::declaredProperties)
        else -> emptyList()
    }

    fun apply(
        document: JsonElement,
        operations: List<AgentJsonPatchOperation>,
    ): JsonElement {
        require(operations.isNotEmpty()) { "at least one patch operation is required" }
        require(operations.size <= 100) { "too many patch operations" }
        return operations.foldIndexed(document) { index, current, operation ->
            try {
                applyOne(current, operation)
            } catch (exception: AgentJsonPatchException) {
                throw exception
            } catch (exception: IllegalArgumentException) {
                throw AgentJsonPatchException(index, operation.path, exception.message ?: "patch operation is invalid")
            }
        }
    }

    private fun applyOne(
        document: JsonElement,
        operation: AgentJsonPatchOperation,
    ): JsonElement {
        val tokens = pointerTokens(operation.path)
        return modify(document, tokens, 0, operation)
    }

    private fun modify(
        current: JsonElement,
        tokens: List<String>,
        depth: Int,
        operation: AgentJsonPatchOperation,
    ): JsonElement {
        if (depth == tokens.size) {
            return when (operation.op) {
                AgentJsonPatchOp.REPLACE -> operation.value!!
                AgentJsonPatchOp.ADD,
                AgentJsonPatchOp.REMOVE,
                -> throw IllegalArgumentException("the document root supports replace only")
            }
        }

        val token = tokens[depth]
        val isLeaf = depth == tokens.lastIndex
        return when (current) {
            is JsonObject -> {
                val values = current.toMutableMap()
                if (isLeaf) {
                    when (operation.op) {
                        AgentJsonPatchOp.ADD -> values[token] = operation.value!!
                        AgentJsonPatchOp.REPLACE -> {
                            require(values.containsKey(token)) { "the object member does not exist" }
                            values[token] = operation.value!!
                        }
                        AgentJsonPatchOp.REMOVE -> requireNotNull(values.remove(token)) {
                            "the object member does not exist"
                        }
                    }
                } else {
                    val child = requireNotNull(values[token]) { "the parent object member does not exist" }
                    values[token] = modify(child, tokens, depth + 1, operation)
                }
                JsonObject(values)
            }
            is JsonArray -> {
                val values = current.toMutableList()
                val position = arrayPosition(token, values.size, operation.op == AgentJsonPatchOp.ADD)
                if (isLeaf) {
                    when (operation.op) {
                        AgentJsonPatchOp.ADD -> values.add(position, operation.value!!)
                        AgentJsonPatchOp.REPLACE -> {
                            require(position < values.size) { "the array index does not exist" }
                            values[position] = operation.value!!
                        }
                        AgentJsonPatchOp.REMOVE -> {
                            require(position < values.size) { "the array index does not exist" }
                            values.removeAt(position)
                        }
                    }
                } else {
                    require(position < values.size) { "the parent array index does not exist" }
                    values[position] = modify(values[position], tokens, depth + 1, operation)
                }
                JsonArray(values)
            }
            else -> throw IllegalArgumentException("the patch parent must be an object or array")
        }
    }

    private fun pointerTokens(path: String): List<String> {
        require(path.isEmpty() || path.startsWith('/')) { "JSON Pointer must start with /" }
        if (path.isEmpty()) return emptyList()
        return path.substring(1).split('/').map { token ->
            var index = 0
            buildString(token.length) {
                while (index < token.length) {
                    if (token[index] != '~') {
                        append(token[index++])
                        continue
                    }
                    require(index + 1 < token.length) { "invalid JSON Pointer escape" }
                    when (token[index + 1]) {
                        '0' -> append('~')
                        '1' -> append('/')
                        else -> throw IllegalArgumentException("invalid JSON Pointer escape")
                    }
                    index += 2
                }
            }
        }
    }

    private fun arrayPosition(
        token: String,
        size: Int,
        allowAppend: Boolean,
    ): Int {
        if (allowAppend && token == "-") return size
        require(token.matches(CANONICAL_ARRAY_INDEX)) { "array index is not canonical" }
        val position = token.toIntOrNull() ?: throw IllegalArgumentException("array index is invalid")
        require(position >= 0 && if (allowAppend) position <= size else position < size) {
            "array index is out of bounds"
        }
        return position
    }
}

class AgentJsonPatchException(
    val operationIndex: Int,
    val path: String,
    message: String,
) : IllegalArgumentException(message)
