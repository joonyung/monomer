package cc.monomer.execution.driver.databricks

import cc.monomer.execution.driver.spi.*
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.Dispatchers
import org.apache.arrow.memory.RootAllocator
import org.apache.arrow.vector.*
import org.apache.arrow.vector.ipc.ArrowStreamWriter
import org.apache.arrow.vector.types.pojo.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.Authenticator
import java.net.CookieHandler
import java.net.ProxySelector
import java.net.URI
import java.net.http.*
import java.nio.channels.Channels
import java.time.Clock
import java.time.Duration
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSession

internal val jsonMapper = ObjectMapper()
internal class RecordedHttpClient(private val handler: (HttpRequest) -> CompletableFuture<HttpResponse<InputStream>>) : HttpClient() {
    val requests = CopyOnWriteArrayList<HttpRequest>()
    override fun cookieHandler(): Optional<CookieHandler> = Optional.empty()
    override fun connectTimeout(): Optional<Duration> = Optional.of(Duration.ofSeconds(3))
    override fun followRedirects(): Redirect = Redirect.NEVER
    override fun proxy(): Optional<ProxySelector> = Optional.empty()
    override fun sslContext(): SSLContext = SSLContext.getDefault()
    override fun sslParameters(): SSLParameters = SSLParameters()
    override fun authenticator(): Optional<Authenticator> = Optional.empty()
    override fun version(): Version = Version.HTTP_2
    override fun executor(): Optional<Executor> = Optional.empty()
    override fun <T> send(request: HttpRequest, handler: HttpResponse.BodyHandler<T>): HttpResponse<T> = error("blocking HTTP is not used")
    @Suppress("UNCHECKED_CAST")
    override fun <T> sendAsync(request: HttpRequest, handler: HttpResponse.BodyHandler<T>): CompletableFuture<HttpResponse<T>> {
        requests.add(request)
        return this.handler(request) as CompletableFuture<HttpResponse<T>>
    }
    override fun <T> sendAsync(request: HttpRequest, handler: HttpResponse.BodyHandler<T>, push: HttpResponse.PushPromiseHandler<T>): CompletableFuture<HttpResponse<T>> = sendAsync(request, handler)
}
internal fun httpResponse(request: HttpRequest, status: Int, body: ByteArray): CompletableFuture<HttpResponse<InputStream>> =
    CompletableFuture.completedFuture(inputResponse(request, status, ByteArrayInputStream(body)))
