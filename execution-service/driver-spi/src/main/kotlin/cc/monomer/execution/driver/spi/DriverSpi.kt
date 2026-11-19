package cc.monomer.execution.driver.spi

data class StartExecutionContext(
    val run: RunIdentity,
    val assignment: RunAssignment,
)

class RawSqlValidationException(
    val failureCode: String,
    val safeMessage: String,
    cause: Throwable?,
) : RuntimeException(safeMessage, cause) {
    init {
        require(failureCode.isNotBlank())
        require(safeMessage.isNotBlank())
    }
}

enum class DriverCancellationResult {
    CONFIRMED,
    REQUESTED,
    UNSUPPORTED,
}

interface RunDriverSession {
    val connectorType: ConnectorType

    suspend fun enter(context: StartExecutionContext)

    suspend fun cancel(): DriverCancellationResult

    suspend fun close()
}
