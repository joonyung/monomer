package cc.monomer.execution.driver.trino

import cc.monomer.execution.driver.jdbc.DirectExecutor
import cc.monomer.execution.driver.jdbc.rawSqlValidationFailureCode
import cc.monomer.execution.driver.jdbc.validateRawSqlOnBlockingConnection

import cc.monomer.execution.arrow.JdbcArrowEncoder
import cc.monomer.execution.arrow.JdbcDecimalPhysicalEncoding
import cc.monomer.execution.driver.jdbc.JdbcConnectorDialect
import cc.monomer.execution.driver.jdbc.JdbcConnectionFactory
import cc.monomer.execution.driver.jdbc.JdbcExecutionDriverSession
import cc.monomer.execution.driver.jdbc.JdbcRunTimeoutBudget
import cc.monomer.execution.driver.jdbc.JdbcRunTimeoutPolicy
import cc.monomer.execution.driver.spi.ConnectorPropertyValue
import cc.monomer.execution.driver.spi.ConnectorRuntimeBinding
import cc.monomer.execution.driver.spi.ConnectorType
import cc.monomer.execution.driver.spi.RawSqlValidationException
import cc.monomer.execution.driver.spi.DriverCapabilityDescriptor
import cc.monomer.execution.driver.spi.DriverOperationTiming
import cc.monomer.execution.driver.spi.DriverPhase
import cc.monomer.execution.driver.spi.ExecutionDriverProvider
import cc.monomer.execution.driver.spi.MaterializedCredential
import cc.monomer.execution.driver.spi.QueryParameter
import cc.monomer.execution.driver.spi.ResultProducingRunDriverSession
import cc.monomer.execution.driver.spi.RunResultSink
import java.nio.file.Path
import java.sql.Connection
import java.sql.Statement
import java.time.Clock
import java.util.Properties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher

