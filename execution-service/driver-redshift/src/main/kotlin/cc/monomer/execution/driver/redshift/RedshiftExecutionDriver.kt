package cc.monomer.execution.driver.redshift

import cc.monomer.execution.driver.jdbc.DirectExecutor
import cc.monomer.execution.driver.jdbc.rawSqlValidationFailureCode
import cc.monomer.execution.driver.jdbc.validateRawSqlOnBlockingConnection

import cc.monomer.execution.arrow.JdbcArrowEncoder
import cc.monomer.execution.arrow.JdbcDecimalPhysicalEncoding
import cc.monomer.execution.driver.jdbc.JdbcConnectionFactory
import cc.monomer.execution.driver.jdbc.JdbcRunTimeoutBudget
import cc.monomer.execution.driver.jdbc.JdbcRunTimeoutPolicy
import cc.monomer.execution.driver.jdbc.JdbcConnectorDialect
import cc.monomer.execution.driver.jdbc.JdbcExecutionDriverSession
import cc.monomer.execution.driver.spi.RunResultSink
import cc.monomer.execution.driver.spi.ConnectorPropertyValue
import cc.monomer.execution.driver.spi.ConnectorRuntimeBinding
import cc.monomer.execution.driver.spi.ConnectorType
import cc.monomer.execution.driver.spi.DriverCapabilityDescriptor
import cc.monomer.execution.driver.spi.DriverOperationTiming
import cc.monomer.execution.driver.spi.DriverPhase
import cc.monomer.execution.driver.spi.ExecutionDriverProvider
import cc.monomer.execution.driver.spi.MaterializedCredential
import cc.monomer.execution.driver.spi.RawSqlValidationException
import cc.monomer.execution.driver.spi.QueryParameter
import cc.monomer.execution.driver.spi.ResultProducingRunDriverSession
import com.amazon.redshift.RedshiftConnection
import java.sql.Connection
import java.sql.Statement
import java.sql.SQLException
import java.time.Clock
import java.time.Duration
import java.util.Properties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.awscore.AwsRequestOverrideConfiguration
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration
import software.amazon.awssdk.core.exception.SdkException
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.redshift.RedshiftClient
import software.amazon.awssdk.services.redshift.model.GetClusterCredentialsRequest
import software.amazon.awssdk.services.redshift.model.RedshiftException

