package cc.monomer.execution.driver.spi

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ExecutionIdentifiersTest {
    private val generatedIdentityParsers =
        listOf<(String) -> String>(
            { RunId.parse(it).toString() },
            { ConnectorId.parse(it).toString() },
        )

    @Test
    fun `generated identities accept canonical IETF UUID v7 and normalize wire case`() {
        val canonicalValue = "019f0000-0000-7000-8000-000000000001"
        val uppercaseWireValue = canonicalValue.uppercase()

        generatedIdentityParsers.forEach { parse ->
            assertEquals(canonicalValue, parse(uppercaseWireValue))
        }
    }

    @Test
    fun `generated identities reject non-v7 nil and non-IETF UUIDs`() {
        val uuidV4 = "550e8400-e29b-41d4-a716-446655440000"
        val nilUuid = "00000000-0000-0000-0000-000000000000"
        val nonIetfUuidV7 = "019f0000-0000-7000-0000-000000000001"

        generatedIdentityParsers.forEach { parse ->
            assertFailsWith<IllegalArgumentException> { parse(uuidV4) }
            assertFailsWith<IllegalArgumentException> { parse(nilUuid) }
            assertFailsWith<IllegalArgumentException> { parse(nonIetfUuidV7) }
        }
    }

    @Test
    fun `workspace identity accepts protected v4 but rejects nil and non-IETF UUIDs`() {
        val protectedWorkspaceId = "c6b4f007-0770-424a-a320-f94dcc8cd474"

        assertEquals(
            protectedWorkspaceId,
            WorkspaceId.parse(protectedWorkspaceId.uppercase()).toString(),
        )
        assertFailsWith<IllegalArgumentException> {
            WorkspaceId.parse("00000000-0000-0000-0000-000000000000")
        }
        assertFailsWith<IllegalArgumentException> {
            WorkspaceId.parse("550e8400-e29b-41d4-0716-446655440000")
        }
    }
}
