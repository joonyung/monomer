package cc.monomer.execution.driver.snowflake

import cc.monomer.execution.driver.jdbc.DirectExecutor
import cc.monomer.execution.driver.jdbc.rawSqlValidationFailureCode
import cc.monomer.execution.driver.jdbc.validateRawSqlOnBlockingConnection

import cc.monomer.execution.arrow.JdbcArrowEncoder
import cc.monomer.execution.arrow.JdbcDecimalPhysicalEncoding
import cc.monomer.execution.driver.jdbc.JdbcConnectionFactory
import cc.monomer.execution.driver.jdbc.JdbcConnectorDialect
import cc.monomer.execution.driver.jdbc.JdbcExecutionDriverSession
import cc.monomer.execution.driver.jdbc.JdbcRunTimeoutBudget
import cc.monomer.execution.driver.jdbc.JdbcRunTimeoutPolicy
import cc.monomer.execution.driver.jdbc.bindQueryParameter
import cc.monomer.execution.driver.spi.ConnectorPropertyValue
import cc.monomer.execution.driver.spi.ConnectorRuntimeBinding
import cc.monomer.execution.driver.spi.ConnectorType
import cc.monomer.execution.driver.spi.RawSqlValidationException
import cc.monomer.execution.driver.spi.MAXIMUM_RAW_SQL_BYTES
import cc.monomer.execution.driver.spi.DriverCapabilityDescriptor
import cc.monomer.execution.driver.spi.DriverOperationTiming
import cc.monomer.execution.driver.spi.DriverPhase
import cc.monomer.execution.driver.spi.ExecutionDriverProvider
import cc.monomer.execution.driver.spi.MaterializedCredential
import cc.monomer.execution.driver.spi.QueryParameter
import cc.monomer.execution.driver.spi.ResultProducingRunDriverSession
import cc.monomer.execution.driver.spi.RunResultSink
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import java.sql.Connection
import java.sql.Statement
import java.sql.SQLException
import java.time.Clock
import java.util.Base64
import java.util.Properties
import javax.crypto.EncryptedPrivateKeyInfo
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher

