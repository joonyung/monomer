package cc.monomer.control

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.io.path.readText

class ControlSourcePurityTest {
    private val controlRoot = Path.of(System.getProperty("control.service.root"))
    private val moduleMap = ObjectMapper().readTree(
        controlRoot.resolve("../contracts/architecture/module-ownership.json").toFile(),
    )
    private val executionProtocolImportRules =
        moduleMap.path("executionProtocolImportRules").associate { rule ->
            rule.path("gradlePath").asText() to
                rule.path("sourcePaths").map { sourcePath -> sourcePath.asText() }
        }

    @Test
    fun `bounded contexts import only declared public packages`() {
        val modules = moduleMap.path("modules").map { module ->
            ModuleContract(
                id = module.path("id").asText(),
                gradlePath = module.path("gradlePath").asText(),
                packageName = module.path("package").asText(),
                publicPackage = module.path("publicPackage").asText(),
                dependencies = module.path("allowedDependencies")
                    .map { dependency -> dependency.asText() }
                    .toSet(),
            )
        }
        val violations = mutableListOf<String>()
        val importPattern = Regex("""(?m)^\s*import\s+([A-Za-z_][A-Za-z0-9_.]*)""")
        val executionProtocolPackage = "cc.monomer.execution.protocol.v1"

        modules.forEach { ownerModule ->
            val moduleRoot = controlRoot.resolve(
                ownerModule.gradlePath.removePrefix(":").replace(":", "/"),
            )
            sourceFiles(moduleRoot).forEach { sourceFile ->
                importPattern.findAll(sourceFile.readText()).forEach { match ->
                    val importedPackage = match.groupValues[1]
                    val targetModule = modules
                        .filter { candidate ->
                            importedPackage == candidate.packageName ||
                                importedPackage.startsWith(candidate.packageName + ".")
                        }
                        .maxByOrNull { candidate -> candidate.packageName.length }
                    if (targetModule != null && targetModule.id != ownerModule.id) {
                        if (targetModule.id !in ownerModule.dependencies) {
                            violations +=
                                "${moduleRoot.relativize(sourceFile)} imports undeclared ${targetModule.id}"
                        }
                        if (
                            importedPackage != targetModule.publicPackage &&
                            !importedPackage.startsWith(targetModule.publicPackage + ".")
                        ) {
                            violations +=
                                "${moduleRoot.relativize(sourceFile)} imports internal $importedPackage"
                        }
                    }
                }
            }
        }

        val sourceProjectPaths = buildList {
            add(moduleMap.path("applicationModule").asText())
            addAll(modules.map(ModuleContract::gradlePath))
            addAll(
                moduleMap.path("auxiliaryRuntimes")
                    .map { runtime -> runtime.path("gradlePath").asText() },
            )
        }
        sourceProjectPaths
            .forEach { projectPath ->
                val projectRoot = controlRoot.resolve(
                    projectPath.removePrefix(":").replace(":", "/"),
                )
                sourceFiles(projectRoot).forEach { sourceFile ->
                    importPattern.findAll(sourceFile.readText()).forEach { match ->
                        val importedPackage = match.groupValues[1]
                        if (
                            (
                                importedPackage == executionProtocolPackage ||
                                    importedPackage.startsWith("$executionProtocolPackage.")
                            ) &&
                            !allowsExecutionProtocolImport(
                                projectPath,
                                projectRoot,
                                sourceFile,
                            )
                        ) {
                            violations +=
                                "$projectPath:${projectRoot.relativize(sourceFile)} " +
                                "imports execution protocol infrastructure"
                        }
                    }
                }
            }

        assertThat(violations).isEmpty()
    }

