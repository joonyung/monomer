package cc.monomer.pages.domain.common

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

data class JsonPatchError(
    val code: String,
    val message: String,
    val path: String?,
    val operationIndex: Int?,
)

sealed class JsonPatchResult {
    data class Success(val document: JsonElement, val applied: Int) : JsonPatchResult()
    data class Failure(val error: JsonPatchError) : JsonPatchResult()
}

object JsonPatchEngine {

    fun apply(document: JsonElement, patches: List<JsonObject>): JsonPatchResult {
        var working = document
        patches.forEachIndexed { index, raw ->
            val op = raw["op"]?.jsonPrimitive?.contentOrNull
                ?: return fail("MISSING_OPERATION", "Patch operation is missing 'op'.", null, index)
            val path = raw["path"]?.jsonPrimitive?.contentOrNull
                ?: return fail("MISSING_PATH", "Patch operation is missing 'path'.", null, index)
            val value = raw["value"]

            val tokens = when (val parsed = parsePointer(path, index)) {
                is Step.Ok -> parsed.value
                is Step.Err -> return JsonPatchResult.Failure(parsed.error)
            }

            val updated = when (op) {
                "add" -> {
                    if (value == null) return fail("MISSING_VALUE", "Patch operation 'add' requires 'value'.", path, index)
                    addAt(working, tokens, value, index, path)
                }
                "remove" -> removeAt(working, tokens, index, path)
                "replace" -> {
                    if (value == null) return fail("MISSING_VALUE", "Patch operation 'replace' requires 'value'.", path, index)
                    replaceAt(working, tokens, value, index, path)
                }
                "test" -> {
                    if (value == null) return fail("MISSING_VALUE", "Patch operation 'test' requires 'value'.", path, index)
                    val actual = getAt(working, tokens, index, path)
                    when (actual) {
                        is Step.Ok ->
                            if (actual.value == value) Step.Ok(working)
                            else Step.Err(err("TEST_OPERATION_FAILED", "Test operation failed.", path, index))
                        is Step.Err -> actual
                    }
                }
                else -> return fail("UNSUPPORTED_OPERATION", "Unsupported patch operation '$op'.", path, index)
            }

            when (updated) {
                is Step.Ok -> working = updated.value
                is Step.Err -> return JsonPatchResult.Failure(updated.error)
            }
        }
        return JsonPatchResult.Success(working, patches.size)
    }

    private sealed class Step<out T> {
        data class Ok<T>(val value: T) : Step<T>()
        data class Err(val error: JsonPatchError) : Step<Nothing>()
    }

    private fun parsePointer(path: String, opIndex: Int): Step<List<String>> {
        if (path.isEmpty()) return Step.Ok(emptyList())
        if (!path.startsWith("/")) {
            return Step.Err(err("INVALID_POINTER", "Patch path must start with '/'.", path, opIndex))
        }
        val decoded = mutableListOf<String>()
        for (rawToken in path.substring(1).split("/")) {
            when (val token = decodeToken(rawToken, opIndex, path)) {
                is Step.Ok -> decoded += token.value
                is Step.Err -> return token
            }
        }
        return Step.Ok(decoded)
    }

    private fun decodeToken(token: String, opIndex: Int, path: String): Step<String> {
        val sb = StringBuilder()
        var cursor = 0
        while (cursor < token.length) {
            val ch = token[cursor]
            if (ch != '~') { sb.append(ch); cursor++; continue }
            if (cursor + 1 >= token.length) {
                return Step.Err(err("INVALID_POINTER_ESCAPE", "Invalid pointer escape.", path, opIndex))
            }
            when (token[cursor + 1]) {
                '0' -> sb.append('~')
                '1' -> sb.append('/')
                else -> return Step.Err(err("INVALID_POINTER_ESCAPE", "Invalid pointer escape.", path, opIndex))
            }
            cursor += 2
        }
        return Step.Ok(sb.toString())
    }

    private fun addAt(
        current: JsonElement, tokens: List<String>, value: JsonElement,
        opIndex: Int, path: String,
    ): Step<JsonElement> {
        if (tokens.isEmpty()) return Step.Ok(value)
        val head = tokens.first()
        val tail = tokens.drop(1)
        return when (current) {
            is JsonObject -> {
                if (tail.isEmpty()) {
                    Step.Ok(JsonObject(current + (head to value)))
                } else {
                    val child = current[head]
                        ?: return Step.Err(err("PATH_NOT_RESOLVABLE", "Add path is not resolvable.", path, opIndex))
                    when (val updated = addAt(child, tail, value, opIndex, path)) {
                        is Step.Ok -> Step.Ok(JsonObject(current + (head to updated.value)))
                        is Step.Err -> updated
                    }
                }
            }
            is JsonArray -> {
                val mutable = current.toMutableList()
                if (tail.isEmpty()) {
                    val idx = if (head == "-") mutable.size
                    else when (val p = parseArrayIndex(head, mutable.size, true, opIndex, path)) {
                        is Step.Ok -> p.value
                        is Step.Err -> return p
                    }
                    mutable.add(idx, value)
                    Step.Ok(JsonArray(mutable))
                } else {
                    val idx = when (val p = parseArrayIndex(head, mutable.size, false, opIndex, path)) {
                        is Step.Ok -> p.value
                        is Step.Err -> return p
                    }
                    when (val updated = addAt(mutable[idx], tail, value, opIndex, path)) {
                        is Step.Ok -> { mutable[idx] = updated.value; Step.Ok(JsonArray(mutable)) }
                        is Step.Err -> updated
                    }
                }
            }
            else -> Step.Err(err("SCALAR_TRAVERSAL", "Add path traverses a scalar value.", path, opIndex))
        }
    }

