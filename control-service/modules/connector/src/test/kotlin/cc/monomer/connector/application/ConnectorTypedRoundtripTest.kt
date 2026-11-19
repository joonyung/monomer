@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.connector.application

import cc.monomer.connector.api.*
import cc.monomer.execution.api.ConnectorRuntimePropertyValue
import cc.monomer.identity.api.WorkspaceId
import java.time.Instant
import kotlin.test.*
import kotlin.uuid.Uuid
import kotlinx.serialization.json.*

class ConnectorTypedRoundtripTest {
    @Test
    fun `all settings preserve public storage and execution meanings`() {
        val fixtures = listOf(
            SettingsFixture(ConnectorProductType.DUCKDB,
                """{"sampleDataset":"acme-cloud@1"}""", """{"sample_dataset":"acme-cloud@1"}""", """{"sample_dataset":"acme-cloud@1"}"""),
            SettingsFixture(ConnectorProductType.DUCKDB,
                """{"sampleDataset":"acme-cloud@2"}""", """{"sample_dataset":"acme-cloud@2"}""", """{"sample_dataset":"acme-cloud@2"}"""),
            SettingsFixture(ConnectorProductType.DUCKDB,
                """{"sampleDataset":"acme-cloud@3"}""", """{"sample_dataset":"acme-cloud@3"}""", """{"sample_dataset":"acme-cloud@3"}"""),
            SettingsFixture(ConnectorProductType.POSTGRESQL,
                """{"host":"pg.internal","port":5432,"database":"warehouse","schema":"analytics","sslMode":"VERIFY_FULL"}""",
                """{"host":"pg.internal","port":"5432","database":"warehouse","schema":"analytics","ssl_mode":"verify_full"}""",
                """{"host":"pg.internal","port":5432,"database":"warehouse","schema":"analytics","ssl_mode":"verify_full"}"""),
            SettingsFixture(ConnectorProductType.BIGQUERY,
                """{"projectId":"monomer-test","location":"us-central1"}""",
                """{"project_id":"monomer-test","location":"us-central1"}""",
                """{"project_id":"monomer-test","location":"us-central1"}"""),
            SettingsFixture(ConnectorProductType.SNOWFLAKE,
                """{"account":"org-account","warehouse":"COMPUTE_WH","database":"WAREHOUSE","schema":"ANALYTICS","role":"QUERY_ROLE"}""",
                """{"account":"org-account","warehouse":"COMPUTE_WH","database":"WAREHOUSE","schema":"ANALYTICS","role":"QUERY_ROLE"}""",
                """{"account":"org-account","warehouse":"COMPUTE_WH","database":"WAREHOUSE","schema":"ANALYTICS","role":"QUERY_ROLE"}"""),
            SettingsFixture(ConnectorProductType.TRINO,
                """{"host":"trino.internal","port":8443,"catalog":"warehouse","schema":"analytics","ssl":true,"sslVerification":"CA"}""",
                """{"host":"trino.internal","port":"8443","catalog":"warehouse","schema":"analytics","ssl":"true","sslVerification":"CA"}""",
                """{"host":"trino.internal","port":8443,"catalog":"warehouse","schema":"analytics","ssl":"true","ssl_verification":"CA"}"""),
            SettingsFixture(ConnectorProductType.REDSHIFT,
                """{"host":"redshift.internal","port":5439,"database":"warehouse","schema":"analytics","sslMode":"VERIFY_CA"}""",
                """{"host":"redshift.internal","port":"5439","database":"warehouse","schema":"analytics","sslMode":"verify-ca"}""",
                """{"host":"redshift.internal","port":5439,"database":"warehouse","schema":"analytics","ssl_mode":"verify-ca"}"""),
            SettingsFixture(ConnectorProductType.DATABRICKS,
                """{"host":"workspace.cloud.databricks.com","warehouseId":"abc","catalog":"warehouse","schema":"analytics"}""",
                """{"host":"workspace.cloud.databricks.com","warehouseId":"abc","catalog":"warehouse","schema":"analytics"}""",
                """{"host":"workspace.cloud.databricks.com","warehouse_id":"abc","catalog":"warehouse","schema":"analytics"}"""),
        )
        assertEquals(ConnectorProductType.entries.toSet(), fixtures.map { it.type }.toSet())
        fixtures.forEach { fixture ->
            val properties = ConnectorSettingsCodec.decode(fixture.type, json(fixture.publicJson))
            assertEquals(json(fixture.publicJson), ConnectorSettingsCodec.encode(properties), fixture.type.name)
            assertEquals(strings(fixture.storageJson), properties.storageValues(), fixture.type.name)
            assertEquals(properties, connectorPropertiesFromStorage(fixture.type, strings(fixture.storageJson)))
            val binding = ConnectorExecutionBinding(ConnectorId.generate(), WorkspaceId(Uuid.generateV7()),
                fixture.type.name.lowercase(), "Warehouse", properties.storageValues(),
                CredentialEnvelopeReference(CredentialVersionId.generate(), "test/key", ContentDigest("sha256:" + "a".repeat(64))),
                Instant.parse("2026-09-07T00:00:00Z"))
            val projected = ConnectorRuntimeBindingProjector().project(binding)
            val expected = json(fixture.runtimeJson).mapValues { (_, value) ->
                if (value.jsonPrimitive.isString) ConnectorRuntimePropertyValue.Text(value.jsonPrimitive.content)
                else ConnectorRuntimePropertyValue.Integer(value.jsonPrimitive.long)
            }
            assertEquals(fixture.type.name, projected.connectorType.name)
            assertEquals(expected, projected.properties, fixture.type.name)
        }
    }