    @Test
    fun `frozen topology keeps owner dependencies at public boundaries`() {
        val dependenciesByModuleId = moduleMap.path("modules").associate { module ->
            module.path("id").asText() to module.path("allowedDependencies")
                .map { dependency -> dependency.asText() }
                .toSet()
        }
        val frozenDependencies = listOf(
            FrozenModuleDependency(
                dependentModuleId = "explore",
                dependencyModuleId = "catalog",
                isRequired = false,
            ),
            FrozenModuleDependency(
                dependentModuleId = "execution-runtime",
                dependencyModuleId = "catalog",
                isRequired = false,
            ),
            FrozenModuleDependency(
                dependentModuleId = "execution-runtime",
                dependencyModuleId = "resource-authority",
                isRequired = false,
            ),
        )

        val violations = frozenDependencies.mapNotNull { frozenDependency ->
            val actualDependencies = dependenciesByModuleId[frozenDependency.dependentModuleId]
                ?: return@mapNotNull "Missing module ${frozenDependency.dependentModuleId}"
            val isPresent = frozenDependency.dependencyModuleId in actualDependencies
            if (isPresent == frozenDependency.isRequired) {
                null
            } else {
                val expectedRelation = if (frozenDependency.isRequired) "declare" else "not declare"
                "${frozenDependency.dependentModuleId} must $expectedRelation " +
                    "${frozenDependency.dependencyModuleId}"
            }
        }

        assertThat(violations).isEmpty()
    }

    @Test
    fun `pure layers and protocol source remain infrastructure free`() {
        val forbiddenPureLayerImports = listOf(
            "org.springframework.",
            "java.sql.",
            "javax.sql.",
            "io.grpc.",
            "com.google.protobuf.",
            "cc.monomer.execution.protocol.v1.",
        )
        val importPattern = Regex("""(?m)^\s*import\s+([A-Za-z_][A-Za-z0-9_.]*)""")
        val violations = moduleMap.path("modules").flatMap { module ->
            val moduleRoot = controlRoot.resolve(
                module.path("gradlePath").asText().removePrefix(":").replace(":", "/"),
            )
            sourceFiles(moduleRoot)
                .filter { sourceFile ->
                    val relativePath = moduleRoot.relativize(sourceFile).toString()
                    "/domain/" in "/$relativePath" || "/application/" in "/$relativePath"
                }
                .flatMap { sourceFile ->
                    importPattern.findAll(sourceFile.readText())
                        .map { match -> match.groupValues[1] }
                        .filter { importedPackage ->
                            forbiddenPureLayerImports.any(importedPackage::startsWith)
                        }
                        .map { importedPackage ->
                            "${moduleRoot.relativize(sourceFile)} imports $importedPackage"
                        }
                        .toList()
                }
        }

        assertThat(violations).isEmpty()

        val copiedProtoFiles = Files.walk(controlRoot).use { paths ->
            paths
                .filter { path ->
                    path.extension == "proto" &&
                        path.none { component -> component.name == "build" || component.name == ".gradle" }
                }
                .toList()
        }
        assertThat(copiedProtoFiles).isEmpty()
        assertThat(controlRoot.resolve("settings.gradle.kts").readText())
            .doesNotContain("include" + "Build(")
    }

    @Test
    fun `execution composition contains no retired owner completion vocabulary`() {
        val applicationRoot = controlRoot.resolve("application")
        val retiredTerms =
            listOf(
                "Owner" + "Completion",
                "owner" + "Completion",
            )
        val violations =
            sourceFiles(applicationRoot).flatMap { sourceFile ->
                val source = sourceFile.readText()
                retiredTerms
                    .filter(source::contains)
                    .map { retiredTerm ->
                        "${applicationRoot.relativize(sourceFile)} contains $retiredTerm"
                    }
            }

        assertThat(violations).isEmpty()
    }

    private fun sourceFiles(moduleRoot: Path): List<Path> {
        val sourceRoot = moduleRoot.resolve("src")
        if (!sourceRoot.isDirectory()) {
            return emptyList()
        }
        return Files.walk(sourceRoot).use { paths ->
            paths
                .filter { path -> path.extension == "kt" || path.extension == "java" }
                .toList()
        }
    }

    private fun allowsExecutionProtocolImport(
        projectPath: String,
        projectRoot: Path,
        sourceFile: Path,
    ): Boolean {
        val relativePath = projectRoot.relativize(sourceFile).toString().replace('\\', '/')
        return executionProtocolImportRules[projectPath].orEmpty().any { allowedPath ->
            if (allowedPath.endsWith("/")) {
                relativePath.startsWith(allowedPath)
            } else {
                relativePath == allowedPath
            }
        }
    }

    private data class ModuleContract(
        val id: String,
        val gradlePath: String,
        val packageName: String,
        val publicPackage: String,
        val dependencies: Set<String>,
    )

    private data class FrozenModuleDependency(
        val dependentModuleId: String,
        val dependencyModuleId: String,
        val isRequired: Boolean,
    )
}