class TrinoExecutionDriverProvider(
    private val maximumQuerySeconds: Int,
    private val arrowBatchSize: Int,
    private val warehouseTrustCertificatePath: Path,
    private val timeoutPolicy: JdbcRunTimeoutPolicy,
    private val blockingDispatcher: CoroutineDispatcher,
    private val clock: Clock,
    private val connectionFactory: JdbcConnectionFactory,
) : ExecutionDriverProvider {
    override val capability =
        DriverCapabilityDescriptor(connectorType = ConnectorType.TRINO)

    override fun openSession(
        connector: ConnectorRuntimeBinding,
        credential: MaterializedCredential?,
        resultSink: RunResultSink,
        timing: DriverOperationTiming,
    ): ResultProducingRunDriverSession {
        val materializedCredential = timing.measure(DriverPhase.CREDENTIAL) {
            val materializedCredential = requireNotNull(credential) { "Trino credential materialization is missing" }
            require(materializedCredential.fieldNames() == setOf("username", "password")) {
                "Trino credential fields do not match the driver contract"
            }
            materializedCredential
        }
        return JdbcExecutionDriverSession(
            connectorType = ConnectorType.TRINO,
            dialect = TrinoDialect(warehouseTrustCertificatePath),
            connector = connector,
            credential = materializedCredential,
            resultSink = resultSink,
            arrowEncoder =
                JdbcArrowEncoder(
                    batchSize = arrowBatchSize,
                    decimalPhysicalEncoding = JdbcDecimalPhysicalEncoding.NATIVE_DECIMAL,
                    valueReader = TrinoJdbcResultValueReader,
                ),
            maximumQuerySeconds = maximumQuerySeconds,
            timeoutPolicy = timeoutPolicy,
            blockingDispatcher = blockingDispatcher,
            clock = clock,
            connectionFactory = connectionFactory,
            timing = timing,
        )
    }

    override suspend fun validateRawSql(
        connector: ConnectorRuntimeBinding,
        credential: MaterializedCredential?,
        sql: String,
        parameters: List<QueryParameter>,
        deadlineEpochMillis: Long,
        timing: DriverOperationTiming,
    ): Unit {
        timing.measure(DriverPhase.PREPARE) {
            cc.monomer.execution.driver.spi.requireQueryParameterNames(connector.connectorType, parameters)
        }
        val materializedCredential = timing.measure(DriverPhase.CREDENTIAL) {
            val materializedCredential =
                requireNotNull(credential) { "Trino credential materialization is missing" }
            require(materializedCredential.fieldNames() == setOf("username", "password")) {
                "Trino credential fields do not match the driver contract"
            }
            materializedCredential
        }
        val (initialRemainingMillis, budget) = timing.measure(DriverPhase.PREPARE) {
            val initialRemainingMillis = deadlineEpochMillis - clock.millis()
            if (initialRemainingMillis < 1_000) {
                throw trinoValidationDeadlineExceeded(null)
            }
            val measuredBudget = timeoutPolicy.timeoutBudget(initialRemainingMillis, maximumQuerySeconds)
            initialRemainingMillis to measuredBudget
        }
        val dialect = TrinoDialect(warehouseTrustCertificatePath)
        val properties = timing.measure(DriverPhase.CREDENTIAL) {
            dialect.connectionProperties(connector, materializedCredential, budget)
        }
        validateRawSqlOnBlockingConnection(
            remainingMillis = initialRemainingMillis,
            blockingDispatcher = blockingDispatcher,
            timing = timing,
            beforeConnect = { timing.measure(DriverPhase.VALIDATE) { TrinoRawSqlValidator().validate(sql) } },
            connect = {
                timing.measure(DriverPhase.CONNECT) {
                    connectionFactory.open(dialect.jdbcUrl(connector), properties)
                }
            },
            validate = { connection ->
                timing.measure(DriverPhase.SESSION_SETUP) {
                    connection.isReadOnly = true
                    dialect.prepareConnection(connection, connector, budget)
                }
                timing.measure(DriverPhase.VALIDATE) {
                    TrinoRawSqlJdbcValidator(
                        connection = connection,
                        statementTimeoutSeconds = {
                            val remainingMillis = deadlineEpochMillis - clock.millis()
                            if (remainingMillis < 1_000) {
                                throw TrinoRawSqlValidationDeadlineExceededException()
                            }
                            val currentBudget = timeoutPolicy.timeoutBudget(remainingMillis, maximumQuerySeconds)
                            timing.measure(DriverPhase.SESSION_SETUP) {
                                dialect.refreshTimeouts(connection, currentBudget)
                            }
                            currentBudget.statementTimeoutSeconds
                        },
                    ).validate(sql, parameters)
                }
            },
            releaseConnectionProperties = properties::clear,
            classifyFailure = { failure ->
                if (failure is TrinoRawSqlValidationDeadlineExceededException) {
                    trinoValidationDeadlineExceeded(failure)
                } else {
                    RawSqlValidationException(
                        failureCode = dialect.rawSqlValidationFailureCode(failure),
                        safeMessage = "Trino Raw SQL validation failed",
                        cause = failure,
                    )
                }
            },
            deadlineExceeded = ::trinoValidationDeadlineExceeded,
        )
    }
}

private fun trinoValidationDeadlineExceeded(cause: Throwable?): RawSqlValidationException =
    RawSqlValidationException(
        failureCode = "RAW_SQL_VALIDATION_DEADLINE_EXCEEDED",
        safeMessage = "Trino Raw SQL validation deadline was exceeded",
        cause = cause,
    )

private class TrinoRawSqlValidationDeadlineExceededException : RuntimeException()

private val trinoHostPattern =
    Regex("(?:[A-Za-z0-9](?:[A-Za-z0-9.-]{0,251}[A-Za-z0-9])?|\\[[0-9A-Fa-f:]+\\])")
private val trinoIdentifierPattern = Regex("[A-Za-z_][A-Za-z0-9_$]{0,127}")

