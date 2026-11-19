package cc.monomer.control.human

import cc.monomer.connector.api.ConnectorWorkspaceReadUseCase
import cc.monomer.connector.api.ConnectorId
import cc.monomer.identity.api.WorkspaceId
import cc.monomer.control.connector.application.ConnectorNotFoundException
import cc.monomer.control.connector.application.ConnectorReadUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlin.uuid.Uuid
import kotlin.test.fail
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.Bean

class ConnectorCatalogApplicationCompositionTest {
    @Test
    fun `connector reads compose from the durable owner API without mutation runtime`() = runBlocking {
        val connectorWorkspaceReadUseCase: ConnectorWorkspaceReadUseCase = mockk()
        val workspaceId = "019e0000-0000-7000-8000-0000000000a1"
        val connectorId = "019e0000-0000-7000-8000-0000000000b1"
        coEvery {
            connectorWorkspaceReadUseCase.listByWorkspace(WorkspaceId(Uuid.parse(workspaceId)))
        } returns emptyList()
        coEvery {
            connectorWorkspaceReadUseCase.findByWorkspaceAndId(
                WorkspaceId(Uuid.parse(workspaceId)),
                ConnectorId(Uuid.parse(connectorId)),
            )
        } returns null

        val useCase = ConnectorCatalogApplicationComposition().connectorReadUseCase(connectorWorkspaceReadUseCase)

        assertThat(useCase).isInstanceOf(ConnectorReadUseCase::class.java)
        assertThat(useCase.listByWorkspace(workspaceId)).isEmpty()
        coVerify {
            connectorWorkspaceReadUseCase.listByWorkspace(WorkspaceId(Uuid.parse(workspaceId)))
        }
        try {
            useCase.get(workspaceId, connectorId)
            fail("missing scoped connector must not resolve")
        } catch (_: ConnectorNotFoundException) {
        }
        coVerify {
            connectorWorkspaceReadUseCase.findByWorkspaceAndId(
                WorkspaceId(Uuid.parse(workspaceId)),
                ConnectorId(Uuid.parse(connectorId)),
            )
        }

        val beanMethods =
            ConnectorCatalogApplicationComposition::class.java.declaredMethods
                .filter { method -> method.isAnnotationPresent(Bean::class.java) }
        val readUseCaseMethod = beanMethods.single { method -> method.name == "connectorReadUseCase" }

        assertThat(readUseCaseMethod.parameterTypes).containsExactly(ConnectorWorkspaceReadUseCase::class.java)
        assertThat(beanMethods.map { method -> method.name })
            .doesNotContain("connectorRegistryService", "connectorCredentialCipher", "connectorClock")
        assertThat(beanMethods.flatMap { method -> method.parameterTypes.asList() }.map(Class<*>::getName))
            .doesNotContain(
                "cc.monomer.connector.application.ConnectorRegistryService",
                "cc.monomer.connector.domain.connector.QueryConnector",
            )
        listOf(
            "cc.monomer.connector.application.ConnectorRegistryService",
            "cc.monomer.connector.domain.authorization.ConnectorMutationAuthorizationPort",
            "cc.monomer.connector.domain.connector.CredentialCipher",
            "cc.monomer.connector.domain.connector.QueryConnector",
            "cc.monomer.control.human.authorization.InProcessConnectorMutationAuthorization",
        ).forEach { className ->
            assertThat(runCatching { Class.forName(className) }.exceptionOrNull())
                .isInstanceOf(ClassNotFoundException::class.java)
        }
    }
}