class SnowflakeExecutionDriverProvider(
    private val maximumQuerySeconds: Int,
    private val arrowBatchSize: Int,
    private val timeoutPolicy: JdbcRunTimeoutPolicy,
    private val blockingDispatcher: CoroutineDispatcher,
    private val clock: Clock,
    private val connectionFactory: JdbcConnectionFactory,
) : ExecutionDriverProvider {
    override val capability =
        DriverCapabilityDescriptor(connectorType = ConnectorType.SNOWFLAKE)

    override fun openSession(
        connector: ConnectorRuntimeBinding,
        credential: MaterializedCredential?,
        resultSink: RunResultSink,
        timing: DriverOperationTiming,
    ): ResultProducingRunDriverSession {
        val materializedCredential = timing.measure(DriverPhase.CREDENTIAL) {
            val materializedCredential = requireNotNull(credential) { "Snowflake credential materialization is missing" }
            require(materializedCredential.fieldNames() in acceptedCredentialFieldSets) {
                "Snowflake credential fields do not match the driver contract"
            }
            materializedCredential
        }
        return JdbcExecutionDriverSession(
            connectorType = ConnectorType.SNOWFLAKE,
            dialect = SnowflakeDialect,
            connector = connector,
            credential = materializedCredential,
            resultSink = resultSink,
            arrowEncoder =
                JdbcArrowEncoder(
                    batchSize = arrowBatchSize,
                    decimalPhysicalEncoding = JdbcDecimalPhysicalEncoding.NATIVE_DECIMAL,
                    valueReader = SnowflakeJdbcResultValueReader,
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
                requireNotNull(credential) { "Snowflake credential materialization is missing" }
            require(materializedCredential.fieldNames() in acceptedCredentialFieldSets) {
                "Snowflake credential fields do not match the driver contract"
            }
            materializedCredential
        }
        val (initialRemainingMillis, budget) = timing.measure(DriverPhase.PREPARE) {
            val initialRemainingMillis = deadlineEpochMillis - clock.millis()
            if (initialRemainingMillis < 1_000) {
                throw snowflakeValidationDeadlineExceeded(null)
            }
            val measuredBudget = timeoutPolicy.timeoutBudget(initialRemainingMillis, maximumQuerySeconds)
            initialRemainingMillis to measuredBudget
        }
        val properties = timing.measure(DriverPhase.CREDENTIAL) {
            SnowflakeDialect.connectionProperties(connector, materializedCredential, budget)
        }
        validateRawSqlOnBlockingConnection(
            remainingMillis = initialRemainingMillis,
            blockingDispatcher = blockingDispatcher,
            timing = timing,
            beforeConnect = {
                timing.measure(DriverPhase.VALIDATE) {
                    require(sql.toByteArray(Charsets.UTF_8).size <= MAXIMUM_RAW_SQL_BYTES) {
                        "Snowflake Raw SQL exceeds the maximum UTF-8 byte length"
                    }
                }
            },
            connect = {
                if (deadlineEpochMillis - clock.millis() < 1_000) {
                    throw SnowflakeRawSqlValidationDeadlineExceededException()
                }
                timing.measure(DriverPhase.CONNECT) {
                    connectionFactory.open(SnowflakeDialect.jdbcUrl(connector), properties)
                }
            },
            validate = { connection ->
                timing.measure(DriverPhase.SESSION_SETUP) {
                    connection.isReadOnly = true
                    SnowflakeDialect.prepareConnection(connection, connector, budget)
                }
                timing.measure(DriverPhase.VALIDATE) {
                    SnowflakeRawSqlJdbcValidator(
                        connection = connection,
                        statementTimeoutSeconds = {
                            val remainingMillis = deadlineEpochMillis - clock.millis()
                            if (remainingMillis < 1_000) {
                                throw SnowflakeRawSqlValidationDeadlineExceededException()
                            }
                            val currentBudget = timeoutPolicy.timeoutBudget(remainingMillis, maximumQuerySeconds)
                            timing.measure(DriverPhase.SESSION_SETUP) {
                                SnowflakeDialect.refreshTimeouts(connection, currentBudget)
                            }
                            currentBudget.statementTimeoutSeconds
                        },
                    ).validate(sql, parameters)
                }
            },
            releaseConnectionProperties = properties::clear,
            classifyFailure = { failure ->
                if (failure is SnowflakeRawSqlValidationDeadlineExceededException) {
                    snowflakeValidationDeadlineExceeded(failure)
                } else {
                    RawSqlValidationException(
                        failureCode = SnowflakeDialect.rawSqlValidationFailureCode(failure),
                        safeMessage = "Snowflake Raw SQL validation failed",
                        cause = failure,
                    )
                }
            },
            deadlineExceeded = ::snowflakeValidationDeadlineExceeded,
        )
    }

    private companion object {
        val acceptedCredentialFieldSets =
            setOf(
                setOf("username", "password"),
                setOf("username", "private_key"),
                setOf("username", "private_key", "private_key_passphrase"),
            )
    }
}

private fun snowflakeValidationDeadlineExceeded(cause: Throwable?): RawSqlValidationException =
    RawSqlValidationException(
        failureCode = "RAW_SQL_VALIDATION_DEADLINE_EXCEEDED",
        safeMessage = "Snowflake Raw SQL validation deadline was exceeded",
        cause = cause,
    )

internal object SnowflakeDialect : JdbcConnectorDialect {
    override val serializeConnectionLifecycle = false

    override fun jdbcUrl(connector: ConnectorRuntimeBinding): String {
        val account = connector.requiredText("account")
        require(SNOWFLAKE_ACCOUNT.matches(account)) { "Snowflake account must be a public account identifier" }
        val parameters =
            buildList {
                add("warehouse=${connector.requiredText("warehouse").also(::requireSnowflakeIdentifier)}")
                add("db=${connector.requiredText("database").also(::requireSnowflakeIdentifier)}")
                connector.optionalText("schema")?.also(::requireSnowflakeIdentifier)?.let { schema -> add("schema=$schema") }
                connector.optionalText("role")?.also(::requireSnowflakeIdentifier)?.let { role -> add("role=$role") }
            }
        return "jdbc:snowflake://${account}.snowflakecomputing.com/?" + parameters.joinToString("&")
    }

    override fun connectionProperties(
        connector: ConnectorRuntimeBinding,
        credential: MaterializedCredential,
        timeoutBudget: JdbcRunTimeoutBudget,
    ): Properties =
        Properties().apply {
            credential.useSecret("username") { username -> setProperty("user", String(username).also(::requireNotBlank)) }
            setProperty("application", "monomer-execution")
            setProperty("enablePutGet", "false")
            setProperty("MULTI_STATEMENT_COUNT", "1")
            setProperty("CLIENT_TIMESTAMP_TYPE_MAPPING", "TIMESTAMP_LTZ")
            if ("private_key" in credential.fieldNames()) {
                put("privateKey", credential.loadSnowflakePrivateKey())
            } else {
                credential.useSecret("password") { password -> setProperty("password", String(password)) }
            }
            setProperty("loginTimeout", timeoutBudget.connectTimeoutSeconds.toString())
            setProperty("networkTimeout", timeoutBudget.socketTimeoutMillis.toString())
            setProperty("queryTimeout", timeoutBudget.statementTimeoutSeconds.toString())
            setProperty("HTTP_CLIENT_CONNECTION_TIMEOUT", timeoutBudget.connectTimeoutMillis.toString())
            setProperty("HTTP_CLIENT_SOCKET_TIMEOUT", timeoutBudget.socketTimeoutMillis.toString())
        }

    override fun prepareConnection(connection: Connection, connector: ConnectorRuntimeBinding, timeoutBudget: JdbcRunTimeoutBudget) {
        refreshTimeouts(connection, timeoutBudget)
    }

    override fun bindQueryParameters(statement: java.sql.PreparedStatement, parameters: List<QueryParameter>) {
        parameters.forEachIndexed { index, parameter ->
            when (parameter) {
                is QueryParameter.Timestamp -> {
                    // Snowflake 3.20.0 divides negative epoch milliseconds toward zero.
                    if (parameter.value.epochSecond < 0 && parameter.value.nano >= 1_000_000) {
                        throw SQLException("Snowflake JDBC cannot represent this pre-epoch fractional TIMESTAMP exactly", "22008")
                    }
                    statement.bindQueryParameter(index + 1, parameter)
                }
                is QueryParameter.Date -> {
                    val calendar = java.util.GregorianCalendar(java.util.TimeZone.getTimeZone("UTC"))
                    calendar.clear()
                    calendar.set(parameter.value.year, parameter.value.monthValue - 1, parameter.value.dayOfMonth)
                    val date = java.sql.Date(calendar.timeInMillis)
                    val encodedMillis = date.time - net.snowflake.client.core.ResultUtil.msDiffJulianToGregorian(date)
                    if (encodedMillis != parameter.value.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()) {
                        throw SQLException("Snowflake JDBC cannot represent this DATE exactly", "22008")
                    }
                    statement.setDate(index + 1, date, calendar)
                }
                else -> statement.bindQueryParameter(index + 1, parameter)
            }
        }
    }

    override fun refreshTimeouts(connection: Connection, timeoutBudget: JdbcRunTimeoutBudget) {
        connection.setNetworkTimeout(DirectExecutor, timeoutBudget.socketTimeoutMillis.toInt())
    }

    override fun cancelRunningStatement(statement: Statement) = statement.cancel()

    override fun safeFailureCode(failure: Throwable): String {
        if (failure is CancellationException) return "CANCELLED"
        val sqlState = (failure as? SQLException)?.sqlState
        val lowerMessage = failure.message.orEmpty().lowercase()
        return when {
            (failure as? SQLException)?.errorCode == 390100 || sqlState?.startsWith("28") == true -> "AUTHENTICATION_FAILED"
            sqlState == "42000" -> "SYNTAX_ERROR"
            sqlState == "42S02" -> "OBJECT_NOT_FOUND"
            sqlState == "42501" -> "PERMISSION_DENIED"
            sqlState == "57014" -> "CANCELLED"
            sqlState?.startsWith("08") == true -> "CONNECTION_FAILED"
            "statement reached its statement timeout" in lowerMessage || "timeout" in lowerMessage -> "TIMEOUT"
            "insufficient privileges" in lowerMessage || "permission denied" in lowerMessage -> "PERMISSION_DENIED"
            "does not exist" in lowerMessage -> "OBJECT_NOT_FOUND"
            "connection refused" in lowerMessage || "connection reset" in lowerMessage ||
                "could not connect" in lowerMessage || "io error" in lowerMessage -> "CONNECTION_FAILED"
            else -> "SNOWFLAKE_EXECUTION_FAILED"
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

private fun MaterializedCredential.loadSnowflakePrivateKey(): PrivateKey {
    val pem = useSecret("private_key") { String(it) }
    val passphrase = optionalSecret("private_key_passphrase")?.useCopy { String(it) }
    return SnowflakePrivateKeyLoader.load(pem, passphrase)
}

private fun requireNotBlank(value: String): String {
    require(value.isNotBlank()) { "connector property must not be blank" }
    return value
}

private object SnowflakePrivateKeyLoader {
    private val unencryptedHeader = Regex("-----BEGIN PRIVATE KEY-----") // gitleaks:allow — PEM delimiter, not key material.
    private val unencryptedFooter = Regex("-----END PRIVATE KEY-----")
    private val encryptedHeader = Regex("-----BEGIN ENCRYPTED PRIVATE KEY-----") // gitleaks:allow — PEM delimiter, not key material.
    private val encryptedFooter = Regex("-----END ENCRYPTED PRIVATE KEY-----")

    fun load(pem: String, passphrase: String?): PrivateKey {
        require(pem.isNotBlank()) { "private_key PEM must not be blank" }
        val encrypted = encryptedHeader.containsMatchIn(pem)
        val unencrypted = !encrypted && unencryptedHeader.containsMatchIn(pem)
        require(encrypted || unencrypted) { "private_key PEM must contain a supported PKCS#8 header" }
        return if (encrypted) {
            require(!passphrase.isNullOrEmpty()) { "private_key_passphrase is required for an encrypted private key" }
            decodeEncrypted(pem, passphrase)
        } else {
            require(passphrase == null) { "private_key_passphrase must not be set for an unencrypted private key" }
            decodeUnencrypted(pem)
        }
    }

    private fun decodeUnencrypted(pem: String): PrivateKey {
        val decoded = decodeBase64(stripPemArmor(pem, unencryptedHeader, unencryptedFooter))
        return runCatching { KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(decoded)) }
            .getOrElse { throw IllegalArgumentException("failed to parse PKCS#8 private key", it) }
    }

    private fun decodeEncrypted(pem: String, passphrase: String): PrivateKey {
        val decoded = decodeBase64(stripPemArmor(pem, encryptedHeader, encryptedFooter))
        val encrypted = runCatching { EncryptedPrivateKeyInfo(decoded) }
            .getOrElse { throw IllegalArgumentException("malformed encrypted PKCS#8 private key", it) }
        val key = SecretKeyFactory.getInstance(encrypted.algName).generateSecret(PBEKeySpec(passphrase.toCharArray()))
        val spec = encrypted.getKeySpec(key)
        return runCatching { KeyFactory.getInstance("RSA").generatePrivate(spec) }
            .getOrElse { throw IllegalArgumentException("decrypted key is not an RSA private key", it) }
    }

    private fun stripPemArmor(pem: String, header: Regex, footer: Regex): String =
        pem.replace(header, "").replace(footer, "").replace(Regex("\\s"), "")

    private fun decodeBase64(body: String): ByteArray =
        runCatching { Base64.getDecoder().decode(body) }
            .getOrElse { throw IllegalArgumentException("private_key PEM is not valid base64", it) }
}

private val SNOWFLAKE_ACCOUNT = Regex("^(?!.*(?:^|\\.)[Pp][Rr][Ii][Vv][Aa][Tt][Ee][Ll][Ii][Nn][Kk](?:\\.|$))[A-Za-z](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?(?:\\.(?:[Aa][Ww][Ss]|[Aa][Zz][Uu][Rr][Ee]|[Gg][Cc][Pp]))?)?(?![\\s\\S])")
private val SNOWFLAKE_IDENTIFIER = Regex("^[A-Za-z_][A-Za-z0-9_$]{0,127}$")

private fun requireSnowflakeIdentifier(value: String) {
    require(SNOWFLAKE_IDENTIFIER.matches(value)) { "Snowflake identifier is invalid" }
}
