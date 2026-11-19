package cc.monomer.control.auth

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class AuthDeploymentConfigurationTest {
    private val contextRunner = ApplicationContextRunner()
        .withUserConfiguration(AuthDeploymentConfiguration::class.java)
        .withPropertyValues(
            "monomer.control.public-api.web-origin=https://app.example.com",
            "monomer.control.oauth.issuer=https://api.example.com",
            "monomer.control.oauth.mcp-resource=https://api.example.com/mcp",
            "auth.oauth.frontend-callback-url=https://app.example.com/auth/callback",
            "auth.cors.allowed-origins=https://app.example.com",
            "auth.refresh-cookie.secure=true",
            "auth.google-oauth.enabled=true",
            "auth.google-oauth.redirect-uri=https://api.example.com/login/oauth2/code/google",
        )

    @Test
    fun `deployment configuration starts with explicit HTTPS origins and secure refresh cookie`() {
        contextRunner.run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context).hasSingleBean(AuthDeploymentConfiguration::class.java)
        }
    }

    @Test
    fun `local profile preserves HTTP defaults and templated Google redirect`() {
        contextRunner.withPropertyValues(
            "spring.profiles.active=local",
            "monomer.control.public-api.web-origin=http://localhost:3100",
            "auth.oauth.frontend-callback-url=http://localhost:3100/auth/callback",
            "auth.cors.allowed-origins=http://localhost:3100",
            "auth.refresh-cookie.secure=false",
            "auth.local-login.enabled=true",
            "auth.google-oauth.redirect-uri={baseUrl}/login/oauth2/code/{registrationId}",
        ).run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context).doesNotHaveBean(AuthDeploymentConfiguration::class.java)
        }
    }

    @Test
    fun `disabled Google does not require an active provider redirect configuration`() {
        contextRunner.withPropertyValues(
            "auth.google-oauth.enabled=false",
            "auth.google-oauth.redirect-uri={baseUrl}/login/oauth2/code/{registrationId}",
        ).run { context -> assertThat(context).hasNotFailed() }
    }

    @ParameterizedTest
    @ValueSource(strings = [
        "monomer.control.oauth.issuer=http://localhost:11077",
        "monomer.control.oauth.mcp-resource=http://localhost:11077/mcp",
        "monomer.control.oauth.mcp-resource=https://other.example.com/mcp",
    ])
    fun `disabled Google still requires secure coherent Agent OAuth endpoints`(property: String) {
        contextRunner.withPropertyValues("auth.google-oauth.enabled=false", property).run { context ->
            assertThat(context).hasFailed()
            assertThat(context.startupFailure).hasRootCauseInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = [
        "auth.refresh-cookie.secure=false",
        "auth.local-login.enabled=true",
        "monomer.control.public-api.web-origin=http://app.example.com",
        "monomer.control.public-api.web-origin=https://APP.example.com",
        "monomer.control.public-api.web-origin=https://app.example.com:443",
        "monomer.control.public-api.web-origin=https://user:secret@app.example.com",
        "auth.oauth.frontend-callback-url=https://other.example.com/auth/callback",
        "auth.oauth.frontend-callback-url=https://app.example.com/wrong",
        "auth.oauth.frontend-callback-url=https://app.example.com/auth/callback?next=x",
        "auth.oauth.frontend-callback-url=https://app.example.com/auth/callback#fragment",
        "auth.oauth.frontend-callback-url=https://user:secret@app.example.com/auth/callback",
        "auth.oauth.frontend-callback-url=not-a-url",
        "auth.cors.allowed-origins=*",
        "auth.cors.allowed-origins=https://*.example.com",
        "auth.cors.allowed-origins=https://other.example.com",
        "auth.cors.allowed-origins=https://APP.example.com",
        "auth.cors.allowed-origins=https://app.example.com:443",
        "auth.cors.allowed-origins=https://app.example.com/",
        "auth.cors.allowed-origins=https://app.example.com,http://other.example.com",
        "auth.cors.allowed-origins=https://app.example.com,https://user:secret@other.example.com",
        "auth.cors.allowed-origins=https://app.example.com,",
        "auth.google-oauth.redirect-uri={baseUrl}/login/oauth2/code/{registrationId}",
        "auth.google-oauth.redirect-uri=http://api.example.com/login/oauth2/code/google",
        "auth.google-oauth.redirect-uri=https://other.example.com/login/oauth2/code/google",
        "auth.google-oauth.redirect-uri=https://api.example.com/wrong",
        "auth.google-oauth.redirect-uri=https://api.example.com/login/oauth2/code/google?code=x",
        "auth.google-oauth.redirect-uri=https://api.example.com/login/oauth2/code/google#fragment",
        "auth.google-oauth.redirect-uri=https://user:secret@api.example.com/login/oauth2/code/google",
        "auth.google-oauth.redirect-uri=https://api.example.com:99999/login/oauth2/code/google",
        "monomer.control.oauth.issuer=http://localhost:11077",
        "monomer.control.oauth.mcp-resource=http://localhost:11077/mcp",
        "monomer.control.oauth.mcp-resource=https://other.example.com/mcp",
    ])
    fun `invalid deployment configuration fails Spring startup`(property: String) {
        contextRunner.withPropertyValues(property).run { context ->
            assertThat(context).hasFailed()
            assertThat(context.startupFailure).hasRootCauseInstanceOf(IllegalArgumentException::class.java)
        }
    }
}
