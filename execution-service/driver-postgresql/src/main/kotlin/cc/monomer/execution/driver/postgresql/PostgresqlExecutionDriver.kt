package cc.monomer.execution.driver.postgresql

import cc.monomer.execution.arrow.JdbcArrowEncoder
import cc.monomer.execution.arrow.JdbcDecimalPhysicalEncoding
import cc.monomer.execution.driver.jdbc.DirectExecutor
import cc.monomer.execution.driver.jdbc.JdbcConnectorDialect
import cc.monomer.execution.driver.jdbc.JdbcRunTimeoutBudget
import cc.monomer.execution.driver.jdbc.JdbcRunTimeoutPolicy
import cc.monomer.execution.driver.jdbc.JdbcExecutionDriverSession
import cc.monomer.execution.driver.jdbc.JdbcConnectionFactory
import cc.monomer.execution.driver.jdbc.bindQueryParameter
import cc.monomer.execution.driver.jdbc.validateRawSqlOnBlockingConnection
import cc.monomer.execution.driver.spi.RunResultSink
import cc.monomer.execution.driver.spi.ConnectorPropertyValue
import cc.monomer.execution.driver.spi.ConnectorRuntimeBinding
import cc.monomer.execution.driver.spi.ConnectorType
import cc.monomer.execution.driver.spi.DriverCapabilityDescriptor
import cc.monomer.execution.driver.spi.DriverOperationTiming
import cc.monomer.execution.driver.spi.DriverPhase
import cc.monomer.execution.driver.spi.ExecutionDriverProvider
import cc.monomer.execution.driver.spi.MaterializedCredential
import cc.monomer.execution.driver.spi.QueryParameter
import cc.monomer.execution.driver.spi.RawSqlValidationException
import cc.monomer.execution.driver.spi.ResultProducingRunDriverSession
import java.net.SocketTimeoutException
import java.sql.SQLException
import java.sql.SQLTimeoutException
import java.net.IDN
import java.nio.file.Path
import java.sql.Connection
import java.sql.Statement
import java.time.Clock
import java.util.Properties
import kotlinx.coroutines.CoroutineDispatcher
import org.postgresql.PGConnection