internal fun inputResponse(request: HttpRequest, status: Int, stream: InputStream): HttpResponse<InputStream> = object : HttpResponse<InputStream> {
    override fun statusCode(): Int = status
    override fun request(): HttpRequest = request
    override fun previousResponse(): Optional<HttpResponse<InputStream>> = Optional.empty()
    override fun headers(): HttpHeaders = HttpHeaders.of(emptyMap()) { _, _ -> true }
    override fun body(): InputStream = stream
    override fun sslSession(): Optional<SSLSession> = Optional.empty()
    override fun uri(): URI = request.uri()
    override fun version(): HttpClient.Version = HttpClient.Version.HTTP_2
}
internal fun jsonResponse(request: HttpRequest, status: Int, value: JsonNode) = httpResponse(request, status, jsonMapper.writeValueAsBytes(value))
internal fun connector(host: String = "dbc-1234.cloud.databricks.com") = ConnectorRuntimeBinding(
    1u, ConnectorId.parse("019f0000-0000-7000-8000-000000000003"), ConnectorType.DATABRICKS,
    mapOf("host" to ConnectorPropertyValue.Text(host), "warehouse_id" to ConnectorPropertyValue.Text("abcd1234"), "catalog" to ConnectorPropertyValue.Text("main"), "schema" to ConnectorPropertyValue.Text("analytics")),
)
internal fun credential(vararg fields: Pair<String,String>) = MaterializedCredential(fields.associate { (name,value) -> name to SecretCharacters.copyOf(value.toCharArray()) })
internal fun provider(http: HttpClient, clock: Clock = Clock.systemUTC(), maximumSeconds: Int = 300) = DatabricksExecutionDriverProvider(
    maximumSeconds, 1_024, Duration.ofSeconds(3), Duration.ofMillis(1), Dispatchers.IO, clock, http,
)
internal fun context(
    target: QueryResultTarget = QueryResultTarget.InlineStream(2, 64 * 1_024, 16 * 1_024),
    parameters: List<QueryParameter> = emptyList(),
    expectedNames: List<String> = emptyList(),
    validate: Boolean = false,
    deadline: Long = System.currentTimeMillis() + 10_000,
): StartExecutionContext {
    val request = QueryRequest(1u, Sha256Digest("0".repeat(64)), "SELECT :value", parameters, target, validate, expectedNames)
    return StartExecutionContext(RunIdentity(RunId.parse("019f0000-0000-7000-8000-000000000001")), RunAssignment(
        WorkspaceId.parse("019f0000-0000-7000-8000-000000000002"), connector(),
        request.copy(canonicalRequestDigest = QueryRequestCanonicalizer.canonicalDigest(request)), deadline,
    ))
}
internal val artifactTarget = QueryResultTarget.ArtifactUpload("artifacts/test.arrow", 10uL, 1_024uL * 1_024uL, 2, 64 * 1_024, 16 * 1_024, 64 * 1_024)
internal fun integerSchema(name: String = "value") = Schema(listOf(Field(name, FieldType.nullable(ArrowType.Int(64, true)), emptyList())))
internal fun arrowStream(schema: Schema = integerSchema(), batches: List<List<List<Any?>>>): ByteArray = RootAllocator(128L * 1_024 * 1_024).use { allocator ->
    VectorSchemaRoot.create(schema, allocator).use { root ->
        val output = ByteArrayOutputStream()
        ArrowStreamWriter(root, null, Channels.newChannel(output)).use { writer ->
            writer.start()
            for (batch in batches) {
                root.clear(); root.allocateNew()
                batch.forEachIndexed { row, values -> values.forEachIndexed { column, value ->
                    val vector = root.fieldVectors[column]
                    if (value == null) vector.setNull(row) else when(vector) {
                        is BigIntVector -> vector.setSafe(row, value as Long)
                        is IntVector -> vector.setSafe(row, value as Int)
                        is TinyIntVector -> vector.setSafe(row, (value as Byte).toInt())
                        is SmallIntVector -> vector.setSafe(row, (value as Short).toInt())
                        is Float4Vector -> vector.setSafe(row, value as Float)
                        is Float8Vector -> vector.setSafe(row, value as Double)
                        is BitVector -> vector.setSafe(row, if(value as Boolean) 1 else 0)
                        is VarCharVector -> vector.setSafe(row, (value as String).toByteArray())
                        is VarBinaryVector -> vector.setSafe(row, value as ByteArray)
                        is DecimalVector -> vector.setSafe(row, value as java.math.BigDecimal)
                        is DateDayVector -> vector.setSafe(row, value as Int)
                        is TimeStampMicroTZVector -> vector.setSafe(row, value as Long)
                        is TimeStampMicroVector -> vector.setSafe(row, value as Long)
                        is TimeStampNanoVector -> vector.setSafe(row, value as Long)
                        else -> error("unsupported fixture vector")
                    }
                } }
                root.fieldVectors.forEach { it.valueCount = batch.size }; root.rowCount = batch.size
                writer.writeBatch()
            }
            writer.end()
        }
        output.toByteArray()
    }
}
internal fun integerStream(vararg batches: List<Long?>) = arrowStream(batches = batches.map { batch -> batch.map { listOf(it) } })
internal fun link(index: Int, offset: Long, count: Long, url: String = "https://storage.cloud.databricks.com/chunk/$index?synthetic-signed-secret", expiration: String = "2099-01-01T00:00:00Z", next: Int? = null): JsonNode =
    jsonMapper.createObjectNode().put("chunk_index",index).put("row_offset",offset).put("row_count",count).put("external_link",url).put("expiration",expiration).apply { next?.let { put("next_chunk_index", it) } }
