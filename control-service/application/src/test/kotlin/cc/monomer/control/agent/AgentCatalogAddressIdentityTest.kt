@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.control.agent

import cc.monomer.agent.api.AgentResourcePath
import cc.monomer.catalog.api.CatalogObjectKind
import cc.monomer.catalog.api.CatalogStableObjectIdentityCodec
import cc.monomer.connector.api.ConnectorId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

class AgentCatalogAddressIdentityTest {
    @Test
    fun `readable addresses retain frozen original name UUIDv8 identities`() {
        val connector = ConnectorId(Uuid.parse("00112233-4455-7788-99aa-bbccddeeff00"))
        val vectors = listOf(
            "한글 😀/a~1b%2F" to "85991c22-d101-8db2-993c-ffa3300744e9",
            "a|b" to "a1a67219-fe42-87a6-a619-bffe144da404",
            "." to "22e5e166-2abb-8275-9d31-558c8129adb0",
            ".." to "5e792d8d-9c52-8af8-b80b-5bc122d90c45",
            "option-refreshes" to "dc7cf7c6-6e83-88e4-ac9c-b59f0d432a4c",
            "a/b" to "c7ae8181-1ef1-8c93-9d62-c7f80279d7ec",
            "a~1b" to "fda4793a-9847-8f00-968e-3504de841469",
            "%2F" to "5702642b-a2f7-8e32-8197-00da8c3c9648",
        )
        val identities = vectors.map { (name, expected) ->
            val path = AgentResourcePath.parse("workspace/connectors/${connector.value}/catalog/catalogs/Analytics/schemas/Public/relations/${AgentResourcePath.encodeNameSegment(name)}")
            val components = path.value.split('/')
            val identity = CatalogStableObjectIdentityCodec.derive(connector, CatalogObjectKind.RELATION,
                AgentResourcePath.decodeNameSegment(components[5]), AgentResourcePath.decodeNameSegment(components[7]),
                AgentResourcePath.decodeNameSegment(components[9]), null, null)
            assertEquals(expected, identity.objectId.toString())
            identity.objectId
        }
        assertEquals(vectors.size, identities.distinct().size)
    }
}
