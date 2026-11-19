package cc.monomer.connector.domain

import cc.monomer.connector.api.*
import kotlinx.serialization.json.*
import kotlin.test.*

class ConnectorTypedContractTest {
    @Test
    fun `public codec preserves scalar kinds and omission`() {
        val properties = ConnectorSettingsProperties.Trino("host", 443, "catalog", null, true, TrinoSslVerification.FULL)
        val encoded = ConnectorSettingsCodec.encode(properties)
        assertFalse(encoded.getValue("port").jsonPrimitive.isString)
        assertFalse(encoded.getValue("ssl").jsonPrimitive.isString)
        assertFalse("schema" in encoded)
        assertEquals(properties, ConnectorSettingsCodec.decode(ConnectorProductType.TRINO, encoded))
        assertEquals(properties, connectorPropertiesFromStorage(ConnectorProductType.TRINO, properties.storageValues()))
    }

    @Test
    fun `public codec rejects string scalars null unknown fields and invalid enums safely`() {
        val valid = Json.parseToJsonElement("""{"host":"host","port":5432,"database":"db"}""").jsonObject
        for (invalid in listOf(
            valid + ("port" to JsonPrimitive("5432")), valid + ("schema" to JsonNull),
            valid + ("password" to JsonPrimitive("sensitive-value")), valid + ("sslMode" to JsonPrimitive("sensitive-value")),
        )) {
            val failure = assertFailsWith<IllegalArgumentException> {
                ConnectorSettingsCodec.decode(ConnectorProductType.POSTGRESQL, JsonObject(invalid))
            }
            assertFalse(failure.toString().contains("sensitive-value"))
        }
    }

    @Test
    fun `property values have typed validation failures while malformed scalars stay decoding failures`() {
        val invalidPort = Json.parseToJsonElement("""{"host":"host","port":0,"database":"db"}""").jsonObject
        val valueFailure = assertFailsWith<ConnectorInputValueException> {
            ConnectorSettingsCodec.decode(ConnectorProductType.POSTGRESQL, invalidPort)
        }
        assertEquals("Connector port must be between 1 and 65535", valueFailure.detail)
        val malformedPort = JsonObject(invalidPort + ("port" to JsonPrimitive("0")))
        val decodingFailure = assertFailsWith<IllegalArgumentException> {
            ConnectorSettingsCodec.decode(ConnectorProductType.POSTGRESQL, malformedPort)
        }
        assertFalse(decodingFailure is ConnectorInputValueException)
    }

    @Test
    fun `Databricks accepts bounded warehouse identifiers and rejects HTTP paths`() {
        val host = "workspace.cloud.databricks.com"
        val schema = Json.parseToJsonElement(javaClass.classLoader.getResource("schema/connector.schema.json")!!.readText())
        val pattern = Regex(schema.jsonObject.getValue("\$defs").jsonObject.getValue("DatabricksProperties")
            .jsonObject.getValue("properties").jsonObject.getValue("warehouseId").jsonObject.getValue("pattern").jsonPrimitive.content)
        for (warehouseId in listOf("a", "AbC0123", "a".repeat(128))) {
            val properties = ConnectorSettingsProperties.Databricks(host, warehouseId, null, null)
            assertEquals(properties, ConnectorSettingsCodec.decode(ConnectorProductType.DATABRICKS, ConnectorSettingsCodec.encode(properties)))
            assertTrue(pattern.matches(warehouseId))
        }
        for (warehouseId in listOf("", "/sql/1.0/warehouses/abc", "abc/def", "abc-def", "abc_def", "abc.def", " abc", "abc\n", "a".repeat(129))) {
            assertFalse(pattern.matches(warehouseId), warehouseId)
            assertFailsWith<IllegalArgumentException> { ConnectorSettingsProperties.Databricks(host, warehouseId, null, null) }
        }
        for (invalid in listOf(
            """{"host":"$host","httpPath":"/sql/1.0/warehouses/abc"}""",
            """{"host":"$host","warehouseId":null}""",
            """{"host":"$host","warehouseId":123}""",
        )) {
            assertFailsWith<IllegalArgumentException> {
                ConnectorSettingsCodec.decode(ConnectorProductType.DATABRICKS, Json.parseToJsonElement(invalid).jsonObject)
            }
        }
    }

    @Test
    fun `credential union is closed write only and type compatible`() {
        val credential = ConnectorCredentialCodec.decode(Json.parseToJsonElement(
            """{"kind":"USERNAME_PASSWORD","username":"user","password":"sensitive-value"}""").jsonObject)
        assertEquals("[REDACTED]", credential.toString())
        credential.requireCompatible(ConnectorProductType.POSTGRESQL)
        assertFailsWith<IllegalArgumentException> { credential.requireCompatible(ConnectorProductType.BIGQUERY) }
        assertFailsWith<IllegalArgumentException> { ConnectorCredentialCodec.decode(Json.parseToJsonElement(
            """{"kind":"USERNAME_PASSWORD","username":"user","password":"sensitive-value","accessToken":"secret"}""").jsonObject) }
    }
}
