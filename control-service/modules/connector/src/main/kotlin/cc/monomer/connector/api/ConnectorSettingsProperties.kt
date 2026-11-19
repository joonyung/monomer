package cc.monomer.connector.api

import kotlinx.serialization.json.*

enum class PostgresqlSslMode { DISABLE, REQUIRE, VERIFY_CA, VERIFY_FULL }
enum class RedshiftSslMode { DISABLE, ALLOW, PREFER, REQUIRE, VERIFY_CA, VERIFY_FULL }
enum class TrinoSslVerification { FULL, CA, NONE }

sealed interface ConnectorSettingsProperties {
    val connectorType: ConnectorProductType
    data class Duckdb(
        val sampleDataset: String,
    ) : ConnectorSettingsProperties {
        override val connectorType = ConnectorProductType.DUCKDB
        init { validateConnectorProperties(this) }
    }
    data class Postgresql(
        val host: String,
        val port: Int,
        val database: String,
        val schema: String?,
        val sslMode: PostgresqlSslMode?,
    ) : ConnectorSettingsProperties {
        override val connectorType = ConnectorProductType.POSTGRESQL
        init { validateConnectorProperties(this) }
    }
    data class Bigquery(
        val projectId: String,
        val location: String,
    ) : ConnectorSettingsProperties {
        override val connectorType = ConnectorProductType.BIGQUERY
        init { validateConnectorProperties(this) }
    }
    data class Snowflake(
        val account: String,
        val warehouse: String,
        val database: String,
        val schema: String?,
        val role: String?,
    ) : ConnectorSettingsProperties {
        override val connectorType = ConnectorProductType.SNOWFLAKE
        init { validateConnectorProperties(this) }
    }
    data class Trino(
        val host: String,
        val port: Int,
        val catalog: String,
        val schema: String?,
        val ssl: Boolean?,
        val sslVerification: TrinoSslVerification?,
    ) : ConnectorSettingsProperties {
        override val connectorType = ConnectorProductType.TRINO
        init { validateConnectorProperties(this) }
    }
    data class Redshift(
        val host: String,
        val port: Int,
        val database: String,
        val schema: String?,
        val sslMode: RedshiftSslMode?,
    ) : ConnectorSettingsProperties {
        override val connectorType = ConnectorProductType.REDSHIFT
        init { validateConnectorProperties(this) }
    }
    data class Databricks(
        val host: String,
        val warehouseId: String,
        val catalog: String?,
        val schema: String?,
    ) : ConnectorSettingsProperties {
        override val connectorType = ConnectorProductType.DATABRICKS
        init { validateConnectorProperties(this) }
    }
}

object ConnectorSettingsCodec {
    fun encode(properties: ConnectorSettingsProperties): JsonObject = buildJsonObject {
        when (properties) {
            is ConnectorSettingsProperties.Duckdb -> put("sampleDataset", properties.sampleDataset)
            is ConnectorSettingsProperties.Postgresql -> {
                put("host", properties.host)
                put("port", properties.port)
                put("database", properties.database)
                properties.schema?.let { put("schema", it) }
                properties.sslMode?.let { put("sslMode", it.name) }
            }
            is ConnectorSettingsProperties.Bigquery -> {
                put("projectId", properties.projectId)
                put("location", properties.location)
            }
            is ConnectorSettingsProperties.Snowflake -> {
                put("account", properties.account)
                put("warehouse", properties.warehouse)
                put("database", properties.database)
                properties.schema?.let { put("schema", it) }
                properties.role?.let { put("role", it) }
            }
            is ConnectorSettingsProperties.Trino -> {
                put("host", properties.host)
                put("port", properties.port)
                put("catalog", properties.catalog)
                properties.schema?.let { put("schema", it) }
                properties.ssl?.let { put("ssl", it) }
                properties.sslVerification?.let { put("sslVerification", it.name) }
            }
            is ConnectorSettingsProperties.Redshift -> {
                put("host", properties.host)
                put("port", properties.port)
                put("database", properties.database)
                properties.schema?.let { put("schema", it) }
                properties.sslMode?.let { put("sslMode", it.name) }
            }
            is ConnectorSettingsProperties.Databricks -> {
                put("host", properties.host)
                put("warehouseId", properties.warehouseId)
                properties.catalog?.let { put("catalog", it) }
                properties.schema?.let { put("schema", it) }
            }
        }
    }

