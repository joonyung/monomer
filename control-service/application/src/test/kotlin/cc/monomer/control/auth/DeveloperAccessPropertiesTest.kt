package cc.monomer.control.auth

import cc.monomer.identity.domain.model.DeveloperAccessPolicy
import kotlin.test.*
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.io.ClassPathResource

class DeveloperAccessPropertiesTest {
    @Test fun `application YAML binds open by default and explicit restricted configuration`() {
        val open = bind(emptyMap())
        assertEquals(DeveloperAccessPolicy.Mode.OPEN, open.mode)
        assertEquals(emptySet(), open.allowedEmails)
        val restricted = bind(mapOf("MONOMER_CONTROL_DEVELOPER_ACCESS_MODE" to "RESTRICTED"))
        assertEquals(DeveloperAccessPolicy.Mode.RESTRICTED, restricted.mode)
        assertEquals(emptySet(), restricted.allowedEmails)
        assertFalse(AuthApplicationComposition().developerAccessPolicy(restricted).isOpen)
        val allowed = bind(mapOf("MONOMER_CONTROL_DEVELOPER_ACCESS_MODE" to "RESTRICTED",
            "MONOMER_CONTROL_DEVELOPER_ALLOWED_EMAILS" to "first@gmail.com,second@gmail.com"))
        assertEquals(setOf("first@gmail.com", "second@gmail.com"), allowed.allowedEmails)
        AuthApplicationComposition().developerAccessPolicy(allowed)
        assertFailsWith<IllegalArgumentException> {
            AuthApplicationComposition().developerAccessPolicy(allowed.copy(allowedEmails = setOf("*@gmail.com")))
        }
    }

    private fun bind(settings: Map<String, String>): DeveloperAccessProperties {
        val environment = StandardEnvironment()
        environment.propertySources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME)
        environment.propertySources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME)
        environment.propertySources.addFirst(MapPropertySource("operator", settings))
        YamlPropertySourceLoader().load("application", ClassPathResource("application.yaml"))
            .forEach(environment.propertySources::addLast)
        return Binder.get(environment).bind("auth.developer-access", Bindable.of(DeveloperAccessProperties::class.java)).get()
    }
}
