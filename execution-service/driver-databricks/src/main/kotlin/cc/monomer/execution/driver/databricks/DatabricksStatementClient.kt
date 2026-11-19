package cc.monomer.execution.driver.databricks

import cc.monomer.execution.driver.spi.*
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.*
import java.io.FilterInputStream
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Duration
import java.util.Base64
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class DatabricksFailure(val code: String, message: String, val vendorMessage: String? = null) : RuntimeException(message)
internal class DatabricksDeadlineExceeded : RuntimeException("Databricks deadline was exceeded")

internal class DatabricksStatementClient(
    connector: ConnectorRuntimeBinding,
    private val credential: MaterializedCredential,
    private val httpClient: HttpClient,
    private val dispatcher: CoroutineDispatcher,
    private val clock: Clock,
    private val maximumCallDuration: Duration,
    private val timing: DriverOperationTiming,
) {
    private val host = connector.databricksText("host", required = true)!!
    private val warehouseId = connector.databricksText("warehouse_id", required = true)!!
    private val catalog = connector.databricksText("catalog", required = false)
    private val schema = connector.databricksText("schema", required = false)
    private val statementIds = ConcurrentHashMap.newKeySet<String>()
    private val cancelSent = ConcurrentHashMap.newKeySet<String>()
    private val resultHeaderValues = ConcurrentHashMap.newKeySet<String>()
    private val abandoned = AtomicBoolean(false)
    private var token: String? = null
    private var tokenExpiresAt = 0L

    init {
        require(DATABRICKS_HOST.matches(host)) { "Databricks host must be an official public workspace hostname" }
        require(Regex("[A-Za-z0-9]{1,128}").matches(warehouseId)) { "Databricks warehouse ID is invalid" }
        require(httpClient.followRedirects() == HttpClient.Redirect.NEVER) { "Databricks HTTP client must not follow redirects" }
        require(httpClient.cookieHandler().isEmpty && httpClient.authenticator().isEmpty) { "Databricks HTTP client must not add ambient authentication or cookies" }
        val retryAll = System.getProperty("jdk.httpclient.enableAllMethodRetry")
        require(retryAll == null || (retryAll.isNotEmpty() && !retryAll.toBoolean())) {
            "Databricks HTTP client cannot enable retry of all HTTP methods"
        }
        require(credential.fieldNames() in setOf(setOf("access_token"), setOf("oauth_client_id", "oauth_client_secret"))) {
            "Databricks credential fields do not match the driver contract"
        }
        require(maximumCallDuration.toMillis() > 0)
    }

    suspend fun submit(sql: String, parameters: List<QueryParameter>, maximumRows: ULong?, deadline: Long): JsonNode {
        val payload = mapper.createObjectNode().put("warehouse_id", warehouseId).put("statement", sql)
            .put("format", "ARROW_STREAM").put("disposition", "EXTERNAL_LINKS").put("wait_timeout", "0s")
            .set<com.fasterxml.jackson.databind.node.ObjectNode>("parameters", databricksParameters(parameters))
        catalog?.let { payload.put("catalog", it) }
        schema?.let { payload.put("schema", it) }
        maximumRows?.let { payload.put("row_limit", java.math.BigInteger(it.toString())) }
        return json("POST", "/api/2.0/sql/statements", payload.toString(), deadline, isSubmission = true)
    }

    suspend fun observe(id: String, deadline: Long): JsonNode = json("GET", statementPath(id), null, deadline, false)
    suspend fun chunk(id: String, index: Int, deadline: Long): JsonNode {
        require(index >= 0)
        return json("GET", "${statementPath(id)}/result/chunks/$index", null, deadline, false)
    }

    fun externalHeaders(headers: JsonNode): Map<String, String> {
        if (headers.isMissingNode) return emptyMap()
        if (!headers.isObject) throw protocolFailure("Databricks result headers are invalid")
        val values = headers.properties().associate { (name, value) ->
            if (name.lowercase(Locale.ROOT) in FORBIDDEN_RESULT_HEADERS) {
                throw protocolFailure("Databricks result headers contain a forbidden name")
            }
            if (!HTTP_HEADER_NAME.matches(name) || !value.isTextual) {
                throw protocolFailure("Databricks result headers are invalid")
            }
            val text = value.asText()
            if (text.any { it != '\t' && it.code !in 32..126 && it.code !in 128..255 }) {
                throw protocolFailure("Databricks result headers are invalid")
            }
            name to text
        }
        values.values.filter(String::isNotEmpty).forEach(resultHeaderValues::add)
        return values
    }

    suspend fun download(link: String, headers: Map<String, String>, deadline: Long): HttpResponse<InputStream> {
        val destination = try { URI(link) } catch (_: Throwable) { throw protocolFailure("Databricks result link is invalid") }
        if (destination.scheme != "https" || destination.host.isNullOrBlank() || destination.rawUserInfo != null || destination.fragment != null) {
            throw protocolFailure("Databricks result link is invalid")
        }
        val request = try {
            HttpRequest.newBuilder(destination).timeout(callDuration(deadline)).apply { headers.forEach(::header) }.GET().build()
        } catch (failure: DatabricksDeadlineExceeded) { throw failure }
        catch (_: Throwable) { throw protocolFailure("Databricks result headers are invalid") }
        return send(request, false, deadline)
    }

    fun boundedInput(input: InputStream, deadline: Long, maximumBytes: Long): InputStream =
        object : FilterInputStream(input) {
            private var consumed = 0L
            private fun count(bytes: Int): Int {
                remainingMillis(deadline)
                if (bytes > 0) {
                    if (consumed > maximumBytes - bytes) throw DatabricksFailure("RESULT_LIMIT_EXCEEDED", "Databricks result download exceeded the byte limit")
                    consumed += bytes
                }
                return bytes
            }
            override fun read(): Int {
                remainingMillis(deadline)
                val value = super.read()
                count(if (value < 0) 0 else 1)
                return value
            }
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                remainingMillis(deadline)
                return count(`in`.read(bytes, offset, length))
            }
        }

    suspend fun cancelAll() = timing.measure(DriverPhase.CLEANUP) {
        abandoned.set(true)
        withContext(NonCancellable) {
            withTimeoutOrNull(CANCEL_BUDGET_MILLIS) {
                val deadline = clock.millis() + CANCEL_BUDGET_MILLIS
                statementIds.toList().forEach { cancelOnce(it, deadline) }
            }
        }
    }

    fun close() {
        token = null
        tokenExpiresAt = 0
        resultHeaderValues.clear()
    }

    private suspend fun cancelOnce(id: String, deadline: Long, knownAuthorization: String? = null) {
        if (!cancelSent.add(id)) return
        timing.measure(DriverPhase.CLEANUP) {
            runCatching {
                if (knownAuthorization == null) json("POST", "${statementPath(id)}/cancel", "{}", deadline, false, allowEmpty = true, measureCredential = false)
                else {
                    val request = HttpRequest.newBuilder(URI("https://$host${statementPath(id)}/cancel"))
                        .timeout(callDuration(deadline)).header("Authorization", knownAuthorization)
                        .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{}")).build()
                    val response = send(request, false, deadline)
                    response.body().close()
                    if (response.statusCode() !in 200..299) throw httpFailure(response.statusCode(), null)
                }
            }.onFailure { timing.cleanupFailed() }
        }
    }

    private fun registerId(response: JsonNode, submissionAuthorization: String) {
        val id = response.path("statement_id").takeIf(JsonNode::isTextual)?.asText() ?: return
        if (!STATEMENT_ID.matches(id)) throw protocolFailure("Databricks statement ID is invalid")
        statementIds.add(id)
        if (abandoned.get()) {
            CoroutineScope(NonCancellable + dispatcher).launch {
                withTimeoutOrNull(CANCEL_BUDGET_MILLIS) { cancelOnce(id, clock.millis() + CANCEL_BUDGET_MILLIS, submissionAuthorization) }
            }
        }
    }

    private suspend fun authorization(deadline: Long, measureCredential: Boolean): String {
        if (measureCredential) return timing.measure(DriverPhase.CREDENTIAL) { authorization(deadline, false) }
        if ("access_token" in credential.fieldNames()) return credential.useSecret("access_token") { "Bearer ${String(it)}" }
        if (token == null || clock.millis() >= tokenExpiresAt) {
            val basic = credential.useSecret("oauth_client_id") { id ->
                credential.useSecret("oauth_client_secret") { secret ->
                    val bytes = "${String(id)}:${String(secret)}".toByteArray(StandardCharsets.UTF_8)
                    try { "Basic ${Base64.getEncoder().encodeToString(bytes)}" } finally { bytes.fill(0) }
                }
            }
            val request = HttpRequest.newBuilder(URI("https://$host/oidc/v1/token"))
                .timeout(callDuration(deadline)).header("Authorization", basic)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("grant_type=client_credentials&scope=all-apis")).build()
            val response = send(request, false, deadline)
            val document = readResponseDocument(response, deadline, false)
            if (response.statusCode() !in 200..299) throw httpFailure(response.statusCode(), document)
            val receivedToken = document!!.path("access_token").takeIf(JsonNode::isTextual)?.asText()
            val expires = document.path("expires_in").asLong(0)
            if (receivedToken.isNullOrBlank() || expires <= 0) throw protocolFailure("Databricks OAuth response is invalid")
            token = receivedToken
            tokenExpiresAt = clock.millis() + (expires * 1_000).coerceAtMost(Long.MAX_VALUE - clock.millis())
        }
        return "Bearer ${requireNotNull(token)}"
    }

    private suspend fun json(method: String, path: String, body: String?, deadline: Long, isSubmission: Boolean, allowEmpty: Boolean = false, measureCredential: Boolean = true): JsonNode {
        val request = HttpRequest.newBuilder(URI("https://$host$path")).timeout(callDuration(deadline))
            .header("Authorization", authorization(deadline, measureCredential)).header("Content-Type", "application/json")
            .method(method, body?.let(HttpRequest.BodyPublishers::ofString) ?: HttpRequest.BodyPublishers.noBody()).build()
        if (isSubmission) {
            return suspendCancellableCoroutine { continuation ->
                val bodyLock = Any()
                var responseBody: InputStream? = null
                var closeBody: Job? = null
                fun scheduleBodyClose(timeoutMillis: Long) {
                    closeBody?.cancel()
                    val activeBody = responseBody ?: return
                    closeBody = CoroutineScope(dispatcher).launch {
                        delay(timeoutMillis.coerceAtLeast(1))
                        runCatching { activeBody.close() }
                    }
                }
                val future = httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream())
                future.whenComplete { response, failure ->
                    dispatcher.dispatch(continuation.context, Runnable {
                        var document: JsonNode? = null
                        try {
                            if (failure != null) throw transportFailure(failure, deadline)
                            val readDeadline = synchronized(bodyLock) {
                                responseBody = response.body()
                                val readDeadline = if (abandoned.get()) clock.millis() + CANCEL_BUDGET_MILLIS else deadline
                                scheduleBodyClose(readDeadline - clock.millis())
                                readDeadline
                            }
                            document = try {
                                response.body().use { mapper.readTree(boundedInput(it, readDeadline, MAXIMUM_JSON_BYTES)) }
                            } finally {
                                synchronized(bodyLock) {
                                    responseBody = null
                                    closeBody?.cancel()
                                    closeBody = null
                                }
                            }
                            registerId(document ?: mapper.createObjectNode(), request.headers().firstValue("Authorization").orElse(""))
                            if (response.statusCode() !in 200..299) throw httpFailure(response.statusCode(), document)
                            if (continuation.isActive) continuation.resume(document ?: throw protocolFailure("Databricks response is missing"))
                        } catch (failure: Throwable) {
                            if (continuation.isActive) continuation.resumeWithException(
                                if (clock.millis() >= deadline) DatabricksDeadlineExceeded()
                                else if (response != null && response.statusCode() !in 200..299) httpFailure(response.statusCode(), document)
                                else sanitize(failure, deadline)
                            )
                        }
                    })
                }
                continuation.invokeOnCancellation {
                    abandoned.set(true)
                    synchronized(bodyLock) { scheduleBodyClose(CANCEL_BUDGET_MILLIS) }
                }
            }
        }
        val response = send(request, false, deadline)
        val document = readResponseDocument(response, deadline, allowEmpty)
        if (response.statusCode() !in 200..299) throw httpFailure(response.statusCode(), document)
        return document ?: throw protocolFailure("Databricks response is missing")
    }

    private suspend fun readResponseDocument(response: HttpResponse<InputStream>, deadline: Long, allowEmpty: Boolean): JsonNode? = try {
        readJsonBody(response.body(), deadline, allowEmpty || response.statusCode() !in 200..299)
    } catch (failure: CancellationException) { throw failure }
    catch (failure: DatabricksDeadlineExceeded) { throw failure }
    catch (failure: Throwable) {
        if (response.statusCode() !in 200..299) throw httpFailure(response.statusCode(), null)
        throw failure
    }

    private suspend fun readJsonBody(body: InputStream, deadline: Long, allowEmpty: Boolean): JsonNode? = coroutineScope {
        val closeOnCancellation = launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { body.close() }
        }
        try {
            withTimeout(callDuration(deadline).toMillis()) {
                runInterruptible(dispatcher) {
                    val document = mapper.readTree(boundedInput(body, deadline, MAXIMUM_JSON_BYTES))
                    document ?: if (allowEmpty) mapper.createObjectNode() else throw protocolFailure("Databricks response is missing")
                }
            }
        } catch (failure: TimeoutCancellationException) {
            if (clock.millis() >= deadline) throw DatabricksDeadlineExceeded()
            throw DatabricksFailure("TIMEOUT", "Databricks HTTP response timed out")
        } catch (failure: CancellationException) { throw failure }
        catch (failure: Throwable) { throw sanitize(failure, deadline) }
        finally { closeOnCancellation.cancel(); body.close() }
    }

    private suspend fun send(request: HttpRequest, isSubmission: Boolean, deadline: Long): HttpResponse<InputStream> =
        suspendCancellableCoroutine { continuation ->
            val future = httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream())
            future.whenComplete { response, failure ->
                if (continuation.isActive) {
                    if (failure != null) continuation.resumeWithException(transportFailure(failure, deadline))
                    else continuation.resume(response) { _, value, _ -> value.body().close() }
                } else response?.body()?.close()
            }
            continuation.invokeOnCancellation { if (!isSubmission) future.cancel(true) }
        }

    fun diagnostic(failure: DatabricksFailure, parameters: List<QueryParameter>): String {
        val fallback = requireNotNull(failure.message)
        val raw = failure.vendorMessage ?: return fallback
        if (token?.takeIf(String::isNotEmpty)?.let(raw::contains) == true) return fallback
        if (resultHeaderValues.any(raw::contains)) return fallback
        val diagnostic = warehouseDiagnostic(raw, credential, fallback)
        val values = parameters.mapNotNull { parameter -> when (parameter) {
            is QueryParameter.Text -> parameter.value
            is QueryParameter.Integer64 -> parameter.value.toString()
            is QueryParameter.Float64 -> parameter.value.toString()
            is QueryParameter.BooleanValue -> parameter.value.toString()
            is QueryParameter.Decimal -> parameter.value
            is QueryParameter.Date -> parameter.value.toString()
            is QueryParameter.Timestamp -> parameter.value.toString()
            else -> null
        } }.filter(String::isNotEmpty)
        return if (values.any(diagnostic::contains)) fallback else diagnostic
    }

    fun remainingMillis(deadline: Long): Long = (deadline - clock.millis()).also { if (it <= 0) throw DatabricksDeadlineExceeded() }
    private fun callDuration(deadline: Long): Duration = Duration.ofMillis(minOf(maximumCallDuration.toMillis(), remainingMillis(deadline)))
    private fun statementPath(id: String): String {
        require(STATEMENT_ID.matches(id))
        return "/api/2.0/sql/statements/$id"
    }
    private fun transportFailure(failure: Throwable, deadline: Long): RuntimeException {
        if (clock.millis() >= deadline) return DatabricksDeadlineExceeded()
        val cause = generateSequence(failure) { it.cause }.last()
        return DatabricksFailure(if (cause is HttpTimeoutException && cause !is java.net.http.HttpConnectTimeoutException) "TIMEOUT" else "CONNECTION_FAILED", "Databricks HTTP request failed")
    }
    private fun sanitize(failure: Throwable, deadline: Long): RuntimeException = when (failure) {
        is DatabricksFailure -> failure
        is DatabricksDeadlineExceeded -> failure
        else -> if (clock.millis() >= deadline) DatabricksDeadlineExceeded() else protocolFailure("Databricks response could not be read")
    }

    companion object {
        private val mapper = ObjectMapper()
        private const val MAXIMUM_JSON_BYTES = 4L * 1_024 * 1_024
        private const val CANCEL_BUDGET_MILLIS = 5_000L
        private val STATEMENT_ID = Regex("[A-Za-z0-9_-]{1,128}")
        private val HTTP_HEADER_NAME = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")
        private val FORBIDDEN_RESULT_HEADERS = setOf("authorization", "proxy-authorization", "cookie", "connection", "content-length", "expect", "host", "upgrade")
    }
}