internal class TrinoDialect(
    private val warehouseTrustCertificatePath: Path,
) : JdbcConnectorDialect {
    override val serializeConnectionLifecycle = false

    override fun jdbcUrl(connector: ConnectorRuntimeBinding): String {
        val host = connector.requiredText("host")
        require(host.matches(trinoHostPattern) && ".." !in host)
        val port = connector.requiredInteger("port")
        require(port in 1..65535)
        val catalog = connector.requiredText("catalog")
        require(catalog.matches(trinoIdentifierPattern))
        val parameters =
            buildList {
                connector.optionalText("schema")?.takeIf(String::isNotBlank)?.let { schema ->
                    require(schema.matches(trinoIdentifierPattern))
                    add("schema=$schema")
                }
                connector.optionalText("ssl")?.let { ssl -> add("SSL=${ssl.toTrinoBoolean()}") }
                connector.optionalText("ssl_verification")?.let { verification ->
                    add("SSLVerification=${verification.toTrinoSslVerification()}")
                }
            }
        val query = parameters.takeIf(List<String>::isNotEmpty)?.joinToString("&", prefix = "?") ?: ""
        return "jdbc:trino://$host:$port/$catalog$query"
    }

    override fun connectionProperties(
        connector: ConnectorRuntimeBinding,
        credential: MaterializedCredential,
        timeoutBudget: JdbcRunTimeoutBudget,
    ): Properties =
        Properties().apply {
            credential.useSecret("username") { username -> setProperty("user", String(username)) }
            credential.useSecret("password") { password -> setProperty("password", String(password)) }
            setProperty("applicationNamePrefix", "monomer-execution")
            setProperty("timeout", "${timeoutBudget.socketTimeoutMillis}ms")
            val sslEnabled = connector.optionalText("ssl")?.toTrinoBoolean() == "true"
            val sslVerification = connector.optionalText("ssl_verification")?.toTrinoSslVerification()
            if (sslEnabled && sslVerification in setOf("CA", "FULL")) {
                setProperty("SSLTrustStorePath", warehouseTrustCertificatePath.toString())
            }
        }

    override fun prepareConnection(connection: Connection, connector: ConnectorRuntimeBinding, timeoutBudget: JdbcRunTimeoutBudget) {
        refreshTimeouts(connection, timeoutBudget)
    }

    override fun refreshTimeouts(connection: Connection, timeoutBudget: JdbcRunTimeoutBudget) {
        connection.setNetworkTimeout(DirectExecutor, timeoutBudget.socketTimeoutMillis.toInt())
    }

    override fun bindQueryParameters(statement: java.sql.PreparedStatement, parameters: List<QueryParameter>) {
        statement.bindTrinoQueryParameters(parameters)
    }

    override fun cancelRunningStatement(statement: Statement) = statement.cancel()

    override fun safeFailureCode(failure: Throwable): String {
        if (failure is CancellationException) return "CANCELLED"
        val sqlState = (failure as? java.sql.SQLException)?.sqlState
        val lowerMessage = failure.message.orEmpty().lowercase()
        return when {
            sqlState?.startsWith("28") == true -> "AUTHENTICATION_FAILED"
            "authentication failed" in lowerMessage || "unauthorized" in lowerMessage -> "AUTHENTICATION_FAILED"
            sqlState?.startsWith("08") == true -> "CONNECTION_FAILED"
            "mismatched input" in lowerMessage || "syntax error" in lowerMessage -> "SYNTAX_ERROR"
            "does not exist" in lowerMessage || "not found" in lowerMessage -> "OBJECT_NOT_FOUND"
            "access denied" in lowerMessage || "permission denied" in lowerMessage -> "PERMISSION_DENIED"
            "query was canceled" in lowerMessage || "query was killed" in lowerMessage -> "CANCELLED"
            "timeout" in lowerMessage -> "TIMEOUT"
            "connection refused" in lowerMessage || "connection reset" in lowerMessage ||
                "could not connect" in lowerMessage || "io error" in lowerMessage -> "CONNECTION_FAILED"
            else -> "TRINO_EXECUTION_FAILED"
        }
    }

}

private fun ConnectorRuntimeBinding.requiredText(name: String): String =
    requireNotNull(optionalText(name)) { "connector property is unavailable" }.also(::requireNotBlank)

private fun ConnectorRuntimeBinding.optionalText(name: String): String? =
    when (val value = properties[name]) {
        null -> null
        is ConnectorPropertyValue.Text -> value.value
        else -> throw IllegalArgumentException("connector property must be text")
    }

private fun ConnectorRuntimeBinding.requiredInteger(name: String): Long =
    when (val value = properties[name]) {
        is ConnectorPropertyValue.Integer -> value.value.also { require(it in 1..65535) }
        else -> throw IllegalArgumentException("connector property must be an integer")
    }

private fun String.toTrinoBoolean(): String =
    lowercase().also { require(it == "true" || it == "false") { "Trino ssl must be true or false" } }

private fun String.toTrinoSslVerification(): String =
    uppercase().also { require(it in setOf("FULL", "CA", "NONE")) { "Trino ssl_verification is unsupported" } }

private fun requireNotBlank(value: String): String {
    require(value.isNotBlank()) { "connector property must not be blank" }
    return value
}