    private fun removeAt(
        current: JsonElement, tokens: List<String>,
        opIndex: Int, path: String,
    ): Step<JsonElement> {
        if (tokens.isEmpty()) {
            return Step.Err(err("ROOT_REMOVE_NOT_ALLOWED", "Remove operation cannot target the document root.", path, opIndex))
        }
        val head = tokens.first()
        val tail = tokens.drop(1)
        return when (current) {
            is JsonObject -> {
                if (tail.isEmpty()) {
                    if (!current.containsKey(head))
                        return Step.Err(err("PATH_NOT_FOUND", "Remove path does not exist.", path, opIndex))
                    Step.Ok(JsonObject(current.filterKeys { it != head }))
                } else {
                    val child = current[head]
                        ?: return Step.Err(err("PATH_NOT_RESOLVABLE", "Remove path is not resolvable.", path, opIndex))
                    when (val updated = removeAt(child, tail, opIndex, path)) {
                        is Step.Ok -> Step.Ok(JsonObject(current + (head to updated.value)))
                        is Step.Err -> updated
                    }
                }
            }
            is JsonArray -> {
                val mutable = current.toMutableList()
                val idx = when (val p = parseArrayIndex(head, mutable.size, false, opIndex, path)) {
                    is Step.Ok -> p.value
                    is Step.Err -> return p
                }
                if (tail.isEmpty()) {
                    mutable.removeAt(idx)
                    Step.Ok(JsonArray(mutable))
                } else {
                    when (val updated = removeAt(mutable[idx], tail, opIndex, path)) {
                        is Step.Ok -> { mutable[idx] = updated.value; Step.Ok(JsonArray(mutable)) }
                        is Step.Err -> updated
                    }
                }
            }
            else -> Step.Err(err("SCALAR_TRAVERSAL", "Remove path traverses a scalar value.", path, opIndex))
        }
    }

    private fun replaceAt(
        current: JsonElement, tokens: List<String>, value: JsonElement,
        opIndex: Int, path: String,
    ): Step<JsonElement> {
        if (tokens.isEmpty()) return Step.Ok(value)
        val head = tokens.first()
        val tail = tokens.drop(1)
        return when (current) {
            is JsonObject -> {
                val child = current[head]
                    ?: return Step.Err(err("PATH_NOT_FOUND", "Replace path does not exist.", path, opIndex))
                if (tail.isEmpty()) {
                    Step.Ok(JsonObject(current + (head to value)))
                } else {
                    when (val updated = replaceAt(child, tail, value, opIndex, path)) {
                        is Step.Ok -> Step.Ok(JsonObject(current + (head to updated.value)))
                        is Step.Err -> updated
                    }
                }
            }
            is JsonArray -> {
                val mutable = current.toMutableList()
                val idx = when (val p = parseArrayIndex(head, mutable.size, false, opIndex, path)) {
                    is Step.Ok -> p.value
                    is Step.Err -> return p
                }
                if (tail.isEmpty()) {
                    mutable[idx] = value
                    Step.Ok(JsonArray(mutable))
                } else {
                    when (val updated = replaceAt(mutable[idx], tail, value, opIndex, path)) {
                        is Step.Ok -> { mutable[idx] = updated.value; Step.Ok(JsonArray(mutable)) }
                        is Step.Err -> updated
                    }
                }
            }
            else -> Step.Err(err("SCALAR_TRAVERSAL", "Replace path traverses a scalar value.", path, opIndex))
        }
    }

    private fun getAt(
        current: JsonElement, tokens: List<String>,
        opIndex: Int, path: String,
    ): Step<JsonElement> {
        var cursor = current
        for (token in tokens) {
            cursor = when (cursor) {
                is JsonObject -> cursor[token]
                    ?: return Step.Err(err("PATH_NOT_FOUND", "Path does not exist.", path, opIndex))
                is JsonArray -> {
                    val idx = when (val p = parseArrayIndex(token, cursor.size, false, opIndex, path)) {
                        is Step.Ok -> p.value
                        is Step.Err -> return p
                    }
                    cursor[idx]
                }
                else -> return Step.Err(err("SCALAR_TRAVERSAL", "Path traverses a scalar value.", path, opIndex))
            }
        }
        return Step.Ok(cursor)
    }

    private fun parseArrayIndex(
        token: String, size: Int, allowEnd: Boolean,
        opIndex: Int, path: String,
    ): Step<Int> {
        if (allowEnd && token == "-") return Step.Ok(size)
        if (!token.matches(ARRAY_INDEX_REGEX)) {
            return Step.Err(err("INVALID_ARRAY_INDEX", "Invalid array index '$token'.", path, opIndex))
        }
        val parsed = token.toIntOrNull()
            ?: return Step.Err(err("ARRAY_INDEX_OVERFLOW", "Array index '$token' is too large.", path, opIndex))
        val max = if (allowEnd) size else size - 1
        if (parsed < 0 || parsed > max) {
            return Step.Err(err("ARRAY_INDEX_OUT_OF_BOUNDS", "Array index '$token' is out of bounds.", path, opIndex))
        }
        return Step.Ok(parsed)
    }

    private fun err(code: String, message: String, path: String?, opIndex: Int) =
        JsonPatchError(code = code, message = message, path = path, operationIndex = opIndex)

    private fun fail(code: String, message: String, path: String?, opIndex: Int) =
        JsonPatchResult.Failure(err(code, message, path, opIndex))

    private val ARRAY_INDEX_REGEX = Regex("0|[1-9][0-9]*")
}
