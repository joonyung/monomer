package cc.monomer.identity.domain.model
import cc.monomer.identity.api.WorkspaceRole
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
class AuthorizationTest {
    @Test fun `roles are cumulative`() { for (held in WorkspaceRole.entries) for (required in WorkspaceRole.entries) assertEquals(held.ordinal >= required.ordinal, held.includes(required)) }
    @Test fun `revision cannot be negative`() { assertFailsWith<IllegalArgumentException> { AuthorizationRevision(-1) } }
}