internal fun snapshot(counts: List<Long> = listOf(1), links: List<JsonNode> = listOf(link(0,0,counts[0])), schema: Schema = integerSchema()): JsonNode {
    val response = jsonMapper.createObjectNode().put("statement_id","statement-1")
    response.putObject("status").put("state","SUCCEEDED")
    val manifest = response.putObject("manifest").put("format","ARROW_STREAM").put("total_chunk_count", counts.size).put("total_row_count", counts.sum()).put("truncated",false)
    val chunks = manifest.putArray("chunks")
    var offset = 0L
    counts.forEachIndexed { index,count -> chunks.addObject().put("chunk_index",index).put("row_offset",offset).put("row_count",count); offset += count }
    val columns = manifest.putObject("schema").put("column_count",schema.fields.size).putArray("columns")
    schema.fields.forEachIndexed { index,field ->
        val type = field.type
        val (name,text) = when(type) {
            is ArrowType.Int -> when (type.bitWidth) { 8 -> "BYTE" to "TINYINT"; 16 -> "SHORT" to "SMALLINT"; 32 -> "INT" to "INT"; else -> "LONG" to "BIGINT" }
            is ArrowType.FloatingPoint -> if (type.precision == org.apache.arrow.vector.types.FloatingPointPrecision.SINGLE) "FLOAT" to "FLOAT" else "DOUBLE" to "DOUBLE"
            is ArrowType.Bool -> "BOOLEAN" to "BOOLEAN"
            is ArrowType.Utf8 -> "STRING" to "STRING"
            is ArrowType.Binary -> "BINARY" to "BINARY"
            is ArrowType.Decimal -> "DECIMAL" to "DECIMAL(${type.precision},${type.scale})"
            is ArrowType.Date -> "DATE" to "DATE"
            is ArrowType.Timestamp -> if(type.timezone.isNullOrEmpty()) "TIMESTAMP_NTZ" to "TIMESTAMP_NTZ" else "TIMESTAMP" to "TIMESTAMP"
            else -> "ARRAY" to "ARRAY<INT>"
        }
        columns.addObject().put("position",index).put("name",field.name).put("type_name",name).put("type_text",text)
    }
    response.putObject("result").putArray("external_links").addAll(links)
    return response
}
internal class RecordingSink : RunResultSink, ArtifactUploadSink {
    val frames = mutableListOf<BinaryValue>()
    val parts = mutableListOf<BinaryValue>()
    var aborted = false
    var completed = false
    var opened = false
    var onEmit: suspend () -> Unit = {}
    var onAppend: suspend () -> Unit = {}
    override suspend fun emitInlineResult(batch: InlineResultBatch) { onEmit(); frames.add(batch.arrowIpc) }
    override suspend fun openObjectUpload(objectKey: String, mediaType: String): ArtifactUploadSink { opened=true; return this }
    override suspend fun append(payload: BinaryValue) { onAppend(); parts.add(payload) }
    override suspend fun complete(rowCount: ULong, columnCount: Int): ArrowResultManifest {
        completed = true
        return ArrowResultManifest("artifacts/test.arrow",parts.sumOf { it.size }.toULong(),"etag",rowCount,columnCount,ArrowResultManifest.ARROW_FILE_MEDIA_TYPE)
    }
    override suspend fun abort() { aborted=true }
}

internal fun operationTiming(operation: DriverOperation = DriverOperation.QUERY) = DriverOperationTiming(
    ConnectorType.DATABRICKS, operation, "019f0000-0000-7000-8000-000000000001", "019f0000-0000-7000-8000-000000000002",
)
