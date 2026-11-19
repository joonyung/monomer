package cc.monomer.identity.application.me

import cc.monomer.identity.domain.id.UserId
import cc.monomer.identity.domain.model.User
import cc.monomer.identity.domain.port.TransactionRunner
import cc.monomer.identity.domain.port.UserRepository
import io.mockk.Called
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class UpdateMyDisplayNameServiceTest {
    private val users = mockk<UserRepository>()
    private val transactions = object : TransactionRunner {
        override suspend fun <T> run(action: () -> T): T = action()
        override suspend fun <T> readOnly(action: () -> T): T = action()
    }
    private val service = UpdateMyDisplayNameService(users, transactions)

    @Test
    fun `trims name and changes only the actor name`() = runTest {
        val user = User(UserId.generate(), "alice@example.test", "Alice")
        every { users.findByIdForUpdate(user.userId) } returns user
        every { users.updateDisplayName(user.userId, "김 사용자") } returns Unit

        assertThat(service.update(user.userId, "  김 사용자  "))
            .isEqualTo(UpdateMyDisplayNameService.Outcome.Updated(user.copy(displayName = "김 사용자")))
        verify(exactly = 1) { users.updateDisplayName(user.userId, "김 사용자") }
    }

    @Test
    fun `blank name is rejected before persistence`() = runTest {
        for (name in listOf("", " ", "\t\n", "\u2003")) {
            assertThat(service.update(UserId.generate(), name))
                .isEqualTo(UpdateMyDisplayNameService.Outcome.InvalidDisplayName)
        }
        verify { users wasNot Called }
    }

    @Test
    fun `missing actor is not created or updated`() = runTest {
        every { users.findByIdForUpdate(any()) } returns null
        assertThat(service.update(UserId.generate(), "Name"))
            .isEqualTo(UpdateMyDisplayNameService.Outcome.UserNotFound)
        verify(exactly = 0) { users.updateDisplayName(any(), any()) }
    }
}