class RedshiftExecutionDriverProvider(
    private val maximumQuerySeconds: Int,
    private val arrowBatchSize: Int,
    private val timeoutPolicy: JdbcRunTimeoutPolicy,
    private val blockingDispatcher: CoroutineDispatcher,
    private val clock: Clock,
    private val connectionFactory: JdbcConnectionFactory,
    private val iamCredentialsClient: RedshiftIamCredentialsClient,
) : ExecutionDriverProvider {
    private val dialect = RedshiftDialect(iamCredentialsClient, arrowBatchSize)

    override val capability =
        DriverCapabilityDescriptor(connectorType = ConnectorType.REDSHIFT)

    override fun openSession(
        connector: ConnectorRuntimeBinding,
        credential: MaterializedCredential?,
        resultSink: RunResultSink,
        timing: DriverOperationTiming,
    ): ResultProducingRunDriverSession {
        val materializedCredential = timing.measure(DriverPhase.CREDENTIAL) {
            val materializedCredential = requireNotNull(credential) {
                "Redshift credential materialization is missing"
            }
            require(
                materializedCredential.fieldNames() == PASSWORD_CREDENTIAL_FIELDS ||
                    materializedCredential.fieldNames() == IAM_CREDENTIAL_FIELDS,
            ) {
                "Redshift credential fields do not match the driver contract"
            }
            materializedCredential
        }
        return JdbcExecutionDriverSession(
            connectorType = ConnectorType.REDSHIFT,
            dialect = dialect,
            connector = connector,
            credential = materializedCredential,
            resultSink = resultSink,
            arrowEncoder =
                JdbcArrowEncoder(
                    batchSize = arrowBatchSize,
                    decimalPhysicalEncoding = JdbcDecimalPhysicalEncoding.NATIVE_DECIMAL,
                    valueReader = RedshiftJdbcResultValueReader,
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
                requireNotNull(credential) { "Redshift credential materialization is missing" }
            require(
                materializedCredential.fieldNames() == PASSWORD_CREDENTIAL_FIELDS ||
                    materializedCredential.fieldNames() == IAM_CREDENTIAL_FIELDS,
            ) {
                "Redshift credential fields do not match the driver contract"
            }
            materializedCredential
        }
        val (initialRemainingMillis, initialBudget) = timing.measure(DriverPhase.PREPARE) {
            val initialRemainingMillis = deadlineEpochMillis - clock.millis()
            if (initialRemainingMillis < 1_000) {
                throw RawSqlValidationException(
                    failureCode = "RAW_SQL_VALIDATION_DEADLINE_EXCEEDED",
                    safeMessage = "Redshift Raw SQL validation deadline was exceeded",
                    cause = null,
                )
            }
            val measuredBudget = timeoutPolicy.timeoutBudget(initialRemainingMillis, maximumQuerySeconds)
            initialRemainingMillis to measuredBudget
        }
        var properties: Properties? = null
        validateRawSqlOnBlockingConnection(
            remainingMillis = initialRemainingMillis,
            blockingDispatcher = blockingDispatcher,
            timing = timing,
            beforeConnect = {},
            connect = {
                val connectionProperties = timing.measure(DriverPhase.CREDENTIAL) {
                    dialect.connectionProperties(connector, materializedCredential, initialBudget)
                }
                properties = connectionProperties
                timing.measure(DriverPhase.CONNECT) {
                    connectionFactory.open(dialect.jdbcUrl(connector), connectionProperties)
                }
            },
            validate = { connection ->
                timing.measure(DriverPhase.SESSION_SETUP) {
                    connection.isReadOnly = true
                    dialect.prepareConnection(connection, connector, initialBudget)
                }
                timing.measure(DriverPhase.VALIDATE) {
                    RedshiftRawSqlJdbcValidator(
                        connection = connection,
                        statementTimeoutSeconds = {
                            val remainingMillis = deadlineEpochMillis - clock.millis()
                            if (remainingMillis < 1_000) {
                                throw RawSqlValidationException(
                                    failureCode = "RAW_SQL_VALIDATION_DEADLINE_EXCEEDED",
                                    safeMessage = "Redshift Raw SQL validation deadline was exceeded",
                                    cause = null,
                                )
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
            releaseConnectionProperties = { properties?.clear() },
            classifyFailure = { failure ->
                RawSqlValidationException(
                    failureCode = dialect.rawSqlValidationFailureCode(failure),
                    safeMessage = "Redshift Raw SQL validation failed",
                    cause = failure,
                )
            },
            deadlineExceeded = { failure ->
                RawSqlValidationException(
                    failureCode = "RAW_SQL_VALIDATION_DEADLINE_EXCEEDED",
                    safeMessage = "Redshift Raw SQL validation deadline was exceeded",
                    cause = failure,
                )
            },
        )
    }

    private companion object {
        val PASSWORD_CREDENTIAL_FIELDS = setOf("username", "password")
        val IAM_CREDENTIAL_FIELDS =
            setOf(
                "aws_access_key_id",
                "aws_secret_access_key",
                "aws_region",
                "cluster_id",
                "db_user",
            )
    }
}

internal class RedshiftDialect(
    private val iamCredentialsClient: RedshiftIamCredentialsClient,
    private val rowFetchSize: Int,
) : JdbcConnectorDialect {
    init {
        require(rowFetchSize > 0)
    }

    override val serializeConnectionLifecycle = false

    override fun jdbcUrl(connector: ConnectorRuntimeBinding): String {
        val host = connector.requiredText("host")
        val port = connector.requiredInteger("port")
        require(port in 1..65535)
        val database = connector.requiredText("database")
        return "jdbc:redshift://$host:$port/$database"
    }

    override fun connectionProperties(
        connector: ConnectorRuntimeBinding,
        credential: MaterializedCredential,
        timeoutBudget: JdbcRunTimeoutBudget,
    ): Properties {
        val properties = Properties()
        when (credential.fieldNames()) {
            setOf("username", "password") -> {
                credential.useSecret("username") { username ->
                    properties.setProperty("user", String(username))
                }
                credential.useSecret("password") { password ->
                    properties.setProperty("password", String(password))
                }
            }
            setOf(
                "aws_access_key_id",
                "aws_secret_access_key",
                "aws_region",
                "cluster_id",
                "db_user",
            ) -> {
                val temporaryCredential =
                    iamCredentialsClient.fetch(
                        credential = credential.toRedshiftIamCredential(),
                        timeoutBudget = timeoutBudget,
                    )
                properties.setProperty("user", temporaryCredential.user)
                properties.setProperty("password", temporaryCredential.password)
            }
            else -> throw IllegalArgumentException("Redshift credential fields do not match the driver contract")
        }
        properties.setProperty("applicationname", "monomer-execution")
        connector.optionalText("ssl_mode")?.let { sslMode ->
            properties.setProperty("ssl", "true")
            properties.setProperty("sslmode", sslMode.toDriverValue())
        }
        properties.setProperty("logintimeout", timeoutBudget.connectTimeoutSeconds.toString())
        // The default ring buffer reads ahead up to 1 GB of rows and ignores the fetch size.
        properties.setProperty("enablefetchringbuffer", "false")
        properties.setProperty("defaultrowfetchsize", rowFetchSize.toString())
        return properties
    }

    override fun prepareConnection(
        connection: Connection,
        connector: ConnectorRuntimeBinding,
        timeoutBudget: JdbcRunTimeoutBudget,
    ) {
        val schema = connector.optionalText("schema")?.also(::requireNotBlank)
        connection.setNetworkTimeout(DirectExecutor, timeoutBudget.socketTimeoutMillis.toInt())
        connection.autoCommit = false
        schema?.let {
            connection.createStatement().use { statement ->
                statement.queryTimeout = timeoutBudget.statementTimeoutSeconds
                statement.execute("SET search_path TO ${it.quoteIdentifier()}")
            }
        }
    }

    override fun refreshTimeouts(
        connection: Connection,
        timeoutBudget: JdbcRunTimeoutBudget,
    ) {
        connection.setNetworkTimeout(DirectExecutor, timeoutBudget.socketTimeoutMillis.toInt())
    }

    override fun bindQueryParameters(statement: java.sql.PreparedStatement, parameters: List<QueryParameter>) {
        statement.bindRedshiftQueryParameters(parameters)
    }

    // Redshift JDBC's Statement.cancel does nothing once execute() returns, so it cannot stop a later cursor fetch.
    override fun cancelRunningStatement(statement: Statement) =
        statement.connection.unwrap(RedshiftConnection::class.java).cancelQuery()

    override fun safeFailureCode(failure: Throwable): String {
        if (failure is CancellationException) return "CANCELLED"
        val sqlState = (failure as? SQLException)?.sqlState
        if (sqlState != null) {
            return when {
                sqlState.startsWith("28") -> "AUTHENTICATION_FAILED"
                sqlState == "42601" -> "SYNTAX_ERROR"
                sqlState == "42P01" || sqlState == "42703" -> "OBJECT_NOT_FOUND"
                sqlState == "42501" -> "PERMISSION_DENIED"
                sqlState == "57014" -> "CANCELLED"
                sqlState in setOf("53200", "53300", "53400") -> "RESOURCE_EXHAUSTED"
                sqlState.startsWith("08") -> "CONNECTION_FAILED"
                else -> "REDSHIFT_EXECUTION_FAILED"
            }
        }
        val lowerMessage = failure.message.orEmpty().lowercase()
        return when {
            "cluster paused" in lowerMessage || "workgroup is paused" in lowerMessage ->
                "RESOURCE_EXHAUSTED"
            "query was aborted" in lowerMessage -> "CANCELLED"
            "timeout" in lowerMessage -> "TIMEOUT"
            "permission denied" in lowerMessage -> "PERMISSION_DENIED"
            else -> "REDSHIFT_EXECUTION_FAILED"
        }
    }
}

fun interface RedshiftIamCredentialsClient {
    fun fetch(
        credential: RedshiftIamCredential,
        timeoutBudget: JdbcRunTimeoutBudget,
    ): RedshiftTemporaryCredential
}

data class RedshiftIamCredential(
    val awsAccessKeyId: String,
    val awsSecretAccessKey: String,
    val awsRegion: String,
    val clusterId: String,
    val dbUser: String,
) {
    init {
        require(awsAccessKeyId.isNotBlank())
        require(awsSecretAccessKey.isNotBlank())
        require(awsRegion.isNotBlank())
        require(clusterId.isNotBlank())
        require(dbUser.isNotBlank())
        require(awsRegion.length in 8..30)
        require(awsRegion.contains('-'))
        require(awsRegion.matches(Regex("^[a-z0-9-]+$")))
        require(clusterId.length in 1..63)
        require(clusterId.matches(Regex("^[a-z0-9-]+$")))
    }

    override fun toString(): String =
        "RedshiftIamCredential(awsAccessKeyId=[REDACTED], awsSecretAccessKey=[REDACTED], " +
            "awsRegion=$awsRegion, clusterId=$clusterId, dbUser=$dbUser)"
}

data class RedshiftTemporaryCredential(
    val user: String,
    val password: String,
) {
    override fun toString(): String = "RedshiftTemporaryCredential(user=$user, password=[REDACTED])"
}

object DefaultRedshiftIamCredentialsClient : RedshiftIamCredentialsClient {
    override fun fetch(
        credential: RedshiftIamCredential,
        timeoutBudget: JdbcRunTimeoutBudget,
    ): RedshiftTemporaryCredential =
        try {
            RedshiftClient.builder()
                .region(Region.of(credential.awsRegion))
                .overrideConfiguration(redshiftIamClientOverride(timeoutBudget))
                .credentialsProvider(
                    StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(
                            credential.awsAccessKeyId,
                            credential.awsSecretAccessKey,
                        ),
                    ),
                )
                .build()
                .use { redshift ->
                    val response =
                        redshift.getClusterCredentials(
                            GetClusterCredentialsRequest.builder()
                                .clusterIdentifier(credential.clusterId)
                                .dbUser(credential.dbUser)
                                .autoCreate(false)
                                .overrideConfiguration(redshiftIamRequestOverride(timeoutBudget))
                                .build(),
                        )
                    RedshiftTemporaryCredential(
                        user = response.dbUser(),
                        password = response.dbPassword(),
                    )
                }
        } catch (failure: RedshiftException) {
            throw IllegalArgumentException(
                "redshift IAM credential fetch failed: ${failure.javaClass.simpleName}",
                failure,
            )
        } catch (failure: SdkException) {
            throw IllegalArgumentException(
                "redshift IAM credential fetch failed: ${failure.javaClass.simpleName}",
                failure,
            )
        } catch (failure: IllegalArgumentException) {
            throw IllegalArgumentException(
                "redshift IAM credential fetch failed: ${failure.javaClass.simpleName}",
                failure,
            )
        }
}

internal fun redshiftIamClientOverride(
    timeoutBudget: JdbcRunTimeoutBudget,
): ClientOverrideConfiguration {
    return ClientOverrideConfiguration.builder()
        .apiCallTimeout(Duration.ofMillis(timeoutBudget.remainingMillis))
        .apiCallAttemptTimeout(Duration.ofMillis(timeoutBudget.connectTimeoutMillis))
        .build()
}

internal fun redshiftIamRequestOverride(
    timeoutBudget: JdbcRunTimeoutBudget,
): AwsRequestOverrideConfiguration {
    return AwsRequestOverrideConfiguration.builder()
        .apiCallTimeout(Duration.ofMillis(timeoutBudget.remainingMillis))
        .apiCallAttemptTimeout(Duration.ofMillis(timeoutBudget.connectTimeoutMillis))
        .build()
}

private fun MaterializedCredential.toRedshiftIamCredential(): RedshiftIamCredential =
    useSecret("aws_access_key_id") { awsAccessKeyId ->
        useSecret("aws_secret_access_key") { awsSecretAccessKey ->
            useSecret("aws_region") { awsRegion ->
                useSecret("cluster_id") { clusterId ->
                    useSecret("db_user") { dbUser ->
                        RedshiftIamCredential(
                            awsAccessKeyId = String(awsAccessKeyId),
                            awsSecretAccessKey = String(awsSecretAccessKey),
                            awsRegion = String(awsRegion),
                            clusterId = String(clusterId),
                            dbUser = String(dbUser),
                        )
                    }
                }
            }
        }
    }

private fun String.toDriverValue(): String =
    when (this) {
        "disable", "allow", "prefer", "require", "verify-ca", "verify-full" -> this
        else -> throw IllegalArgumentException("Redshift ssl_mode is unsupported")
    }

private fun String.quoteIdentifier(): String =
    "\"${replace("\"", "\"\"")}\""

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
        is ConnectorPropertyValue.Integer -> value.value
        else -> throw IllegalArgumentException("connector property must be an integer")
    }

private fun requireNotBlank(value: String): String {
    require(value.isNotBlank()) { "connector property must not be blank" }
    return value
}
