package cc.monomer.control

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.io.path.readText

class ControlModuleTopologyTest {
    private val controlRoot = Path.of(System.getProperty("control.service.root"))
    private val moduleMap = ObjectMapper().readTree(
        controlRoot.resolve("../contracts/architecture/module-ownership.json").toFile(),
    )

    @Test
    fun `Gradle projects and direct dependencies exactly match the locked module map`() {
        val modules = moduleMap.path("modules").associateBy { module -> module.path("id").asText() }
        val auxiliaryPaths = moduleMap.path("auxiliaryRuntimes")
            .map { runtime -> runtime.path("gradlePath").asText() }
            .toSet()
        val expectedProjectPaths = modules.values
            .map { module -> module.path("gradlePath").asText() }
            .toSet() + auxiliaryPaths + moduleMap.path("applicationModule").asText()
        val settingsSource = controlRoot.resolve("settings.gradle.kts").readText()
        val declaredProjectPaths = Regex("""["'](:[a-z][a-z0-9:-]+)["']""")
            .findAll(settingsSource)
            .map { match -> match.groupValues[1] }
            .toSet()

        assertThat(declaredProjectPaths).isEqualTo(expectedProjectPaths)

        val gradlePathByModuleId = modules.mapValues { (_, module) ->
            module.path("gradlePath").asText()
        }
        modules.values.forEach { module ->
            val gradlePath = module.path("gradlePath").asText()
            val buildSource = controlRoot
                .resolve(gradlePath.removePrefix(":").replace(":", "/"))
                .resolve("build.gradle.kts")
                .readText()
            val actualDependencies = Regex("""project\("(:modules:[a-z0-9-]+)"\)""")
                .findAll(buildSource)
                .map { match -> match.groupValues[1] }
                .toSet()
            val expectedDependencies = module.path("allowedDependencies")
                .map { dependency -> gradlePathByModuleId.getValue(dependency.asText()) }
                .toSet()

            assertThat(actualDependencies)
                .describedAs("$gradlePath direct project dependencies")
                .isEqualTo(expectedDependencies)
        }

        requireAcyclic(
            modules.mapValues { (_, module) ->
                module.path("allowedDependencies").map(JsonNode::asText).toSet()
            },
        )
    }

    @Test
    fun `canonical apply order is the dependency-valid phase one order`() {
        val expectedOrder = listOf(
            "identity-access",
            "resource-authority",
            "execution-runtime",
            "connector",
            "catalog",
            "semantic",
            "explore",
            "dashboard",
            "pages",
            "agent-workspace",
            "search",
        )
        val actualOrder = moduleMap.path("canonicalApplyOrder").map(JsonNode::asText)

        assertThat(actualOrder).isEqualTo(expectedOrder)

        val applyIndexByModule = actualOrder
            .withIndex()
            .associate { indexedValue -> indexedValue.value to indexedValue.index }
        moduleMap.path("modules").forEach { module ->
            val moduleId = module.path("id").asText()
            module.path("allowedDependencies").forEach { dependency ->
                assertThat(applyIndexByModule.getValue(dependency.asText()))
                    .isLessThan(applyIndexByModule.getValue(moduleId))
            }
        }
    }

    private fun requireAcyclic(graph: Map<String, Set<String>>) {
        val visiting = mutableSetOf<String>()
        val visited = mutableSetOf<String>()

        fun visit(moduleId: String) {
            check(moduleId !in visiting) { "cycle at $moduleId" }
            if (moduleId in visited) {
                return
            }
            visiting += moduleId
            graph.getValue(moduleId).forEach(::visit)
            visiting -= moduleId
            visited += moduleId
        }

        graph.keys.forEach(::visit)
    }
}
