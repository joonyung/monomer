package cc.monomer.identity.outbound.postgres

import java.sql.Connection
import java.sql.SQLException
import javax.sql.DataSource

class AuthSchemaVerifier(
    private val dataSource: DataSource,
    private val maximumAttempts: Int,
    private val retryDelayMillis: Long,
    private val sleep: (Long) -> Unit,
) {
    init {
        require(maximumAttempts > 0)
        require(retryDelayMillis >= 0)
    }

    fun verify() {
        var lastConnectionFailure: SQLException? = null
        repeat(maximumAttempts) { attempt ->
            try {
                verifyOnce()
                return
            } catch (error: AuthSchemaContractMismatchException) {
                throw error
            } catch (error: SQLException) {
                if (!containsRetryableSqlState(error)) {
                    throw mismatch(
                        "database query failed with SQLSTATE ${error.sqlState ?: "unknown"}",
                        error,
                    )
                }
                lastConnectionFailure = error
                if (attempt + 1 < maximumAttempts) {
                    sleep(retryDelayMillis)
                }
            }
        }
        throw AuthSchemaUnavailableException(
            "AUTH_SCHEMA_CONTRACT_UNAVAILABLE: PostgreSQL remained unavailable after $maximumAttempts attempts",
            lastConnectionFailure,
        )
    }

    private fun verifyOnce() {
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                connection.createStatement().use { statement ->
                    statement.execute("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY")
                }
                verifyContractFacts(connection)
                connection.commit()
            } catch (error: AuthSchemaContractMismatchException) {
                rollback(connection, error)
                throw error
            } catch (error: SQLException) {
                rollback(connection, error)
                throw error
            }
        }
    }

    companion object {
        fun verifyContractFacts(connection: Connection) {
            verifyPostgresMajor(connection)
            verifyBaseTables(connection)
            verifyMarkerShape(connection)
            val marker = readMarker(connection)
            verifyMarker(marker)
            val helper = readFingerprintHelper(connection)
            verifyFingerprintHelper(helper)
            verifyLiveCatalogFingerprint(connection)
        }

        fun isRetryableSqlState(sqlState: String?): Boolean =
            sqlState?.startsWith("08") == true || sqlState == "57P03"

        private fun verifyPostgresMajor(connection: Connection) {
            val serverVersionNumber = connection.createStatement().use { statement ->
                statement.executeQuery("SHOW server_version_num").use { result ->
                    if (!result.next()) {
                        throw mismatch("server_version_num returned no row", null)
                    }
                    result.getString(1)
                }
            }
            val postgresMajor = serverVersionNumber.toIntOrNull()?.div(10_000)
                ?: throw mismatch("server_version_num is malformed: $serverVersionNumber", null)
            if (postgresMajor != AuthSchemaContract.POSTGRES_MAJOR) {
                throw mismatch(
                    "PostgreSQL major expected=${AuthSchemaContract.POSTGRES_MAJOR} actual=$postgresMajor",
                    null,
                )
            }
        }

        private fun verifyBaseTables(connection: Connection) {
            val actualBaseTables = connection.prepareStatement(
                """
                SELECT relation.relname
                  FROM pg_class relation
                  JOIN pg_namespace namespace ON namespace.oid = relation.relnamespace
                 WHERE namespace.nspname = 'identity_runtime'
                   AND relation.relkind = 'r'
                 ORDER BY relation.relname
                """.trimIndent(),
            ).use { statement ->
                statement.executeQuery().use { result ->
                    buildSet {
                        while (result.next()) {
                            add(result.getString(1))
                        }
                    }
                }
            }
            if (actualBaseTables != AuthSchemaContract.baseTables) {
                throw mismatch(
                    "base tables expected=${AuthSchemaContract.baseTables.sorted()} actual=${actualBaseTables.sorted()}",
                    null,
                )
            }
        }

        private fun verifyMarkerShape(connection: Connection) {
            val actualColumns = connection.prepareStatement(
                """
                SELECT column_name, data_type, is_nullable, ordinal_position
                  FROM information_schema.columns
                 WHERE table_schema = 'identity_runtime'
                   AND table_name = 'schema_contract'
                 ORDER BY ordinal_position
                """.trimIndent(),
            ).use { statement ->
                statement.executeQuery().use { result ->
                    buildList {
                        while (result.next()) {
                            add(
                                AuthSchemaContract.MarkerColumn(
                                    name = result.getString("column_name"),
                                    postgresDataType = result.getString("data_type"),
                                    nullable = result.getString("is_nullable") == "YES",
                                    ordinalPosition = result.getInt("ordinal_position"),
                                ),
                            )
                        }
                    }
                }
            }
            if (actualColumns != AuthSchemaContract.schemaContractColumns) {
                throw mismatch(
                    "schema_contract columns expected=${AuthSchemaContract.schemaContractColumns} actual=$actualColumns",
                    null,
                )
            }
        }

        private fun readMarker(connection: Connection): SchemaMarker {
            val markers = connection.prepareStatement(
                """
                SELECT singleton,
                       contract_revision,
                       catalog_fingerprint,
                       fingerprint_function_hash
                  FROM identity_runtime.schema_contract
                 ORDER BY singleton
                """.trimIndent(),
            ).use { statement ->
                statement.executeQuery().use { result ->
                    buildList {
                        while (result.next()) {
                            add(
                                SchemaMarker(
                                    singleton = result.getBoolean("singleton"),
                                    contractRevision = result.getString("contract_revision"),
                                    catalogFingerprint = result.getString("catalog_fingerprint"),
                                    fingerprintFunctionHash = result.getString("fingerprint_function_hash"),
                                ),
                            )
                        }
                    }
                }
            }
            if (markers.size != 1) {
                throw mismatch("schema_contract row count expected=1 actual=${markers.size}", null)
            }
            return markers.single()
        }

        private fun verifyMarker(marker: SchemaMarker) {
            if (!marker.singleton) {
                throw mismatch("schema_contract singleton expected=true actual=false", null)
            }
            if (marker.contractRevision != AuthSchemaContract.SCHEMA_CONTRACT_REVISION) {
                throw mismatch(
                    "contract revision expected=${AuthSchemaContract.SCHEMA_CONTRACT_REVISION} " +
                        "actual=${marker.contractRevision}",
                    null,
                )
            }
            if (marker.catalogFingerprint != AuthSchemaContract.SCHEMA_CATALOG_FINGERPRINT) {
                throw mismatch(
                    "marker catalog fingerprint expected=${AuthSchemaContract.SCHEMA_CATALOG_FINGERPRINT} " +
                        "actual=${marker.catalogFingerprint}",
                    null,
                )
            }
            if (marker.fingerprintFunctionHash != AuthSchemaContract.FINGERPRINT_FUNCTION_HASH) {
                throw mismatch(
                    "marker helper hash expected=${AuthSchemaContract.FINGERPRINT_FUNCTION_HASH} " +
                        "actual=${marker.fingerprintFunctionHash}",
                    null,
                )
            }
        }

        private fun readFingerprintHelper(connection: Connection): FingerprintHelper {
            val helpers = connection.prepareStatement(
                """
                SELECT routine.prokind::TEXT AS kind,
                       format_type(routine.prorettype, NULL) AS return_type,
                       routine.pronargs,
                       md5(pg_get_functiondef(routine.oid)) AS definition_hash
                  FROM pg_proc routine
                 WHERE routine.oid = to_regprocedure('identity_runtime.compute_schema_catalog_fingerprint()')
                """.trimIndent(),
            ).use { statement ->
                statement.executeQuery().use { result ->
                    buildList {
                        while (result.next()) {
                            add(
                                FingerprintHelper(
                                    kind = result.getString("kind"),
                                    returnType = result.getString("return_type"),
                                    argumentCount = result.getInt("pronargs"),
                                    definitionHash = result.getString("definition_hash"),
                                ),
                            )
                        }
                    }
                }
            }
            if (helpers.size != 1) {
                throw mismatch("fingerprint helper count expected=1 actual=${helpers.size}", null)
            }
            return helpers.single()
        }

        private fun verifyFingerprintHelper(helper: FingerprintHelper) {
            if (helper.kind != "f" || helper.returnType != "text" || helper.argumentCount != 0) {
                throw mismatch(
                    "fingerprint helper signature expected=(f,text,0) " +
                        "actual=(${helper.kind},${helper.returnType},${helper.argumentCount})",
                    null,
                )
            }
            if (helper.definitionHash != AuthSchemaContract.FINGERPRINT_FUNCTION_HASH) {
                throw mismatch(
                    "live helper hash expected=${AuthSchemaContract.FINGERPRINT_FUNCTION_HASH} " +
                        "actual=${helper.definitionHash}",
                    null,
                )
            }
        }

        private fun verifyLiveCatalogFingerprint(connection: Connection) {
            val liveCatalogFingerprint = connection.prepareStatement(
                "SELECT identity_runtime.compute_schema_catalog_fingerprint()",
            ).use { statement ->
                statement.executeQuery().use { result ->
                    if (!result.next()) {
                        throw mismatch("live catalog fingerprint returned no row", null)
                    }
                    result.getString(1)
                }
            }
            if (liveCatalogFingerprint != AuthSchemaContract.SCHEMA_CATALOG_FINGERPRINT) {
                throw mismatch(
                    "live catalog fingerprint expected=${AuthSchemaContract.SCHEMA_CATALOG_FINGERPRINT} " +
                        "actual=$liveCatalogFingerprint",
                    null,
                )
            }
        }

        private fun containsRetryableSqlState(error: SQLException): Boolean {
            val sqlStates = mutableListOf<String>()
            var current: SQLException? = error
            while (current != null) {
                if (current.sqlState != null) {
                    sqlStates += current.sqlState
                }
                current = current.nextException
            }
            var cause: Throwable? = error.cause
            while (cause != null) {
                if (cause is SQLException && cause.sqlState != null) {
                    sqlStates += cause.sqlState
                }
                cause = cause.cause
            }
            return sqlStates.isNotEmpty() && sqlStates.all(::isRetryableSqlState)
        }

        private fun rollback(connection: Connection, error: Throwable) {
            try {
                connection.rollback()
            } catch (rollbackError: SQLException) {
                error.addSuppressed(rollbackError)
            }
        }

        private fun mismatch(detail: String, cause: Throwable?): AuthSchemaContractMismatchException =
            AuthSchemaContractMismatchException(
                "AUTH_SCHEMA_CONTRACT_MISMATCH: $detail",
                cause,
            )
    }

    private data class SchemaMarker(
        val singleton: Boolean,
        val contractRevision: String,
        val catalogFingerprint: String,
        val fingerprintFunctionHash: String,
    )

    private data class FingerprintHelper(
        val kind: String,
        val returnType: String,
        val argumentCount: Int,
        val definitionHash: String,
    )
}

class AuthSchemaContractMismatchException(
    message: String,
    cause: Throwable?,
) : IllegalStateException(message, cause)

class AuthSchemaUnavailableException(
    message: String,
    cause: Throwable?,
) : IllegalStateException(message, cause)
