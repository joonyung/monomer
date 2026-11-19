package cc.monomer.control.auth.inbound.local

import cc.monomer.identity.application.login.LoginUseCase
import cc.monomer.identity.application.session.StartSessionUseCase
import cc.monomer.control.auth.inbound.oauth.OAuthProperties
import cc.monomer.control.auth.inbound.session.RefreshCookieFactory
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class LocalLoginConfigurationTest {
    private val contextRunner = ApplicationContextRunner()
        .withUserConfiguration(
            LocalLoginConfiguration::class.java,
            LocalLoginController::class.java,
        )
        .withBean(LoginUseCase::class.java, { mockk() })
        .withBean(OAuthProperties::class.java, { mockk() })
        .withBean(StartSessionUseCase::class.java, { mockk() })
        .withBean(RefreshCookieFactory::class.java, { mockk() })

    @Test
    fun `local profile and loopback address expose local login`() {
        contextRunner
            .withPropertyValues(
                "spring.profiles.active=local",
                "server.address=127.0.0.1",
                "auth.local-login.enabled=true",
                "auth.local-login.subject=monomer-local-dev",
                "auth.local-login.email=dev@local.monomer",
                "auth.local-login.display-name=Monomer Dev",
            )
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).hasSingleBean(LocalLoginController::class.java)
            }
    }

    @Test
    fun `non-local profile cannot expose local login`() {
        contextRunner
            .withPropertyValues(
                "spring.profiles.active=dev",
                "server.address=0.0.0.0",
                "auth.local-login.enabled=true",
            )
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).doesNotHaveBean(LocalLoginController::class.java)
            }
    }

    @Test
    fun `local login refuses non-loopback server address`() {
        contextRunner
            .withPropertyValues(
                "spring.profiles.active=local",
                "server.address=0.0.0.0",
                "auth.local-login.enabled=true",
                "auth.local-login.subject=monomer-local-dev",
                "auth.local-login.email=dev@local.monomer",
                "auth.local-login.display-name=Monomer Dev",
            )
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure)
                    .hasRootCauseMessage("local login requires server.address to be an explicit loopback address")
            }
    }

    @Test
    fun `local login refuses missing server address`() {
        contextRunner
            .withPropertyValues(
                "spring.profiles.active=local",
                "auth.local-login.enabled=true",
                "auth.local-login.subject=monomer-local-dev",
                "auth.local-login.email=dev@local.monomer",
                "auth.local-login.display-name=Monomer Dev",
            )
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure)
                    .hasRootCauseMessage("local login requires server.address to be an explicit loopback address")
            }
    }
}