    @Test
    fun `every credential variant survives encrypted document format and normalizes only for allowed types`() {
        val fixtures = listOf(
            CredentialFixture("""{"kind":"SAMPLE_DATASET_ACCESS","dataset":"acme-cloud@1"}""",
                """{"dataset":"acme-cloud@1"}""", """{"dataset":"acme-cloud@1"}""", setOf(ConnectorProductType.DUCKDB)),
            CredentialFixture("""{"kind":"SAMPLE_DATASET_ACCESS","dataset":"acme-cloud@2"}""",
                """{"dataset":"acme-cloud@2"}""", """{"dataset":"acme-cloud@2"}""", setOf(ConnectorProductType.DUCKDB)),
            CredentialFixture("""{"kind":"SAMPLE_DATASET_ACCESS","dataset":"acme-cloud@3"}""",
                """{"dataset":"acme-cloud@3"}""", """{"dataset":"acme-cloud@3"}""", setOf(ConnectorProductType.DUCKDB)),
            CredentialFixture("""{"kind":"USERNAME_PASSWORD","username":"user","password":"password-secret"}""",
                """{"username":"user","password":"password-secret"}""",
                """{"username":"user","password":"password-secret"}""",
                setOf(ConnectorProductType.POSTGRESQL, ConnectorProductType.TRINO, ConnectorProductType.SNOWFLAKE, ConnectorProductType.REDSHIFT)),
            CredentialFixture("""{"kind":"SERVICE_ACCOUNT_JSON","serviceAccountJson":"{\"private_key\":\"json-secret\"}"}""",
                """{"service_account_json":"{\"private_key\":\"json-secret\"}"}""",
                """{"service_account_json":"{\"private_key\":\"json-secret\"}"}""", setOf(ConnectorProductType.BIGQUERY)),
            CredentialFixture("""{"kind":"USERNAME_PRIVATE_KEY","username":"user","privateKey":"pem-secret","privateKeyPassphrase":"phrase-secret"}""",
                """{"username":"user","privateKey":"pem-secret","privateKeyPassphrase":"phrase-secret"}""",
                """{"username":"user","private_key":"pem-secret","private_key_passphrase":"phrase-secret"}""", setOf(ConnectorProductType.SNOWFLAKE)),
            CredentialFixture("""{"kind":"AWS_IAM","awsAccessKeyId":"access-secret","awsSecretAccessKey":"key-secret","awsRegion":"us-east-1","clusterId":"cluster","dbUser":"user"}""",
                """{"awsAccessKeyId":"access-secret","awsSecretAccessKey":"key-secret","awsRegion":"us-east-1","clusterId":"cluster","dbUser":"user"}""",
                """{"aws_access_key_id":"access-secret","aws_secret_access_key":"key-secret","aws_region":"us-east-1","cluster_id":"cluster","db_user":"user"}""", setOf(ConnectorProductType.REDSHIFT)),
            CredentialFixture("""{"kind":"ACCESS_TOKEN","accessToken":"token-secret"}""",
                """{"accessToken":"token-secret"}""", """{"access_token":"token-secret"}""", setOf(ConnectorProductType.DATABRICKS)),
            CredentialFixture("""{"kind":"OAUTH_CLIENT","oauthClientId":"client","oauthClientSecret":"oauth-secret"}""",
                """{"oauthClientId":"client","oauthClientSecret":"oauth-secret"}""",
                """{"oauth_client_id":"client","oauth_client_secret":"oauth-secret"}""", setOf(ConnectorProductType.DATABRICKS)),
        )
        assertEquals(7, fixtures.map { json(it.publicJson).getValue("kind") }.toSet().size)
        fixtures.forEach { fixture ->
            val credential = ConnectorCredentialCodec.decode(json(fixture.publicJson))
            if (credential !is ConnectorCredential.SampleDatasetAccess) assertEquals("[REDACTED]", credential.toString())
            credential.toMaterial().use { material ->
                material.useFields { fields -> assertEquals(strings(fixture.materialJson), fields.mapValues { it.value.decodeToString() }) }
                val document = ManagedCredentialDocumentCodec.encode(material, 1_048_576, 16, 64, 262_144)
                try {
                    ManagedCredentialDocumentCodec.decode(document, 16, 64, 262_144).use { restored ->
                        restored.useFields { fields -> assertEquals(strings(fixture.materialJson), fields.mapValues { it.value.decodeToString() }) }
                        ConnectorProductType.entries.forEach { type ->
                            if (type in fixture.allowedTypes) {
                                credential.requireCompatible(type)
                                restored.normalizedForExecution(type.name.lowercase()).use { normalized ->
                                    normalized.useFields { fields -> assertEquals(strings(fixture.runtimeJson), fields.mapValues { it.value.decodeToString() }) }
                                }
                            } else {
                                assertFailsWith<IllegalArgumentException> { credential.requireCompatible(type) }
                                assertFailsWith<IllegalArgumentException> { restored.requireCompatible(type.name.lowercase()) }
                            }
                        }
                    }
                } finally { document.fill(0) }
            }
        }
    }