    fun decode(type: ConnectorProductType, value: JsonObject): ConnectorSettingsProperties {
        val expected = when (type) {
            ConnectorProductType.DUCKDB -> setOf("sampleDataset")
            ConnectorProductType.POSTGRESQL -> setOf("host", "port", "database", "schema", "sslMode")
            ConnectorProductType.BIGQUERY -> setOf("projectId", "location")
            ConnectorProductType.SNOWFLAKE -> setOf("account", "warehouse", "database", "schema", "role")
            ConnectorProductType.TRINO -> setOf("host", "port", "catalog", "schema", "ssl", "sslVerification")
            ConnectorProductType.REDSHIFT -> setOf("host", "port", "database", "schema", "sslMode")
            ConnectorProductType.DATABRICKS -> setOf("host", "warehouseId", "catalog", "schema")
        }
        require(value.keys.all { it in expected }) { "Connector properties contain an unknown field" }
        return when (type) {
            ConnectorProductType.DUCKDB -> ConnectorSettingsProperties.Duckdb(value.text("sampleDataset"))
            ConnectorProductType.POSTGRESQL -> ConnectorSettingsProperties.Postgresql(
                host = value.text("host"),
                port = value.integer("port"),
                database = value.text("database"),
                schema = if ("schema" in value) value.text("schema") else null,
                sslMode = if ("sslMode" in value) enumValue<PostgresqlSslMode>(value.text("sslMode")) else null,
            )
            ConnectorProductType.BIGQUERY -> ConnectorSettingsProperties.Bigquery(
                projectId = value.text("projectId"),
                location = value.text("location"),
            )
            ConnectorProductType.SNOWFLAKE -> ConnectorSettingsProperties.Snowflake(
                account = value.text("account"),
                warehouse = value.text("warehouse"),
                database = value.text("database"),
                schema = if ("schema" in value) value.text("schema") else null,
                role = if ("role" in value) value.text("role") else null,
            )
            ConnectorProductType.TRINO -> ConnectorSettingsProperties.Trino(
                host = value.text("host"),
                port = value.integer("port"),
                catalog = value.text("catalog"),
                schema = if ("schema" in value) value.text("schema") else null,
                ssl = if ("ssl" in value) value.boolean("ssl") else null,
                sslVerification = if ("sslVerification" in value) enumValue<TrinoSslVerification>(value.text("sslVerification")) else null,
            )
            ConnectorProductType.REDSHIFT -> ConnectorSettingsProperties.Redshift(
                host = value.text("host"),
                port = value.integer("port"),
                database = value.text("database"),
                schema = if ("schema" in value) value.text("schema") else null,
                sslMode = if ("sslMode" in value) enumValue<RedshiftSslMode>(value.text("sslMode")) else null,
            )
            ConnectorProductType.DATABRICKS -> ConnectorSettingsProperties.Databricks(
                host = value.text("host"),
                warehouseId = value.text("warehouseId"),
                catalog = if ("catalog" in value) value.text("catalog") else null,
                schema = if ("schema" in value) value.text("schema") else null,
            )
        }
    }
}

