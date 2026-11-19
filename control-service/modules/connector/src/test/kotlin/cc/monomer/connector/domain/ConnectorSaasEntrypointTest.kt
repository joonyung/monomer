package cc.monomer.connector.domain

import cc.monomer.connector.api.ConnectorSettingsProperties
import kotlinx.serialization.json.*
import kotlin.test.*

class ConnectorSaasEntrypointTest {
    @Test
    fun `accepts official public Databricks entrypoints in contract and schema`() {
        val schema = Json.parseToJsonElement(javaClass.classLoader.getResource("schema/connector.schema.json")!!.readText()).jsonObject
        val pattern = Regex(schema.getValue("\$defs").jsonObject.getValue("DatabricksProperties").jsonObject
            .getValue("properties").jsonObject.getValue("host").jsonObject.getValue("pattern").jsonPrimitive.content)
        listOf(
            "dbc-1234.cloud.databricks.com",
            "DBC-1234.CLOUD.DATABRICKS.COM",
            "adb-123456789.12.azuredatabricks.net",
            "123456789.12.gcp.databricks.com",
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.cloud.databricks.com",
        ).forEach { value ->
            ConnectorSettingsProperties.Databricks(value, "abc", null, null)
            assertTrue(pattern.matches(value), value)
        }
    }

    @Test
    fun `rejects unofficial and injected public Databricks entrypoints in contract and schema`() {
        val schema = Json.parseToJsonElement(javaClass.classLoader.getResource("schema/connector.schema.json")!!.readText()).jsonObject
        val pattern = Regex(schema.getValue("\$defs").jsonObject.getValue("DatabricksProperties").jsonObject
            .getValue("properties").jsonObject.getValue("host").jsonObject.getValue("pattern").jsonPrimitive.content)
        listOf(
            "dbc.cloud.databricks.com\n",
            "workspace.databricks.com",
            "accounts.cloud.databricks.com",
            "ACCOUNTS.CLOUD.DATABRICKS.COM",
            "privatelink.cloud.databricks.com",
            "dbc.cloud.databricks.com.evil.com",
            "dbc.evil.cloud.databricks.com",
            "https://dbc.cloud.databricks.com",
            "dbc.cloud.databricks.com:443",
            "dbc.cloud.databricks.com/",
            "user@dbc.cloud.databricks.com",
            "dbc.cloud.databricks.com%2fevil",
            "dbc.cloud.databricks.com.",
            "déb.cloud.databricks.com",
            "127.0.0.1",
            "[::1]",
            "10.0.0.1",
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.cloud.databricks.com",
            "-dbc.cloud.databricks.com",
            "dbc-.cloud.databricks.com",
            "adb-123.12.privatelink.azuredatabricks.net",
            "123.12.privatelink.gcp.databricks.com",
        ).forEach { value ->
            assertFailsWith<IllegalArgumentException>(value) { ConnectorSettingsProperties.Databricks(value, "abc", null, null) }
            assertFalse(pattern.matches(value), value)
        }
    }

    @Test
    fun `accepts official public Snowflake entrypoints in contract and schema`() {
        val schema = Json.parseToJsonElement(javaClass.classLoader.getResource("schema/connector.schema.json")!!.readText()).jsonObject
        val pattern = Regex(schema.getValue("\$defs").jsonObject.getValue("SnowflakeProperties").jsonObject
            .getValue("properties").jsonObject.getValue("account").jsonObject.getValue("pattern").jsonPrimitive.content)
        listOf(
            "org-account",
            "ORG-ACCOUNT",
            "ab12345",
            "ab12345.eu-central-1",
            "ab12345.eu-central-1.aws",
            "ab12345.west-europe.azure",
            "ab12345.us-central1.gcp",
            "org-account-with-hyphens",
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
        ).forEach { value ->
            ConnectorSettingsProperties.Snowflake(value, "WH", "DB", null, null)
            assertTrue(pattern.matches(value), value)
        }
    }

    @Test
    fun `rejects unofficial and injected public Snowflake entrypoints in contract and schema`() {
        val schema = Json.parseToJsonElement(javaClass.classLoader.getResource("schema/connector.schema.json")!!.readText()).jsonObject
        val pattern = Regex(schema.getValue("\$defs").jsonObject.getValue("SnowflakeProperties").jsonObject
            .getValue("properties").jsonObject.getValue("account").jsonObject.getValue("pattern").jsonPrimitive.content)
        listOf(
            "org-account\n",
            "org-account.snowflakecomputing.com",
            "org-account.snowflakecomputing.com.evil.com",
            "https://org-account",
            "org-account:443",
            "org-account/",
            "user@org-account",
            "org-account?insecureMode=true",
            "org-account%2fevil",
            "org-account.",
            "org_account",
            "örg-account",
            "127.0.0.1",
            "[::1]",
            "privatelink",
            "org-account.privatelink",
            "org-account.PRIVATELINK",
            "ab12345.region.privatelink",
            "ab12345.region.evil",
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            "ab12345.aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            "-org-account",
            "org-account-",
        ).forEach { value ->
            assertFailsWith<IllegalArgumentException>(value) { ConnectorSettingsProperties.Snowflake(value, "WH", "DB", null, null) }
            assertFalse(pattern.matches(value), value)
        }
    }

}