    @Test
    fun `unsupported sample version fails public storage and credential documents`() {
        assertFailsWith<IllegalArgumentException> {
            ConnectorSettingsCodec.decode(ConnectorProductType.DUCKDB, json("""{"sampleDataset":"acme-cloud@4"}"""))
        }
        assertFailsWith<IllegalArgumentException> {
            connectorPropertiesFromStorage(ConnectorProductType.DUCKDB, mapOf("sample_dataset" to "acme-cloud@4"))
        }
        assertFailsWith<IllegalArgumentException> {
            ConnectorCredentialCodec.decode(json("""{"kind":"SAMPLE_DATASET_ACCESS","dataset":"acme-cloud@4"}"""))
        }
        CredentialMaterial.copyOf(mapOf("dataset" to "acme-cloud@4".toByteArray())).use { material ->
            assertFailsWith<IllegalArgumentException> { material.requireCompatible("duckdb") }
        }
    }

    private data class SettingsFixture(val type: ConnectorProductType, val publicJson: String, val storageJson: String, val runtimeJson: String)
    private data class CredentialFixture(val publicJson: String, val materialJson: String, val runtimeJson: String, val allowedTypes: Set<ConnectorProductType>)
    private fun json(value: String): JsonObject = Json.parseToJsonElement(value).jsonObject
    private fun strings(value: String): Map<String, String> = json(value).mapValues { it.value.jsonPrimitive.content }
}