internal fun statementFailure(status: JsonNode): DatabricksFailure {
    val state = status.path("sql_state").asText("")
    val code = status.path("error").path("error_code").asText("")
    return DatabricksFailure(classifiedCode(0, code, state), "Databricks statement execution failed", status.path("error").path("message").takeIf(JsonNode::isTextual)?.asText())
}

internal fun httpFailure(status: Int, response: JsonNode?): DatabricksFailure = DatabricksFailure(
    classifiedCode(status, response?.path("error_code")?.asText("").orEmpty(), response?.path("sql_state")?.asText("").orEmpty()),
    "Databricks HTTP request was rejected",
    response?.path("message")?.takeIf(JsonNode::isTextual)?.asText(),
)

private fun classifiedCode(httpStatus: Int, code: String, state: String): String = when {
    httpStatus == 401 || code == "UNAUTHENTICATED" || state.startsWith("28") -> "AUTHENTICATION_FAILED"
    httpStatus == 403 || code == "PERMISSION_DENIED" || state == "42501" -> "PERMISSION_DENIED"
    httpStatus == 429 || code == "RESOURCE_EXHAUSTED" || state.startsWith("53") -> "RESOURCE_EXHAUSTED"
    state.startsWith("08") -> "CONNECTION_FAILED"
    code == "DEADLINE_EXCEEDED" || state == "HYT00" || state == "HYT01" -> "TIMEOUT"
    state == "57014" || code == "CANCELED" || code == "CANCELLED" -> "CANCELLED"
    state == "42601" || state == "42000" || code == "PARSE_SYNTAX_ERROR" -> "SYNTAX_ERROR"
    state == "42S02" || state == "42S22" || state == "42703" || state == "42P01" || code == "TABLE_OR_VIEW_NOT_FOUND" || code == "UNRESOLVED_COLUMN" -> "OBJECT_NOT_FOUND"
    else -> "DATABRICKS_EXECUTION_FAILED"
}

