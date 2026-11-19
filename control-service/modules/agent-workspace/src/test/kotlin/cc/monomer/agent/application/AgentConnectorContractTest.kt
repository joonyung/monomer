package cc.monomer.agent.application

import cc.monomer.agent.api.*
import cc.monomer.connector.api.ConnectorProductType
import kotlin.test.*

class AgentConnectorContractTest {
    private val decoder = AgentWorkspaceContractDecoder()
    private val resource = "workspace/connectors/01942b04-6e00-7000-8000-000000000001"
    private val create = """{"path":"workspace/connectors","input":{"kind":"CONNECTOR_CREATE","connectorType":"POSTGRESQL","displayName":"Warehouse","properties":{"host":"warehouse.example.com","port":5432,"database":"warehouse","sslMode":"REQUIRE"},"credential":{"kind":"USERNAME_PASSWORD","username":"reader","password":"fixture-only"}}}"""

    @Test
    fun `create decodes typed owner values and rejects secret and type drift`() {
        val input = assertIs<AgentCreateInput.Connector>(decoder.create(create).value.input)
        assertEquals(ConnectorProductType.POSTGRESQL, input.connectorType)
        for (invalid in listOf(
            create.replace("5432", "\"5432\""),
            create.replace("5432", "0"),
            create.replace("\"REQUIRE\"", "true"),
            create.replace("\"host\":", "\"extra\":1,\"host\":"),
            create.replace("\"password\":\"fixture-only\"", "\"password\":null"),
            create.replace("\"password\":\"fixture-only\"", "\"password\":\"\""),
            create.replace("\"POSTGRESQL\"", "\"BIGQUERY\""),
        )) assertFailsWith<AgentWorkspaceRequestDecodingException> { decoder.create(invalid) }
    }

    @Test
    fun `settings edit excludes credentials identity and public revision`() {
        val patch = """{"path":"$resource","patch":[{"op":"replace","path":"/displayName","value":"Renamed"}]}"""
        assertEquals("/displayName", decoder.edit(patch).value.patch.single().path)
        for (invalid in listOf(
            patch.replace("/displayName", "/credential"), patch.replace("/displayName", "/connectorType"),
            patch.replace("/displayName", "/authorityRevision"), patch.replace("\"patch\":", "\"revision\":null,\"patch\":"),
            patch.replace("\"patch\":", "\"revision\":\"1\",\"patch\":"),
            patch.replace("\"/displayName\"", "\"\""),
        )) assertFailsWith<AgentWorkspaceRequestDecodingException> { decoder.edit(invalid) }
    }

    @Test
    fun `credential rotation is exact and write only`() {
        val request = """{"path":"$resource","action":"rotate-credentials","input":{"kind":"CONNECTOR_CREDENTIAL_ROTATE","credential":{"kind":"USERNAME_PASSWORD","username":"reader","password":"fixture-only"}}}"""
        assertIs<AgentInvokeInput.ConnectorCredentialRotate>(decoder.invoke(request).value.input)
        for (invalid in listOf(request.replace("rotate-credentials", "test"), request.replace(resource, "workspace/connectors"),
            request.replace("\"credential\":", "\"connectorType\":\"POSTGRESQL\",\"credential\":"),
            request.replace(",\"password\":\"fixture-only\"", ""))) {
            assertFailsWith<AgentWorkspaceRequestDecodingException> { decoder.invoke(invalid) }
        }
    }
}
