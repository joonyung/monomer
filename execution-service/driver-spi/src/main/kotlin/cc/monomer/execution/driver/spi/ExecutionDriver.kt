package cc.monomer.execution.driver.spi

import java.util.concurrent.atomic.AtomicBoolean

data class DriverCapabilityDescriptor(
    val connectorType: ConnectorType,
)

sealed interface DriverPhysicalResult {
    data class Succeeded(
        val rowsProcessed: ULong,
        val bytesProcessed: ULong,
        val manifest: ArrowResultManifest?,
    ) : DriverPhysicalResult

    data class Failed(
        val failureCode: String,
        val safeMessage: String,
    ) : DriverPhysicalResult {
        init {
            require(failureCode.isNotBlank())
            require(safeMessage.isNotBlank())
        }
    }

    data object Cancelled : DriverPhysicalResult
}

data class ArrowResultManifest(
    val objectKey: String,
    val byteSize: ULong,
    val objectEtag: String,
    val rowCount: ULong,
    val columnCount: Int,
    val mediaType: String,
) {
    init {
        require(objectKey.isNotBlank())
        require(byteSize > 0uL)
        require(objectEtag.isNotBlank())
        require(columnCount > 0)
        require(mediaType == ARROW_FILE_MEDIA_TYPE)
    }

    companion object {
        const val ARROW_FILE_MEDIA_TYPE = "application/vnd.apache.arrow.file"
    }
}

interface RunResultSink {
    suspend fun emitInlineResult(batch: InlineResultBatch)

    suspend fun openObjectUpload(
        objectKey: String,
        mediaType: String,
    ): ArtifactUploadSink
}

interface ArtifactUploadSink {
    suspend fun append(payload: BinaryValue)

    suspend fun complete(
        rowCount: ULong,
        columnCount: Int,
    ): ArrowResultManifest

    suspend fun abort()
}

interface ResultProducingRunDriverSession : RunDriverSession {
    suspend fun awaitPhysicalResult(): DriverPhysicalResult
}
interface ExecutionDriverProvider {
    val capability: DriverCapabilityDescriptor

    fun openSession(
        connector: ConnectorRuntimeBinding,
        credential: MaterializedCredential?,
        resultSink: RunResultSink,
        timing: DriverOperationTiming,
    ): ResultProducingRunDriverSession

    suspend fun validateRawSql(
        connector: ConnectorRuntimeBinding,
        credential: MaterializedCredential?,
        sql: String,
        parameters: List<QueryParameter>,
        deadlineEpochMillis: Long,
        timing: DriverOperationTiming,
    ): Unit
}

class ExecutionDriverRegistry(
    providers: Iterable<ExecutionDriverProvider>,
) : AutoCloseable {
    private val providerList = providers.toList()
    private val providersByConnectorType =
        providerList.associateBy(ExecutionDriverProvider::capability)
            .mapKeys { (capability, _) -> capability.connectorType }

    init {
        require(providerList.isNotEmpty())
        require(providersByConnectorType.size == providerList.size) {
            "exactly one execution driver must own each connector type"
        }
    }

    override fun close() {
        providerList.filterIsInstance<AutoCloseable>().forEach(AutoCloseable::close)
    }

    fun openSession(
        connector: ConnectorRuntimeBinding,
        credential: MaterializedCredential?,
        resultSink: RunResultSink,
        timing: DriverOperationTiming,
    ): ResultProducingRunDriverSession {
        val provider =
            requireNotNull(providersByConnectorType[connector.connectorType]) {
                "connector type ${connector.connectorType} has no execution driver"
            }
        return provider.openSession(connector, credential, resultSink, timing)
    }

    suspend fun validateRawSql(
        connector: ConnectorRuntimeBinding,
        credential: MaterializedCredential?,
        sql: String,
        parameters: List<QueryParameter>,
        deadlineEpochMillis: Long,
        timing: DriverOperationTiming,
    ): Unit {
        val provider =
            requireNotNull(providersByConnectorType[connector.connectorType]) {
                "connector type ${connector.connectorType} has no execution driver"
            }
        return provider.validateRawSql(
            connector = connector,
            credential = credential,
            sql = sql,
            parameters = parameters,
            deadlineEpochMillis = deadlineEpochMillis,
            timing = timing,
        )
    }
}

class SecretCharacters private constructor(
    source: CharArray,
) : AutoCloseable {
    private val content = source.copyOf()
    private val closed = AtomicBoolean(false)

    val size: Int
        get() = content.size

    fun <T> useCopy(block: (CharArray) -> T): T {
        check(!closed.get()) { "secret is closed" }
        val copy = content.copyOf()
        return try {
            block(copy)
        } finally {
            copy.fill('\u0000')
        }
    }

    fun isZeroized(): Boolean = content.all { it == '\u0000' }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            content.fill('\u0000')
        }
    }

    override fun toString(): String = "SecretCharacters(size=$size)"

    companion object {
        fun copyOf(source: CharArray): SecretCharacters = SecretCharacters(source)
    }
}

class MaterializedCredential(
    entries: Map<String, SecretCharacters>,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val secretsByName = entries.toMap()

    init {
        require(secretsByName.keys.none(String::isBlank))
    }

    fun <T> useSecret(
        name: String,
        block: (CharArray) -> T,
    ): T {
        check(!closed.get()) { "credential is closed" }
        return requireNotNull(secretsByName[name]) {
            "credential field is unavailable"
        }.useCopy(block)
    }

    fun optionalSecret(name: String): SecretCharacters? {
        check(!closed.get()) { "credential is closed" }
        return secretsByName[name]
    }

    fun fieldNames(): Set<String> = secretsByName.keys

    fun isZeroized(): Boolean = secretsByName.values.all(SecretCharacters::isZeroized)

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            secretsByName.values.forEach(SecretCharacters::close)
        }
    }

    override fun toString(): String =
        "MaterializedCredential(fields=${secretsByName.keys.sorted()})"
}