internal fun protocolFailure(message: String) = DatabricksFailure("DATABRICKS_EXECUTION_FAILED", message)
private fun ConnectorRuntimeBinding.databricksText(name: String, required: Boolean): String? = when (val value = properties[name]) {
    null -> { require(!required) { "Databricks connector property is unavailable" }; null }
    is ConnectorPropertyValue.Text -> value.value.also { require(it.isNotBlank() && it.none(Char::isISOControl)) { "Databricks connector property is invalid" } }
    else -> throw IllegalArgumentException("Databricks connector property must be text")
}
private val DATABRICKS_HOST = Regex("^(?=.{1,253}$)(?![Aa][Cc][Cc][Oo][Uu][Nn][Tt][Ss]\\.)(?![Pp][Rr][Ii][Vv][Aa][Tt][Ee][Ll][Ii][Nn][Kk]\\.)(?:[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?\\.[Cc][Ll][Oo][Uu][Dd]\\.[Dd][Aa][Tt][Aa][Bb][Rr][Ii][Cc][Kk][Ss]\\.[Cc][Oo][Mm]|[Aa][Dd][Bb]-[0-9]{1,59}\\.[0-9]{1,63}\\.[Aa][Zz][Uu][Rr][Ee][Dd][Aa][Tt][Aa][Bb][Rr][Ii][Cc][Kk][Ss]\\.[Nn][Ee][Tt]|[0-9]{1,63}\\.[0-9]{1,63}\\.[Gg][Cc][Pp]\\.[Dd][Aa][Tt][Aa][Bb][Rr][Ii][Cc][Kk][Ss]\\.[Cc][Oo][Mm])(?![\\s\\S])")