internal fun JsonObject.text(name: String): String {
    val value = this[name] as? JsonPrimitive
    require(value != null && value.isString && value.content.isNotBlank()) { "Connector field requires a non-empty string" }
    return value.content
}
private fun JsonObject.integer(name: String): Int {
    val value = this[name] as? JsonPrimitive
    require(value != null && !value.isString && value.intOrNull != null) { "Connector port requires an integer" }
    return value.int
}
private fun JsonObject.boolean(name: String): Boolean {
    val value = this[name] as? JsonPrimitive
    require(value != null && !value.isString && value.booleanOrNull != null) { "Connector ssl requires a boolean" }
    return value.boolean
}
private inline fun <reified T : Enum<T>> enumValue(value: String): T =
    requireNotNull(enumValues<T>().firstOrNull { it.name == value }) { "Connector enum value is invalid" }

internal fun ConnectorSettingsProperties.storageValues(): Map<String, String> =
    ConnectorSettingsCodec.encode(this).map { (name, value) ->
        val storedName = when {
            connectorType == ConnectorProductType.DUCKDB && name == "sampleDataset" -> "sample_dataset"
            connectorType == ConnectorProductType.POSTGRESQL && name == "sslMode" -> "ssl_mode"
            connectorType == ConnectorProductType.BIGQUERY && name == "projectId" -> "project_id"
            else -> name
        }
        val storedValue = when {
            name == "sslMode" && connectorType == ConnectorProductType.POSTGRESQL -> value.jsonPrimitive.content.lowercase()
            name == "sslMode" && connectorType == ConnectorProductType.REDSHIFT -> value.jsonPrimitive.content.lowercase().replace('_', '-')
            else -> value.jsonPrimitive.content
        }
        storedName to storedValue
    }.toMap()

internal fun connectorPropertiesFromStorage(type: ConnectorProductType, values: Map<String, String>): ConnectorSettingsProperties =
    ConnectorSettingsCodec.decode(type, buildJsonObject {
        values.forEach { (name, value) ->
            val publicName = when {
                type == ConnectorProductType.DUCKDB && name == "sample_dataset" -> "sampleDataset"
                type == ConnectorProductType.POSTGRESQL && name == "ssl_mode" -> "sslMode"
                type == ConnectorProductType.BIGQUERY && name == "project_id" -> "projectId"
                else -> name
            }
            when (publicName) {
                "port" -> { require(value.toIntOrNull()?.toString() == value) { "Invalid stored port" }; put(publicName, value.toInt()) }
                "ssl" -> { require(value in setOf("true", "false")) { "Invalid stored ssl" }; put(publicName, value.toBooleanStrict()) }
                "sslMode" -> put(publicName, value.uppercase().replace('-', '_'))
                else -> put(publicName, value)
            }
        }
    })

class ConnectorInputValueException(val detail: String) : IllegalArgumentException(detail)

private fun validateConnectorProperties(properties: ConnectorSettingsProperties) {
    fun requireValue(valid: Boolean, message: () -> String) {
        if (!valid) throw ConnectorInputValueException(message())
    }
    fun host(value: String) = requireValue(value.isCanonicalHost()) { "Connector host is invalid" }
    fun port(value: Int) = requireValue(value in 1..65535) { "Connector port must be between 1 and 65535" }
    fun database(value: String) = requireValue(value.isCanonicalDatabaseName()) { "Connector database is invalid" }
    fun identifiers(vararg values: String?) = values.forEach { value ->
        requireValue(value == null || value.isCanonicalSqlIdentifier()) { "Connector identifier is invalid" }
    }
    when (properties) {
        is ConnectorSettingsProperties.Duckdb -> requireValue(properties.sampleDataset in setOf("acme-cloud@1", "acme-cloud@2", "acme-cloud@3")) { "Unsupported sample dataset" }
        is ConnectorSettingsProperties.Postgresql -> {
            host(properties.host); port(properties.port); database(properties.database); identifiers(properties.schema)
        }
        is ConnectorSettingsProperties.Bigquery -> {
            requireValue(properties.projectId.isCanonicalGoogleProjectId()) { "BigQuery projectId is invalid" }
            requireValue(properties.location.isCanonicalBigQueryLocation()) { "BigQuery location is invalid" }
        }
        is ConnectorSettingsProperties.Snowflake -> {
            requireValue(properties.account.matches(snowflakeAccountPattern)) { "Snowflake account is invalid" }
            identifiers(properties.warehouse, properties.database, properties.schema, properties.role)
        }
        is ConnectorSettingsProperties.Trino -> {
            host(properties.host); port(properties.port); identifiers(properties.catalog, properties.schema)
        }
        is ConnectorSettingsProperties.Redshift -> {
            host(properties.host); port(properties.port); database(properties.database); identifiers(properties.schema)
        }
        is ConnectorSettingsProperties.Databricks -> {
            requireValue(properties.host.isCanonicalDatabricksHost()) { "Databricks host is invalid" }
            requireValue(properties.warehouseId.isCanonicalDatabricksWarehouseId()) { "Databricks warehouseId is invalid" }
            identifiers(properties.catalog, properties.schema)
        }
    }
}