class PostgresqlExecutionDriverProvider(
    private val maximumQuerySeconds: Int,
    private val arrowBatchSize: Int,
    private val warehouseTrustCertificatePath: Path,
    private val timeoutPolicy: JdbcRunTimeoutPolicy,
    private val blockingDispatcher: CoroutineDispatcher,
    private val clock: Clock,
    private val connectionFactory: JdbcConnectionFactory,
) : ExecutionDriverProvider {
    override val capability =
        DriverCapabilityDescriptor(connectorType = ConnectorType.POSTGRESQL)

    override fun openSession(
        connector: ConnectorRuntimeBinding,
        credential: MaterializedCredential?,
        resultSink: RunResultSink,
        timing: DriverOperationTiming,
    ): ResultProducingRunDriverSession {
        val materializedCredential = timing.measure(DriverPhase.CREDENTIAL) {
            val materializedCredential = requireNotNull(credential) {
                "PostgreSQL credential materialization is missing"
            }
            require(materializedCredential.fieldNames() == setOf("username", "password")) {
                "PostgreSQL credential fields do not match the driver contract"
            }
            materializedCredential
        }
        return JdbcExecutionDriverSession(
            connectorType = ConnectorType.POSTGRESQL,
            dialect = PostgresqlDialect(warehouseTrustCertificatePath, arrowBatchSize),
            connector = connector,
            credential = materializedCredential,
            resultSink = resultSink,
            arrowEncoder =
                JdbcArrowEncoder(
                    batchSize = arrowBatchSize,
                    decimalPhysicalEncoding = JdbcDecimalPhysicalEncoding.UTF8_CANONICAL_DECIMAL,
                    valueReader = PostgresqlJdbcResultValueReader,
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
                requireNotNull(credential) { "PostgreSQL credential materialization is missing" }
            materializedCredential
        }
        val (remainingMillis, budget) = timing.measure(DriverPhase.PREPARE) {
            val remainingMillis = deadlineEpochMillis - clock.millis()
            if (remainingMillis < 1_000) {
                throw RawSqlValidationException(
                    "RAW_SQL_VALIDATION_DEADLINE_EXCEEDED", "PostgreSQL Raw SQL validation deadline was exceeded",
                    null,
                )
            }
            val measuredBudget = timeoutPolicy.timeoutBudget(remainingMillis, maximumQuerySeconds)
            remainingMillis to measuredBudget
        }
        val dialect = PostgresqlDialect(warehouseTrustCertificatePath, arrowBatchSize)
        val properties = timing.measure(DriverPhase.CREDENTIAL) {
            dialect.connectionProperties(connector, materializedCredential, budget)
        }
        validateRawSqlOnBlockingConnection(
            remainingMillis = remainingMillis,
            blockingDispatcher = blockingDispatcher,
            timing = timing,
            beforeConnect = {},
            connect = {
                timing.measure(DriverPhase.CONNECT) {
                    connectionFactory.open(dialect.jdbcUrl(connector), properties)
                }
            },
            validate = { connection ->
                timing.measure(DriverPhase.SESSION_SETUP) {
                    dialect.prepareConnection(connection, connector, budget)
                }
                timing.measure(DriverPhase.VALIDATE) {
                    PostgresqlRawSqlValidator(connection).validate(sql, parameters)
                }
            },
            releaseConnectionProperties = properties::clear,
            classifyFailure = { failure ->
                val failureCode = postgresqlFailureCode(failure, validating = true)
                val safeMessage = when (failureCode) {
                    "RAW_SQL_VALIDATION_DEADLINE_EXCEEDED" -> "PostgreSQL Raw SQL validation deadline was exceeded"
                    "CONNECTION_FAILED" -> "PostgreSQL connection failed during Raw SQL validation"
                    "PERMISSION_DENIED" -> "The Connector's warehouse account is not allowed to run this SQL."
                    "AUTHENTICATION_FAILED" -> "PostgreSQL authentication failed during Raw SQL validation"
                    "RAW_SQL_VALIDATION_REJECTED" -> "PostgreSQL Raw SQL validation was rejected"
                    else -> "PostgreSQL Raw SQL validation failed"
                }
                RawSqlValidationException(failureCode, safeMessage, failure)
            },
            deadlineExceeded = { failure ->
                RawSqlValidationException(
                    failureCode = "RAW_SQL_VALIDATION_DEADLINE_EXCEEDED",
                    safeMessage = "PostgreSQL Raw SQL validation deadline was exceeded",
                    cause = failure,
                )
            },
        )
    }
}

private val postgresqlHostPattern = Regex("[A-Za-z0-9.-]+|\\[[0-9A-Fa-f:]+\\]")
private val postgresqlSchemaPattern = Regex("[A-Za-z_][A-Za-z0-9_$]{0,127}")

internal class PostgresqlDialect(
    private val warehouseTrustCertificatePath: Path,
    private val rowFetchSize: Int,
) : JdbcConnectorDialect {
    init {
        require(rowFetchSize > 0)
    }

    override val serializeConnectionLifecycle = false

    override fun jdbcUrl(connector: ConnectorRuntimeBinding): String {
        val host = connector.requiredText("host")
        val asciiHost = IDN.toASCII(host)
        require(asciiHost.matches(postgresqlHostPattern))
        val port = connector.optionalInteger("port") ?: 5432L
        require(port in 1..65535)
        val database = connector.requiredText("database")
        require(database.matches(Regex("[A-Za-z0-9_.-]+")))
        return "jdbc:postgresql://$asciiHost:$port/$database"
    }

    override fun connectionProperties(
        connector: ConnectorRuntimeBinding,
        credential: MaterializedCredential,
        timeoutBudget: JdbcRunTimeoutBudget,
    ): Properties {
        val properties = Properties()
        credential.useSecret("username") { username ->
            properties.setProperty("user", String(username))
        }
        credential.useSecret("password") { password ->
            properties.setProperty("password", String(password))
        }
        properties.setProperty("ApplicationName", "monomer-execution")
        val sslMode = connector.optionalText("ssl_mode")
        if (sslMode != null) {
            val jdbcSslMode =
                when (sslMode) {
                    "disable" -> "disable"
                    "require" -> "require"
                    "verify_ca" -> "verify-ca"
                    "verify_full" -> "verify-full"
                    else -> throw IllegalArgumentException("PostgreSQL ssl_mode is unsupported")
                }
            properties.setProperty("sslmode", jdbcSslMode)
            if (jdbcSslMode == "verify-ca" || jdbcSslMode == "verify-full") {
                properties.setProperty(
                    "sslrootcert",
                    warehouseTrustCertificatePath.toString(),
                )
            }
        }
        properties.setProperty("readOnly", "true")
        properties.setProperty("defaultRowFetchSize", rowFetchSize.toString())
        properties.setProperty("connectTimeout", timeoutBudget.connectTimeoutSeconds.toString())
        properties.setProperty("loginTimeout", timeoutBudget.connectTimeoutSeconds.toString())
        properties.setProperty("socketTimeout", timeoutBudget.socketTimeoutSeconds.toString())
        return properties
    }

    override fun prepareConnection(
        connection: Connection,
        connector: ConnectorRuntimeBinding,
        timeoutBudget: JdbcRunTimeoutBudget,
    ) {
        connection.setNetworkTimeout(DirectExecutor, timeoutBudget.socketTimeoutMillis.toInt())
        connection.transactionIsolation = Connection.TRANSACTION_REPEATABLE_READ
        connection.createStatement().use { statement ->
            statement.queryTimeout = timeoutBudget.statementTimeoutSeconds
            statement.execute("SET SESSION CHARACTERISTICS AS TRANSACTION READ ONLY")
        }
        connection.autoCommit = false
        connection.createStatement().use { statement ->
            statement.queryTimeout = timeoutBudget.statementTimeoutSeconds
            statement.execute("SET TRANSACTION READ ONLY")
        }
        refreshTimeouts(connection, timeoutBudget)
        val schema = connector.optionalText("schema") ?: "public"
        require(schema.matches(postgresqlSchemaPattern))
        connection.prepareStatement(
            "SELECT set_config('search_path', ?, true)",
        ).use { statement ->
            statement.queryTimeout = timeoutBudget.statementTimeoutSeconds
            statement.setString(1, "pg_catalog,\"$schema\"")
            statement.executeQuery().use { result ->
                check(result.next())
            }
        }
    }

    override fun refreshTimeouts(
        connection: Connection,
        timeoutBudget: JdbcRunTimeoutBudget,
    ) {
        connection.setNetworkTimeout(DirectExecutor, timeoutBudget.socketTimeoutMillis.toInt())
        connection.prepareStatement(
            """
            SELECT set_config('statement_timeout', ?, true),
                   set_config('lock_timeout', ?, true),
                   set_config('idle_in_transaction_session_timeout', ?, true)
            """.trimIndent(),
        ).use { statement ->
            statement.queryTimeout = timeoutBudget.statementTimeoutSeconds
            statement.setString(1, timeoutBudget.statementTimeoutMillis.toString())
            statement.setString(2, timeoutBudget.lockTimeoutMillis.toString())
            statement.setString(3, timeoutBudget.statementTimeoutMillis.toString())
            statement.executeQuery().use { result ->
                check(result.next())
            }
        }
    }

    override fun bindQueryParameters(statement: java.sql.PreparedStatement, parameters: List<QueryParameter>) {
        parameters.forEachIndexed { index, parameter ->
            when {
                parameter is QueryParameter.Timestamp -> statement.setObject(index + 1, parameter.value.atOffset(java.time.ZoneOffset.UTC))
                parameter is QueryParameter.Date -> statement.setObject(index + 1, parameter.value)
                parameter is QueryParameter.Null && parameter.type == cc.monomer.execution.driver.spi.NullQueryParameterType.TIMESTAMP ->
                    statement.setNull(index + 1, java.sql.Types.TIMESTAMP_WITH_TIMEZONE)
                else -> statement.bindQueryParameter(index + 1, parameter)
            }
        }
    }

    // pgjdbc's Statement.cancel does nothing once execute() returns, so it cannot stop a later cursor fetch.
    override fun cancelRunningStatement(statement: Statement) =
        statement.connection.unwrap(PGConnection::class.java).cancelQuery()

    override fun safeFailureCode(failure: Throwable): String =
        postgresqlFailureCode(failure, validating = false)
}

private fun ConnectorRuntimeBinding.requiredText(name: String): String =
    requireNotNull(optionalText(name)) { "connector property is unavailable" }

private fun ConnectorRuntimeBinding.optionalText(name: String): String? =
    when (val value = properties[name]) {
        null -> null
        is ConnectorPropertyValue.Text -> value.value
        else -> throw IllegalArgumentException("connector property must be text")
    }

private fun ConnectorRuntimeBinding.optionalInteger(name: String): Long? =
    when (val value = properties[name]) {
        null -> null
        is ConnectorPropertyValue.Integer -> value.value
        else -> throw IllegalArgumentException("connector property must be an integer")
    }

internal fun postgresqlFailureCode(failure: Throwable, validating: Boolean): String {
    val causes = generateSequence(failure) { it.cause }.take(32).toList()
    val sqlStates = causes.filterIsInstance<SQLException>().mapNotNull { it.sqlState }
    return when {
        sqlStates.any { it.startsWith("08") } || causes.any { it is SocketTimeoutException } -> "CONNECTION_FAILED"
        sqlStates.any { it.startsWith("28") } -> "AUTHENTICATION_FAILED"
        "42501" in sqlStates -> "PERMISSION_DENIED"
        "25006" in sqlStates -> "READ_ONLY_VIOLATION"
        causes.any { it is SQLTimeoutException } || "57014" in sqlStates ->
            if (validating) "RAW_SQL_VALIDATION_DEADLINE_EXCEEDED" else "CANCELLED"
        sqlStates.any { it.startsWith("53") } -> "RESOURCE_EXHAUSTED"
        !validating && "42821" in sqlStates -> "UNSUPPORTED_RESULT_TYPE"
        validating && (failure is IllegalArgumentException || sqlStates.any { it.startsWith("42") }) ->
            "RAW_SQL_VALIDATION_REJECTED"
        validating -> "RAW_SQL_VALIDATION_FAILED"
        sqlStates.any { it.startsWith("42") } -> "SYNTAX_OR_OBJECT_ERROR"
        else -> "POSTGRESQL_EXECUTION_FAILED"
    }
}