private val sqlIdentifierPattern = Regex("^[A-Za-z_][A-Za-z0-9_$]{0,127}$")
private val databaseNamePattern = Regex("^[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}$")
private val hostPattern =
    Regex("^(?:[A-Za-z0-9](?:[A-Za-z0-9.-]{0,251}[A-Za-z0-9])?|\\[[0-9A-Fa-f:]+\\])$")
private val googleProjectIdPattern = Regex("^[a-z][a-z0-9-]{4,28}[a-z0-9]$")
private val bigQueryLocationPattern = Regex("^(?![A-Za-z0-9-]*--)[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?$")
private val databricksHostPattern = Regex("^(?=.{1,253}$)(?![Aa][Cc][Cc][Oo][Uu][Nn][Tt][Ss]\\.)(?![Pp][Rr][Ii][Vv][Aa][Tt][Ee][Ll][Ii][Nn][Kk]\\.)(?:[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?\\.[Cc][Ll][Oo][Uu][Dd]\\.[Dd][Aa][Tt][Aa][Bb][Rr][Ii][Cc][Kk][Ss]\\.[Cc][Oo][Mm]|[Aa][Dd][Bb]-[0-9]{1,59}\\.[0-9]{1,63}\\.[Aa][Zz][Uu][Rr][Ee][Dd][Aa][Tt][Aa][Bb][Rr][Ii][Cc][Kk][Ss]\\.[Nn][Ee][Tt]|[0-9]{1,63}\\.[0-9]{1,63}\\.[Gg][Cc][Pp]\\.[Dd][Aa][Tt][Aa][Bb][Rr][Ii][Cc][Kk][Ss]\\.[Cc][Oo][Mm])(?![\\s\\S])")
private val snowflakeAccountPattern = Regex("^(?!.*(?:^|\\.)[Pp][Rr][Ii][Vv][Aa][Tt][Ee][Ll][Ii][Nn][Kk](?:\\.|$))[A-Za-z](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?(?:\\.(?:[Aa][Ww][Ss]|[Aa][Zz][Uu][Rr][Ee]|[Gg][Cc][Pp]))?)?(?![\\s\\S])")
private val databricksWarehouseIdPattern = Regex("^[A-Za-z0-9]{1,128}(?![\\s\\S])")

private fun String.isCanonicalSqlIdentifier(): Boolean = matches(sqlIdentifierPattern)
private fun String.isCanonicalDatabaseName(): Boolean = matches(databaseNamePattern)
private fun String.isCanonicalHost(): Boolean = matches(hostPattern) && !contains("..")
private fun String.isCanonicalGoogleProjectId(): Boolean = matches(googleProjectIdPattern)
private fun String.isCanonicalBigQueryLocation(): Boolean = matches(bigQueryLocationPattern)
private fun String.isCanonicalDatabricksHost(): Boolean =
    matches(databricksHostPattern) && !contains("..")
private fun String.isCanonicalDatabricksWarehouseId(): Boolean = matches(databricksWarehouseIdPattern)
