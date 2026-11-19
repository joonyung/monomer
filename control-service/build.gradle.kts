import groovy.json.JsonSlurper
import org.gradle.api.Project
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.testing.Test
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

plugins {
    base
    kotlin("jvm") version "2.3.20" apply false
    kotlin("plugin.spring") version "2.3.20" apply false
    id("org.springframework.boot") version "4.0.7" apply false
    id("com.google.protobuf") version "0.9.5" apply false
}

group = "cc.monomer"
version = "0.1.0"

val structuralProjectPaths = setOf(":modules")

subprojects {
    group = rootProject.group
    version = rootProject.version

    if (path !in structuralProjectPaths) {
        apply(plugin = "org.jetbrains.kotlin.jvm")
        if (path != ":application") {
            apply(plugin = "java-library")
        }

        extensions.configure<KotlinJvmProjectExtension> {
            jvmToolchain(25)
            compilerOptions {
                jvmTarget.set(JvmTarget.JVM_25)
                apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_3)
                languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_3)
                freeCompilerArgs.add("-Xjsr305=strict")
            }
        }

        extensions.configure<JavaPluginExtension> {
            toolchain {
                languageVersion.set(JavaLanguageVersion.of(25))
            }
        }

        tasks.withType<JavaCompile>().configureEach {
            options.release.set(25)
        }

        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
            maxHeapSize = "512m"
            systemProperty("control.service.root", rootProject.projectDir.absolutePath)
        }
    }
}

data class LockedModule(
    val id: String,
    val gradlePath: String,
    val packageName: String,
    val publicPackage: String,
    val schemas: Set<String>,
    val allowedDependencies: Set<String>,
)

data class LockedExecutionProtocolImportRule(
    val gradlePath: String,
    val sourcePaths: Set<String>,
)

data class LockedAuxiliaryRuntime(
    val id: String,
    val gradlePath: String,
)

data class LockedModuleMap(
    val applicationModule: String,
    val executionProtocolImportRules: List<LockedExecutionProtocolImportRule>,
    val canonicalApplyOrder: List<String>,
    val modules: List<LockedModule>,
    val auxiliaryRuntimes: List<LockedAuxiliaryRuntime>,
)

fun loadLockedModuleMap(contractFile: File): LockedModuleMap {
    @Suppress("UNCHECKED_CAST")
    val parsed = JsonSlurper().parse(contractFile) as Map<String, Any?>
    val modules = (parsed.getValue("modules") as List<*>).map { entry ->
        @Suppress("UNCHECKED_CAST")
        val module = entry as Map<String, Any?>
        LockedModule(
            id = module.getValue("id") as String,
            gradlePath = module.getValue("gradlePath") as String,
            packageName = module.getValue("package") as String,
            publicPackage = module.getValue("publicPackage") as String,
            schemas = (module.getValue("schemas") as List<*>)
                .map { it as String }
                .toSet(),
            allowedDependencies = (module.getValue("allowedDependencies") as List<*>)
                .map { it as String }
                .toSet(),
        )
    }
    val auxiliaryRuntimes = (parsed.getValue("auxiliaryRuntimes") as List<*>).map { entry ->
        @Suppress("UNCHECKED_CAST")
        val auxiliaryRuntime = entry as Map<String, Any?>
        LockedAuxiliaryRuntime(
            id = auxiliaryRuntime.getValue("id") as String,
            gradlePath = auxiliaryRuntime.getValue("gradlePath") as String,
        )
    }
    return LockedModuleMap(
        applicationModule = parsed.getValue("applicationModule") as String,
        executionProtocolImportRules =
            (parsed["executionProtocolImportRules"] as? List<*>)
                .orEmpty()
                .map { entry ->
                    @Suppress("UNCHECKED_CAST")
                    val rule = entry as Map<String, Any?>
                    LockedExecutionProtocolImportRule(
                        gradlePath = rule.getValue("gradlePath") as String,
                        sourcePaths =
                            (rule.getValue("sourcePaths") as List<*>)
                                .map { it as String }
                                .toSet(),
                    )
                },
        canonicalApplyOrder = (parsed.getValue("canonicalApplyOrder") as List<*>)
            .map { it as String },
        modules = modules,
        auxiliaryRuntimes = auxiliaryRuntimes,
    )
}

fun loadMetricFlowRendererModuleNames(contractFile: File): Set<String> {
    check(contractFile.isFile) {
        "MetricFlow artifact-set contract is missing: $contractFile"
    }
    val contract = JsonSlurper().parse(contractFile) as? Map<*, *>
        ?: error("MetricFlow artifact-set contract must be an object")
    val artifacts = contract["artifacts"] as? List<*>
        ?: error("MetricFlow artifact-set artifacts must be an array")
    val rendererModuleNames = artifacts.mapIndexedNotNull { index, entry ->
        val artifact = entry as? Map<*, *>
            ?: error("MetricFlow artifact-set artifact[$index] must be an object")
        val coordinate = artifact["coordinate"] as? String
            ?: error("MetricFlow artifact-set artifact[$index].coordinate must be a string")
        val coordinateParts = coordinate.split(":")
        check(coordinateParts.size == 3 && coordinateParts[0] == "cc.monomer.metricflow") {
            "MetricFlow artifact coordinate is malformed: $coordinate"
        }
        coordinateParts[1].takeIf { moduleName -> moduleName.startsWith("metricflow-render-") }
    }.toSet()
    check(rendererModuleNames.isNotEmpty()) {
        "MetricFlow artifact-set must contain renderer modules"
    }
    return rendererModuleNames
}

fun Project.declaredProjectDependencyPaths(): Set<String> {
    val projectPath = path
    return configurations
        .flatMap { configuration ->
            configuration.dependencies
                .withType(ProjectDependency::class.java)
                .map(ProjectDependency::getPath)
        }
        .filterNot { dependencyPath -> dependencyPath == projectPath }
        .toSet()
}

data class FrozenModuleDependency(
    val dependentModuleId: String,
    val dependencyModuleId: String,
    val isRequired: Boolean,
)

val frozenModuleDependencies = listOf(
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

fun verifyFrozenModuleDependencies(
    dependencyIdsByModuleId: Map<String, Set<String>>,
    sourceDescription: String,
) {
    frozenModuleDependencies.forEach { frozenDependency ->
        val actualDependencyIds = dependencyIdsByModuleId.getValue(
            frozenDependency.dependentModuleId,
        )
        val isPresent = frozenDependency.dependencyModuleId in actualDependencyIds
        check(isPresent == frozenDependency.isRequired) {
            val expectedRelation = if (frozenDependency.isRequired) "declare" else "not declare"
            "$sourceDescription must $expectedRelation ${frozenDependency.dependentModuleId} " +
                "-> ${frozenDependency.dependencyModuleId}"
        }
    }
}

fun requireAcyclic(graph: Map<String, Set<String>>) {
    val visiting = mutableSetOf<String>()
    val visited = mutableSetOf<String>()

    fun visit(node: String) {
        check(node !in visiting) { "Module dependency cycle at $node" }
        if (node in visited) {
            return
        }
        visiting += node
        graph.getValue(node).forEach(::visit)
        visiting -= node
        visited += node
    }

    graph.keys.sorted().forEach(::visit)
}

fun Char.isSqlDollarQuoteTagStart(): Boolean =
    this == '_' || (this >= 'A' && this <= 'Z') || (this >= 'a' && this <= 'z')

fun Char.isSqlDollarQuoteTagPart(): Boolean =
    isSqlDollarQuoteTagStart() || (this >= '0' && this <= '9')

fun String.sqlDollarQuoteDelimiterAt(index: Int): String? {
    if (
        this[index] != '$' ||
        (index > 0 && this[index - 1].let { character ->
            character == '_' || character.isLetterOrDigit() || character == '$'
        })
    ) {
        return null
    }
    if (index + 1 >= length) {
        return null
    }
    if (this[index + 1] == '$') {
        return "$$"
    }
    if (!this[index + 1].isSqlDollarQuoteTagStart()) {
        return null
    }
    var delimiterEnd = index + 2
    while (delimiterEnd < length && this[delimiterEnd].isSqlDollarQuoteTagPart()) {
        delimiterEnd += 1
    }
    return if (delimiterEnd < length && this[delimiterEnd] == '$') {
        substring(index, delimiterEnd + 1)
    } else {
        null
    }
}

fun Char.sqlLiteralMaskCharacter(): Char = if (this == '\n' || this == '\r') this else ' '

fun maskSqlStringLiterals(source: String, sourceDescription: String): String {
    val masked = StringBuilder(source.length)
    var index = 0
    while (index < source.length) {
        if (source.startsWith("--", index)) {
            val commentEnd = source.indexOf('\n', index).let { end ->
                if (end >= 0) end else source.length
            }
            masked.append(source, index, commentEnd)
            index = commentEnd
            continue
        }
        if (source.startsWith("/*", index)) {
            var commentDepth = 0
            while (index < source.length) {
                if (source.startsWith("/*", index)) {
                    commentDepth += 1
                    masked.append("/*")
                    index += 2
                } else if (source.startsWith("*/", index)) {
                    commentDepth -= 1
                    masked.append("*/")
                    index += 2
                    if (commentDepth == 0) {
                        break
                    }
                } else {
                    masked.append(source[index])
                    index += 1
                }
            }
            continue
        }
        if (source[index] == '\'') {
            val literalStart = index
            val escapeString =
                index > 0 &&
                    source[index - 1] in setOf('E', 'e') &&
                    (index == 1 || !source[index - 2].let { character ->
                        character == '_' || character.isLetterOrDigit() || character == '$'
                    })
            var literalClosed = false
            masked.append(source[index].sqlLiteralMaskCharacter())
            index += 1
            while (index < source.length) {
                val character = source[index]
                masked.append(character.sqlLiteralMaskCharacter())
                index += 1
                if (escapeString && character == '\\' && index < source.length) {
                    masked.append(source[index].sqlLiteralMaskCharacter())
                    index += 1
                } else if (character == '\'') {
                    if (index < source.length && source[index] == '\'') {
                        masked.append(source[index].sqlLiteralMaskCharacter())
                        index += 1
                    } else {
                        literalClosed = true
                        break
                    }
                }
            }
            check(literalClosed) {
                "Unterminated SQL single-quoted literal in $sourceDescription at offset $literalStart"
            }
            continue
        }

        val delimiter = source.sqlDollarQuoteDelimiterAt(index)
        if (delimiter != null) {
            val literalEnd = source.indexOf(delimiter, index + delimiter.length)
            if (literalEnd >= 0) {
                val literalEndExclusive = literalEnd + delimiter.length
                while (index < literalEndExclusive) {
                    masked.append(source[index].sqlLiteralMaskCharacter())
                    index += 1
                }
                continue
            }
        }

        masked.append(source[index])
        index += 1
    }
    return masked.toString()
}

fun persistenceOwnershipViolations(
    ownerModule: LockedModule,
    relativePath: String,
    source: String,
    sourceExtension: String,
    schemaOwners: Map<String, LockedModule>,
    ddlSchemaQualifierPattern: Regex,
    persistenceReferencePattern: Regex,
): List<String> {
    val sourceForReferenceScan =
        if (sourceExtension.lowercase() == "sql") {
            maskSqlStringLiterals(source, relativePath)
        } else {
            source
        }
    val referencedSchemas =
        if (sourceExtension.lowercase() == "sql") {
            ddlSchemaQualifierPattern.findAll(sourceForReferenceScan).map { match ->
                "DDL" to match.groupValues[1].lowercase()
            }
        } else {
            persistenceReferencePattern.findAll(sourceForReferenceScan).map { match ->
                match.groupValues[1] to match.groupValues[2].lowercase()
            }
        }
    return referencedSchemas.mapNotNull { (operation, referencedSchema) ->
        val referencedOwner = schemaOwners.getValue(referencedSchema)
        if (referencedOwner.id != ownerModule.id) {
            "${ownerModule.id}:$relativePath uses $operation " +
                "$referencedSchema owned by ${referencedOwner.id}"
        } else {
            null
        }
    }.toList()
}

val moduleContractFile = rootProject.file("../contracts/architecture/module-ownership.json")
val lockedModuleMap = loadLockedModuleMap(moduleContractFile)
val lockedModulesById = lockedModuleMap.modules.associateBy(LockedModule::id)
val lockedModulesByGradlePath = lockedModuleMap.modules.associateBy(LockedModule::gradlePath)
val lockedSchemaOwners = lockedModuleMap.modules
    .flatMap { module -> module.schemas.map { schema -> schema to module } }
    .also { schemaOwners ->
        check(schemaOwners.map { it.first }.distinct().size == schemaOwners.size) {
            "Each schema in module-ownership.json must have exactly one owner"
        }
    }
    .toMap()
val metricFlowArtifactSetContractFile = rootProject.file(
    "../release/contracts/metricflow-artifact-set.json",
)
val metricFlowRendererModuleNames = loadMetricFlowRendererModuleNames(
    metricFlowArtifactSetContractFile,
)
val forbiddenControlRuntimeDependencyFragments = setOf(
    "apache-arrow",
    "arrow-vector",
    "bigquery",
    "databricks",
    "redshift",
    "snowflake",
    "trino",
)

fun isForbiddenControlRuntimeComponent(
    group: String,
    module: String,
    rendererModuleNames: Set<String>,
): Boolean {
    val isProductOwnedMetricFlowRenderer =
        group == "cc.monomer.metricflow" && module in rendererModuleNames
    if (isProductOwnedMetricFlowRenderer) {
        return false
    }
    if (group == "org.apache.arrow") {
        // Explore reads the immutable Execution Arrow File at request time.  The
        // reader is the only Control runtime use of Arrow; warehouse drivers are
        // still excluded below by their own coordinates.
        return module !in setOf(
            "arrow-bom",
            "arrow-vector",
            "arrow-memory-unsafe",
            "arrow-format",
            "arrow-memory-core",
            "arrow-algorithm",
            "arrow-dataset",
            "arrow-c-data",
            "arrow-flight-core",
            "arrow-flight-grpc",
            "arrow-memory-netty",
        )
    }
    val unversionedCoordinate = "$group:$module".lowercase()
    return forbiddenControlRuntimeDependencyFragments.any(unversionedCoordinate::contains)
}

val boundarySourceProjectPaths = buildList {
    add(lockedModuleMap.applicationModule)
    addAll(lockedModuleMap.modules.map(LockedModule::gradlePath))
    addAll(lockedModuleMap.auxiliaryRuntimes.map(LockedAuxiliaryRuntime::gradlePath))
}

val verifyBuildTopology = tasks.register("verifyBuildTopology") {
    group = "verification"
    description = "Verifies the locked Control project set, dependency graph, and apply order."
    inputs.file(moduleContractFile)

    doLast {
        verifyFrozenModuleDependencies(
            dependencyIdsByModuleId = lockedModuleMap.modules.associate { module ->
                module.id to module.allowedDependencies
            },
            sourceDescription = "module-ownership.json",
        )

        val expectedProjectPaths = buildSet {
            add(":")
            addAll(structuralProjectPaths)
            add(lockedModuleMap.applicationModule)
            addAll(lockedModuleMap.modules.map(LockedModule::gradlePath))
            addAll(lockedModuleMap.auxiliaryRuntimes.map(LockedAuxiliaryRuntime::gradlePath))
        }
        val actualProjectPaths = rootProject.allprojects.map(Project::getPath).toSet()
        check(actualProjectPaths == expectedProjectPaths) {
            "Control projects differ from module-ownership.json. " +
                "Expected $expectedProjectPaths but found $actualProjectPaths"
        }

        lockedModuleMap.modules.forEach { module ->
            val expectedDependencies = module.allowedDependencies
                .map { dependencyId -> lockedModulesById.getValue(dependencyId).gradlePath }
                .toSet()
            val actualDependencies = project(module.gradlePath).declaredProjectDependencyPaths()
            check(actualDependencies == expectedDependencies) {
                "${module.gradlePath} project dependencies differ from allowedDependencies. " +
                    "Expected $expectedDependencies but found $actualDependencies"
            }
        }

        verifyFrozenModuleDependencies(
            dependencyIdsByModuleId = lockedModuleMap.modules.associate { module ->
                module.id to project(module.gradlePath)
                    .declaredProjectDependencyPaths()
                    .map { dependencyPath ->
                        lockedModulesByGradlePath[dependencyPath]?.id ?: dependencyPath
                    }
                    .toSet()
            },
            sourceDescription = "Control Gradle project dependencies",
        )

        val applicationDependencies = project(lockedModuleMap.applicationModule)
            .declaredProjectDependencyPaths()
        val expectedApplicationDependencies = lockedModuleMap.modules
            .map(LockedModule::gradlePath)
            .toSet()
        check(applicationDependencies == expectedApplicationDependencies) {
            "${lockedModuleMap.applicationModule} must compose every bounded context exactly once"
        }

        lockedModuleMap.auxiliaryRuntimes.forEach { auxiliaryRuntime ->
            val dependencies = project(auxiliaryRuntime.gradlePath).declaredProjectDependencyPaths()
            check(dependencies.isEmpty()) {
                "${auxiliaryRuntime.gradlePath} must not depend on Product Control projects"
            }
        }

        val actualGraph = lockedModuleMap.modules.associate { module ->
            val dependencyIds = project(module.gradlePath)
                .declaredProjectDependencyPaths()
                .map { dependencyPath -> lockedModulesByGradlePath.getValue(dependencyPath).id }
                .toSet()
            module.id to dependencyIds
        }
        requireAcyclic(actualGraph)

        check(lockedModuleMap.canonicalApplyOrder.toSet() == lockedModulesById.keys) {
            "canonicalApplyOrder must contain each bounded context exactly once"
        }
        val applyIndexByModule = lockedModuleMap.canonicalApplyOrder
            .withIndex()
            .associate { indexedValue -> indexedValue.value to indexedValue.index }
        actualGraph.forEach { (moduleId, dependencyIds) ->
            dependencyIds.forEach { dependencyId ->
                check(applyIndexByModule.getValue(dependencyId) < applyIndexByModule.getValue(moduleId)) {
                    "canonicalApplyOrder places $moduleId before dependency $dependencyId"
                }
            }
        }

        val projectsWithBootJar = rootProject.allprojects
            .filter { candidate -> candidate.tasks.findByName("bootJar") != null }
            .map(Project::getPath)
        check(projectsWithBootJar == listOf(lockedModuleMap.applicationModule)) {
            "Only ${lockedModuleMap.applicationModule} may provide a BootJar; found $projectsWithBootJar"
        }
    }
}

val verifyModuleBoundaries = tasks.register("verifyModuleBoundaries") {
    group = "verification"
    description = "Verifies module package ownership, public imports, and hexagonal source purity."
    inputs.file(moduleContractFile)
    inputs.files(
        boundarySourceProjectPaths.map { projectPath ->
            fileTree(project(projectPath).projectDir.resolve("src")) {
                include("**/*.kt", "**/*.java")
            }
        },
    )

    doLast {
        val importPattern = Regex("""(?m)^\s*import\s+([A-Za-z_][A-Za-z0-9_.]*)""")
        val packagePattern = Regex("""(?m)^\s*package\s+([A-Za-z_][A-Za-z0-9_.]*)""")
        val infrastructurePrefixes = listOf(
            "org.springframework.",
            "java.sql.",
            "javax.sql.",
            "io.grpc.",
            "com.google.protobuf.",
            "cc.monomer.execution.protocol.v1.",
        )
        val executionProtocolPackage = "cc.monomer.execution.protocol.v1"
        val retiredPackageIdentifier =
            "com." + "nave" + "rcorp" + "." + "ni" + "mo"
        val violations = mutableListOf<String>()

        lockedModuleMap.modules.forEach { ownerModule ->
            val moduleProject = project(ownerModule.gradlePath)
            val sourceRoot = moduleProject.projectDir.resolve("src")
            if (!sourceRoot.exists()) {
                return@forEach
            }

            fileTree(sourceRoot) {
                include("**/*.kt", "**/*.java")
            }.files.sorted().forEach { sourceFile ->
                val source = sourceFile.readText()
                val relativePath = sourceFile.relativeTo(moduleProject.projectDir).invariantSeparatorsPath
                val declaredPackage = packagePattern.find(source)?.groupValues?.get(1)
                if (declaredPackage == null ||
                    (declaredPackage != ownerModule.packageName &&
                        !declaredPackage.startsWith(ownerModule.packageName + "."))
                ) {
                    violations += "$relativePath is outside package ${ownerModule.packageName}"
                }
                if (retiredPackageIdentifier in source) {
                    violations += "$relativePath contains a retired package identifier"
                }

                importPattern.findAll(source).forEach { match ->
                    val importedPackage = match.groupValues[1]
                    val targetModule = lockedModuleMap.modules
                        .filter { candidate ->
                            importedPackage == candidate.packageName ||
                                importedPackage.startsWith(candidate.packageName + ".")
                        }
                        .maxByOrNull { candidate -> candidate.packageName.length }
                    if (targetModule != null && targetModule.id != ownerModule.id) {
                        if (targetModule.id !in ownerModule.allowedDependencies) {
                            violations +=
                                "$relativePath imports undeclared module ${targetModule.id}: $importedPackage"
                        }
                        if (
                            importedPackage != targetModule.publicPackage &&
                            !importedPackage.startsWith(targetModule.publicPackage + ".")
                        ) {
                            violations +=
                                "$relativePath imports non-api package from ${targetModule.id}: $importedPackage"
                        }
                    }
                    val isPureLayer = "/domain/" in "/$relativePath" ||
                        "/application/" in "/$relativePath"
                    if (isPureLayer &&
                        infrastructurePrefixes.any(importedPackage::startsWith)
                    ) {
                        violations +=
                            "$relativePath imports infrastructure from a pure layer: $importedPackage"
                    }
                }
            }
        }

        boundarySourceProjectPaths
            .forEach { projectPath ->
                val sourceProject = project(projectPath)
                val sourceRoot = sourceProject.projectDir.resolve("src")
                if (!sourceRoot.exists()) {
                    return@forEach
                }
                fileTree(sourceRoot) {
                    include("**/*.kt", "**/*.java")
                }.files.sorted().forEach { sourceFile ->
                    val relativePath = sourceFile
                        .relativeTo(sourceProject.projectDir)
                        .invariantSeparatorsPath
                    importPattern.findAll(sourceFile.readText()).forEach { match ->
                        val importedPackage = match.groupValues[1]
                        val allowedSourcePaths =
                            lockedModuleMap.executionProtocolImportRules
                                .firstOrNull { rule -> rule.gradlePath == projectPath }
                                ?.sourcePaths
                                .orEmpty()
                        val importIsAllowed =
                            allowedSourcePaths.any { allowedPath ->
                                if (allowedPath.endsWith("/")) {
                                    relativePath.startsWith(allowedPath)
                                } else {
                                    relativePath == allowedPath
                                }
                            }
                        if (
                            (
                                importedPackage == executionProtocolPackage ||
                                    importedPackage.startsWith("$executionProtocolPackage.")
                            ) &&
                            !importIsAllowed
                        ) {
                            violations +=
                                "$projectPath:$relativePath imports execution protocol " +
                                "infrastructure: $importedPackage"
                        }
                    }
                }
            }

        check(violations.isEmpty()) {
            "Control module boundary violations:\n" + violations.joinToString("\n")
        }
    }
}

val verifyPersistenceOwnership = tasks.register("verifyPersistenceOwnership") {
    group = "verification"
    description = "Rejects direct SQL references across bounded-context schema owners."
    inputs.file(moduleContractFile)
    inputs.files(
        lockedModuleMap.modules.map { module ->
            fileTree(project(module.gradlePath).projectDir.resolve("src/main")) {
                include("**/*.sql", "**/*.kt", "**/*.java")
            }
        },
    )

    doLast {
        val schemaAlternation = lockedSchemaOwners.keys
            .sortedByDescending(String::length)
            .joinToString("|", transform = Regex::escape)
        val ddlSchemaQualifierPattern = Regex(
            """(?<![A-Za-z0-9_])\"?($schemaAlternation)\"?\s*\.""",
            RegexOption.IGNORE_CASE,
        )
        val persistenceReferencePattern = Regex(
            """\b(REFERENCES|FROM|JOIN|INSERT\s+INTO|UPDATE|DELETE\s+FROM|""" +
                """MERGE\s+INTO|TRUNCATE(?:\s+TABLE)?|ALTER\s+TABLE|""" +
                """DROP\s+TABLE(?:\s+IF\s+EXISTS)?|""" +
                """CREATE\s+(?:TABLE|VIEW)(?:\s+IF\s+NOT\s+EXISTS)?)""" +
                """\s+(?:ONLY\s+)?\"?($schemaAlternation)\"?\s*\.""",
            RegexOption.IGNORE_CASE,
        )
        val violations = mutableListOf<String>()
        val identityAccessModule = lockedModulesById.getValue("identity-access")
        fun fixtureViolations(relativePath: String, source: String): List<String> =
            persistenceOwnershipViolations(
                ownerModule = identityAccessModule,
                relativePath = relativePath,
                source = source,
                sourceExtension = "sql",
                schemaOwners = lockedSchemaOwners,
                ddlSchemaQualifierPattern = ddlSchemaQualifierPattern,
                persistenceReferencePattern = persistenceReferencePattern,
            )
        fun requireUnterminatedLiteralFailure(relativePath: String, source: String) {
            val failure = runCatching { fixtureViolations(relativePath, source) }.exceptionOrNull()
            check(
                failure is IllegalStateException &&
                    failure.message?.contains(relativePath) == true,
            ) {
                "Persistence ownership must fail closed for unterminated SQL literals"
            }
        }

        check(
            fixtureViolations(
                "fixtures/plain-capability-literals.sql",
                """
                CREATE TABLE auth.capability_fixture (
                    capability TEXT NOT NULL CHECK (
                        capability IN ('dashboard.edit', 'agent.workspace')
                    )
                );
                """.trimIndent(),
            ).isEmpty(),
        ) {
            "Persistence ownership must ignore dotted capability values in SQL literals"
        }
        check(
            fixtureViolations(
                "fixtures/escaped-capability-literals.sql",
                """
                CREATE TABLE auth.capability_fixture (
                    capability TEXT NOT NULL CHECK (
                        capability IN (
                            E'agent.workspace\'dashboard.edit',
                            'dashboard''agent.workspace'
                        )
                    )
                );
                """.trimIndent(),
            ).isEmpty(),
        ) {
            "Persistence ownership must ignore escaped SQL string literals"
        }
        requireUnterminatedLiteralFailure(
            "fixtures/unterminated-literal.sql",
            """
            CREATE TABLE auth.capability_fixture (capability TEXT NOT NULL);
            INSERT INTO auth.capability_fixture (capability) VALUES ('agent.workspace
            REFERENCES dashboard.widgets(id);
            """.trimIndent(),
        )
        requireUnterminatedLiteralFailure(
            "fixtures/unterminated-escape-literal.sql",
            """
            CREATE TABLE auth.capability_fixture (capability TEXT NOT NULL);
            INSERT INTO auth.capability_fixture (capability) VALUES (E'agent.workspace
            REFERENCES dashboard.widgets(id);
            """.trimIndent(),
        )
        check(
            fixtureViolations(
                "fixtures/dollar-quoted-capability-body.sql",
                """
                DO ${'$'}fixture${'$'}
                BEGIN
                    PERFORM dashboard.edit;
                    PERFORM agent.workspace;
                END;
                ${'$'}fixture${'$'};
                CREATE TABLE auth.capability_fixture (id UUID PRIMARY KEY);
                """.trimIndent(),
            ).isEmpty(),
        ) {
            "Persistence ownership must ignore dollar-quoted SQL bodies"
        }
        check(
            fixtureViolations(
                "fixtures/empty-dollar-quoted-body.sql",
                """
                SELECT ${'$'}${'$'}${'$'}${'$'};
                CREATE TABLE auth.capability_fixture (id UUID PRIMARY KEY);
                """.trimIndent(),
            ).isEmpty(),
        ) {
            "Persistence ownership must recognize an empty dollar-quoted SQL body"
        }
        check(
            fixtureViolations(
                "fixtures/comment-reference.sql",
                """
                CREATE TABLE auth.capability_fixture (id UUID PRIMARY KEY);
                -- catalog.capability_fixture
                """.trimIndent(),
            ) == listOf(
                "identity-access:fixtures/comment-reference.sql uses DDL " +
                    "catalog owned by catalog",
            ),
        ) {
            "Persistence ownership comment handling must remain unchanged"
        }
        check(
            fixtureViolations(
                "fixtures/block-comment-reference.sql",
                """
                CREATE TABLE auth.capability_fixture (id UUID PRIMARY KEY);
                /* ' catalog.capability_fixture */
                """.trimIndent(),
            ) == listOf(
                "identity-access:fixtures/block-comment-reference.sql uses DDL " +
                    "catalog owned by catalog",
            ),
        ) {
            "Persistence ownership block-comment handling must remain unchanged"
        }
        check(
            fixtureViolations(
                "fixtures/unauthorized-schema-reference.sql",
                """
                CREATE TABLE auth.capability_fixture (catalog_id UUID NOT NULL);
                ALTER TABLE auth.capability_fixture
                    ADD CONSTRAINT capability_fixture_catalog_reference
                    FOREIGN KEY (catalog_id) REFERENCES catalog.relation_metadata(id);
                """.trimIndent(),
            ) == listOf(
                "identity-access:fixtures/unauthorized-schema-reference.sql uses DDL " +
                    "catalog owned by catalog",
            ),
        ) {
            "Persistence ownership must still reject a real unauthorized schema reference"
        }

        lockedModuleMap.modules.forEach { ownerModule ->
            val moduleProject = project(ownerModule.gradlePath)
            val sourceRoot = moduleProject.projectDir.resolve("src/main")
            if (!sourceRoot.exists()) {
                return@forEach
            }
            fileTree(sourceRoot) {
                include("**/*.sql", "**/*.kt", "**/*.java")
            }.files.sorted().forEach { sourceFile ->
                val relativePath =
                    sourceFile.relativeTo(moduleProject.projectDir).invariantSeparatorsPath
                val source = sourceFile.readText()
                violations += persistenceOwnershipViolations(
                    ownerModule = ownerModule,
                    relativePath = relativePath,
                    source = source,
                    sourceExtension = sourceFile.extension,
                    schemaOwners = lockedSchemaOwners,
                    ddlSchemaQualifierPattern = ddlSchemaQualifierPattern,
                    persistenceReferencePattern = persistenceReferencePattern,
                )
            }
        }

        check(violations.isEmpty()) {
            "Cross-owner persistence references:\n" + violations.joinToString("\n")
        }
    }
}

val verifyBuildIsolation = tasks.register("verifyBuildIsolation") {
    group = "verification"
    description = "Verifies independent build boundaries and the single root-owned proto source."
    inputs.file("settings.gradle.kts")
    inputs.files(
        fileTree(rootProject.projectDir) {
            include("**/*.gradle", "**/*.gradle.kts", "**/*.proto")
            exclude(".gradle/**", "**/build/**")
        },
    )

    doLast {
        val settingsSource = rootProject.file("settings.gradle.kts").readText()
        check(!Regex("""\bincludeBuild\s*\(""").containsMatchIn(settingsSource)) {
            "Control Service must not use an included or composite build"
        }

        val siblingReferencePattern = Regex("""["'](\.\./[^"']+)["']""")
        val allowedSiblingReferences = setOf(
            "../assets/brand/monomer-favicon.png",
            "../contracts",
            "../contracts/architecture/module-ownership.json",
            "../docs/research/2026-08-27-agent-public-rest-api-design.html",
            "../docs/research/2026-08-27-agent-public-rest-api-request-response-catalog.html",
            "../protos",
            "../release/contracts/metricflow-artifact-set.json",
            "../scripts/monomer-require-local-ingress-closed.sh",
            "../testkit/agent-oauth-client/verify_mcp_protocol.py",
            "../testkit/agent-oauth-client/verify_pages_mcp.py",
            "../testkit/dashboard-d10/dashboard.json",
            "../testkit/data/duckdb/acme-cloud/definitions",
            "../testkit/data/duckdb/acme-cloud/versions/1/definitions",
            "../testkit/data/duckdb/acme-cloud/versions/2/definitions",
            "../testkit/environment/environment.contract.json",
        )
        val forbiddenSiblingReferences = fileTree(rootProject.projectDir) {
            include("**/*.gradle", "**/*.gradle.kts")
            exclude(".gradle/**", "**/build/**")
        }.files.flatMap { buildFile ->
            siblingReferencePattern.findAll(buildFile.readText()).mapNotNull { match ->
                val reference = match.groupValues[1]
                if (reference in allowedSiblingReferences) {
                    null
                } else {
                    "${buildFile.relativeTo(rootProject.projectDir)} -> $reference"
                }
            }.toList()
        }
        check(forbiddenSiblingReferences.isEmpty()) {
            "Control build has cross-build sibling references: $forbiddenSiblingReferences"
        }

        val copiedProtoFiles = fileTree(rootProject.projectDir) {
            include("**/*.proto")
            exclude(".gradle/**", "**/build/**")
        }.files
        check(copiedProtoFiles.isEmpty()) {
            "Control Service must generate from ../protos/execution_plane.proto without copying it"
        }

        val executionRuntimeBuild = project(":modules:execution-runtime")
            .projectDir
            .resolve("build.gradle.kts")
            .readText()
        check("""rootProject.file("../protos")""" in executionRuntimeBuild) {
            "execution-runtime must use the root-owned proto directory"
        }
        check("""include("execution_plane.proto")""" in executionRuntimeBuild) {
            "execution-runtime must select execution_plane.proto explicitly"
        }

        val checkedInProtocolBindings = fileTree(project(":modules:execution-runtime").projectDir) {
            include("src/**/cc/monomer/execution/protocol/v1/**/*.kt")
            include("src/**/cc/monomer/execution/protocol/v1/**/*.java")
        }.files
        check(checkedInProtocolBindings.isEmpty()) {
            "Execution protocol bindings must be generated, not checked in"
        }
    }
}

val verifyControlRuntimePurity = tasks.register("verifyControlRuntimePurity") {
    group = "verification"
    description = "Rejects warehouse drivers and Arrow from the Control runtime classpath."
    inputs.file(metricFlowArtifactSetContractFile)

    doLast {
        val runtimeClasspath = project(":application")
            .configurations
            .getByName("runtimeClasspath")
        val forbiddenDependencies = runtimeClasspath.incoming.resolutionResult.allComponents
            .mapNotNull { component ->
                val identifier = component.id
                if (identifier is ModuleComponentIdentifier) {
                    identifier
                } else {
                    null
                }
            }
            .filter { identifier ->
                isForbiddenControlRuntimeComponent(
                    group = identifier.group,
                    module = identifier.module,
                    rendererModuleNames = metricFlowRendererModuleNames,
                )
            }
            .map { identifier -> "${identifier.group}:${identifier.module}:${identifier.version}" }
            .sorted()
        check(forbiddenDependencies.isEmpty()) {
            "Control runtime contains forbidden execution dependencies: $forbiddenDependencies"
        }
    }
}

val verifyControlRuntimePurityPolicy = tasks.register("verifyControlRuntimePurityPolicy") {
    group = "verification"
    description = "Verifies that only contract-owned MetricFlow renderer wrappers bypass runtime purity fragments."
    inputs.file(metricFlowArtifactSetContractFile)

    doLast {
        val bigQueryRendererModule = metricFlowRendererModuleNames.singleOrNull { moduleName ->
            moduleName.endsWith("-bigquery")
        } ?: error("MetricFlow artifact-set must contain exactly one BigQuery renderer wrapper")
        check(
            !isForbiddenControlRuntimeComponent(
                group = "cc.monomer.metricflow",
                module = bigQueryRendererModule,
                rendererModuleNames = metricFlowRendererModuleNames,
            ),
        ) {
            "Contract-owned MetricFlow renderer wrapper was rejected by runtime purity"
        }
        check(
            isForbiddenControlRuntimeComponent(
                group = "com.google.cloud",
                module = "google-cloud-bigquery",
                rendererModuleNames = metricFlowRendererModuleNames,
            ),
        ) {
            "External BigQuery dependency bypassed runtime purity"
        }
        check(
            !isForbiddenControlRuntimeComponent(
                group = "org.apache.arrow",
                module = "arrow-vector",
                rendererModuleNames = metricFlowRendererModuleNames,
            ),
        ) {
            "Explore's pinned Arrow reader dependency was rejected"
        }
    }
}

val verifyPhaseTwoAdmissionBoundary = tasks.register("verifyPhaseTwoAdmissionBoundary") {
    group = "verification"
    description =
        "Verifies that admission itself stays free of ingress and network trust: the identity-access " +
            "and resource-authority modules plus the composition root's own files. Bounded-context " +
            "packages under cc.monomer.control own the product ingress adapters and are out of scope. " +
            "Identity client metadata resolution may use its bounded HTTPS adapter; admission never performs introspection."
    inputs.files(
        boundarySourceProjectPaths.map { projectPath ->
            fileTree(project(projectPath).projectDir.resolve("src/main")) {
                include("**/*.kt", "**/*.sql", "**/*.yaml")
            }
        },
    )

    doLast {
        val identityRoot = project(":modules:identity-access").projectDir
        val authorityRoot = project(":modules:resource-authority").projectDir
        val applicationRoot = project(":application").projectDir
        val identitySources = fileTree(identityRoot.resolve("src/main")) {
            include("**/*.kt")
        }.files
        val authoritySources = fileTree(authorityRoot.resolve("src/main")) {
            include("**/*.kt")
        }.files
        val admissionCompositionSources = fileTree(
            applicationRoot.resolve("src/main/kotlin/cc/monomer/control"),
        ) {
            include("*.kt")
        }.files
        val admissionSources = identitySources + authoritySources + admissionCompositionSources
        val forbiddenIngressOrNetworkFragments = listOf(
            "@RestController",
            "@Controller",
            "@MutationMapping",
            "@PostMapping",
            "WebClient",
            "HttpClient",
            "tokenIntrospection",
            "x-workspace",
            "X-Workspace",
        )
        val forbiddenSources = admissionSources.flatMap { sourceFile ->
            val source = sourceFile.readText()
            forbiddenIngressOrNetworkFragments
                .filter(source::contains)
                .filterNot { fragment ->
                    fragment == "HttpClient" && sourceFile == identityRoot.resolve(
                        "src/main/kotlin/cc/monomer/identity/outbound/oauth/HttpsOAuthClientResolver.kt",
                    )
                }
                .map { fragment ->
                    "${sourceFile.relativeTo(rootProject.projectDir)} contains $fragment"
                }
        }
        check(forbiddenSources.isEmpty()) {
            "Phase 2 admission exposes ingress or network trust: $forbiddenSources"
        }

        val productionKotlinSources =
            boundarySourceProjectPaths.flatMap { projectPath ->
                fileTree(project(projectPath).projectDir.resolve("src/main")) {
                    include("**/*.kt")
                }.files
            }.map { sourceFile -> sourceFile.canonicalFile }
                .distinct()
                .sortedBy { sourceFile -> sourceFile.relativeTo(rootProject.projectDir).path }

        fun constructorCallOffsets(
            source: String,
            typeName: String,
        ): List<Int> =
            Regex("""\b${Regex.escape(typeName)}\s*\(""")
                .findAll(source)
                .map { match -> match.range.first }
                .filter { offset ->
                    val prefix = source.substring(maxOf(0, offset - 64), offset)
                    !Regex("""\b(?:class|interface|object)\s*$""").containsMatchIn(prefix)
                }.toList()

        fun canonicalMutationContextConstructorOffsets(source: String): List<Int> {
            val fullyQualifiedOffsets =
                Regex("""\bcc\.monomer\.identity\.api\.AuthorizedMutationContext\s*\(""")
                    .findAll(source)
                    .map { match -> match.range.first }
                    .toList()
            val importsCanonicalType = Regex(
                """(?m)^\s*import\s+cc\.monomer\.identity\.api\.AuthorizedMutationContext\s*$""",
            ).containsMatchIn(source)
            val belongsToCanonicalPackage = Regex(
                """(?m)^\s*package\s+cc\.monomer\.identity\.api\s*$""",
            ).containsMatchIn(source)
            val simpleNameOffsets = if (importsCanonicalType || belongsToCanonicalPackage) {
                constructorCallOffsets(source, "AuthorizedMutationContext")
            } else {
                emptyList()
            }
            return (fullyQualifiedOffsets + simpleNameOffsets).distinct()
        }

        val facadeFile = applicationRoot.resolve(
            "src/main/kotlin/cc/monomer/control/ControlAdmissionFacade.kt",
        ).canonicalFile
        val identityAdmissionFile = identityRoot.resolve(
            "src/main/kotlin/cc/monomer/identity/api/IdentityAdmission.kt",
        ).canonicalFile
        val unauthorizedMutationContextConstructors =
            productionKotlinSources.filter { sourceFile ->
                sourceFile.canonicalFile !in setOf(identityAdmissionFile, facadeFile) &&
                    canonicalMutationContextConstructorOffsets(sourceFile.readText()).isNotEmpty()
            }
        check(unauthorizedMutationContextConstructors.isEmpty()) {
            "Only Identity persistence and ControlAdmissionFacade may construct mutation contexts: " +
                unauthorizedMutationContextConstructors.map { it.relativeTo(rootProject.projectDir) }
        }

        val unauthorizedExecutionContextConstructors =
            productionKotlinSources.filter { sourceFile ->
                sourceFile.canonicalFile != facadeFile &&
                    constructorCallOffsets(sourceFile.readText(), "AuthorizedExecutionContext").isNotEmpty()
            }
        check(unauthorizedExecutionContextConstructors.isEmpty()) {
            "Only ControlAdmissionFacade may issue execution contexts: " +
                unauthorizedExecutionContextConstructors.map { it.relativeTo(rootProject.projectDir) }
        }
        check(constructorCallOffsets(facadeFile.readText(), "AuthorizedExecutionContext").isNotEmpty()) {
            "ControlAdmissionFacade must remain the execution-context issuer"
        }

        val contextRestorationPattern =
            Regex("""\bAuthorizedMutationContext\.restore\s*\(""")
        val allowedContextRestorationFile = project(":modules:connector").projectDir.resolve(
            "src/main/kotlin/cc/monomer/connector/adapter/outbound/postgres/" +
                "PostgresConnectorRepository.kt",
        ).canonicalFile
        val unauthorizedContextRestorers = boundarySourceProjectPaths.flatMap { projectPath ->
            fileTree(project(projectPath).projectDir.resolve("src/main")) {
                include("**/*.kt")
            }.files
        }.filter { sourceFile ->
            sourceFile.canonicalFile != allowedContextRestorationFile &&
                contextRestorationPattern.containsMatchIn(sourceFile.readText())
        }
        check(unauthorizedContextRestorers.isEmpty()) {
            "Only the Connector persistence adapter may restore an authorized mutation context: " +
                unauthorizedContextRestorers.map { it.relativeTo(rootProject.projectDir) }
        }

        fun bracedFunctionRange(
            source: String,
            signature: String,
        ): IntRange {
            val signatureOffset = source.indexOf(signature)
            check(signatureOffset >= 0) { "Missing required function: $signature" }
            val openingBraceOffset = source.indexOf('{', signatureOffset)
            check(openingBraceOffset >= 0) { "Missing function body for $signature" }
            var depth = 0
            for (offset in openingBraceOffset until source.length) {
                when (source[offset]) {
                    '{' -> depth += 1
                    '}' -> {
                        depth -= 1
                        if (depth == 0) {
                            return signatureOffset..offset
                        }
                    }
                }
            }
            error("Unclosed function body for $signature")
        }

        data class DirectAdmissionPortInvocation(
            val portTypeName: String,
            val methodName: String,
            val offset: Int,
        )

        fun directAdmissionPortInvocations(
            source: String,
        ): List<DirectAdmissionPortInvocation> {
            val expectedMethodByPortType = mapOf(
                "IdentityAdmissionPort" to "decide",
            )
            val declaredPortsByName =
                Regex(
                    """\b([A-Za-z_][A-Za-z0-9_]*)\s*:\s*""" +
                        """(?:[A-Za-z_][A-Za-z0-9_]*\.)*""" +
                        """(IdentityAdmissionPort)\b""",
                ).findAll(source)
                    .associate { declaration ->
                        declaration.groupValues[1] to declaration.groupValues[2]
                    }
            return declaredPortsByName.flatMap { (receiverName, portTypeName) ->
                val methodName = expectedMethodByPortType.getValue(portTypeName)
                Regex("""\b${Regex.escape(receiverName)}\s*\.\s*$methodName\s*\(""")
                    .findAll(source)
                    .map { invocation ->
                        DirectAdmissionPortInvocation(
                            portTypeName = portTypeName,
                            methodName = methodName,
                            offset = invocation.range.first,
                        )
                    }.toList()
            }.sortedBy(DirectAdmissionPortInvocation::offset)
        }

        val variableThroughPortFixture =
            """
            private suspend fun unsafe(
                identityAdmissionPort: IdentityAdmissionPort,
                request: IdentityRoleAdmissionRequest,
            ) {
                identityAdmissionPort.decide(request)
            }
            """.trimIndent()
        check(
            directAdmissionPortInvocations(variableThroughPortFixture).singleOrNull()?.let { invocation ->
                invocation.portTypeName == "IdentityAdmissionPort" &&
                    invocation.methodName == "decide"
            } == true,
        ) {
            "Phase 2 admission boundary must reject a variable-through-port initial admission invocation"
        }

        val unauthorizedAdmissionPortCallers = productionKotlinSources.flatMap { sourceFile ->
            val source = sourceFile.readText()
            directAdmissionPortInvocations(source).filter { invocation ->
                sourceFile != facadeFile
            }.map { invocation ->
                "${sourceFile.relativeTo(rootProject.projectDir)}:" +
                    "${invocation.portTypeName}.${invocation.methodName}"
            }
        }
        check(unauthorizedAdmissionPortCallers.isEmpty()) {
            "Only ControlAdmissionFacade may invoke Identity admission; " +
                "authenticated-caller factories are the narrow exception: $unauthorizedAdmissionPortCallers"
        }

        val identityDdl = identityRoot.resolve("src/main/resources/db/identity-runtime/schema.sql").readText()
        val forbiddenDdlPattern = Regex(
            """(?i)\bCREATE\s+(?:OR\s+REPLACE\s+)?VIEW\b|""" +
                """\b(?:REFERENCES|FROM|JOIN|UPDATE|INTO|TABLE)\s+""" +
                """(?:ONLY\s+)?(?:catalog|dashboard|semantic|connector)\.""",
        )
        check(!forbiddenDdlPattern.containsMatchIn("CHECK (capability IN ('connector.manage'))")) {
            "Cross-owner DDL gate must not treat capability values as schema references"
        }
        check(forbiddenDdlPattern.containsMatchIn("REFERENCES connector.connectors(id)")) {
            "Cross-owner DDL gate must reject foreign schema table references"
        }
        check(!forbiddenDdlPattern.containsMatchIn(identityDdl)) {
            "Identity owner DDL contains a cross-owner view or table reference"
        }
        check(fileTree(authorityRoot.resolve("src/main/resources/db")).files.isEmpty()) {
            "Resource Authority must not persist current graph state"
        }

        val facadeSource = facadeFile.readText()
        check(facadeSource.contains("IdentityRoleAdmissionRequest(")) {
            "Control admission must use current Identity role evidence"
        }
        val executionAdmissionBody =
            bracedFunctionRange(facadeSource, "suspend fun authorizeExecution(")
                .let(facadeSource::substring)
        check(executionAdmissionBody.contains("authorizeWorkspaceRole(") &&
            executionAdmissionBody.contains("WorkspaceRole.EXPLORER")) {
            "Explore and Catalog execution must require Explorer role admission"
        }
        check(!facadeSource.contains("resolveCurrent(") && !facadeSource.contains("RequestResourceGraph")) {
            "Execution admission must not depend on current Lineage topology"
        }

    }
}

val verifyControlArchitecture = tasks.register("verifyControlArchitecture") {
    group = "verification"
    description = "Runs every executable Product Control build boundary gate."
    dependsOn(
        verifyBuildTopology,
        verifyModuleBoundaries,
        verifyPersistenceOwnership,
        verifyBuildIsolation,
        verifyControlRuntimePurity,
        verifyControlRuntimePurityPolicy,
        verifyPhaseTwoAdmissionBoundary,
    )
}

val testTestkitCapabilities = tasks.register<Exec>("testTestkitCapabilities") {
    group = "verification"
    description = "Verifies Phase 1 testkit descriptors and fail-closed mutation capabilities."
    inputs.files(
        "testkit/inventory.sh",
        "testkit/reset-environment.sh",
        "testkit/test-capabilities.sh",
        "../testkit/environment/environment.contract.json",
    )
    commandLine("bash", rootProject.file("testkit/test-capabilities.sh"))
}

val testDatabricksStatementApiCutover = tasks.register<Exec>("testDatabricksStatementApiCutover") {
    group = "verification"
    description = "Verifies the data-preserving Databricks warehouse property cutover in disposable PostgreSQL."
    inputs.files(
        "testkit/databricks-statement-api-cutover.sql",
        "testkit/databricks-statement-api-cutover.sh",
        "testkit/test-databricks-statement-api-cutover.py",
        "modules/catalog/src/main/resources/db/catalog/schema.sql",
        "modules/connector/src/main/resources/db/connector/schema.sql",
    )
    commandLine("python3", rootProject.file("testkit/test-databricks-statement-api-cutover.py"))
}

val testLocalDatabaseReset = tasks.register<Exec>("testLocalDatabaseReset") {
    group = "verification"
    description = "Verifies the explicit, fenced local Control PostgreSQL reset capability."
    inputs.files(
        "testkit/reset-local-database.sh",
        "testkit/test-reset-local-database-shell.sh",
        "testkit/bootstrap-empty-local-database.sh",
        "deployment/database-bootstrap-ddl.txt",
        "../contracts/architecture/module-ownership.json",
        "../scripts/monomer-require-local-ingress-closed.sh",
    )
    commandLine("bash", rootProject.file("testkit/test-reset-local-database-shell.sh"))
}

tasks.register("test") {
    group = "verification"
    description = "Runs every Product Control unit test."
    dependsOn(
        subprojects
            .filter { subproject -> subproject.path !in structuralProjectPaths }
            .map { subproject -> "${subproject.path}:test" },
    )
}

tasks.named("check") {
    dependsOn(
        subprojects
            .filter { subproject -> subproject.path !in structuralProjectPaths }
            .map { subproject -> "${subproject.path}:check" },
    )
    dependsOn(verifyControlArchitecture)
    dependsOn(testTestkitCapabilities)
    dependsOn(testLocalDatabaseReset)
}

tasks.register("confidenceSweep") {
    group = "verification"
    description = "Manually runs check plus all Control integration and PostgreSQL verification gates."
    dependsOn("check")
    dependsOn(
        ":application:integrationTest",
        ":application:postgresAuthorityGate",
        ":modules:catalog:integrationTest",
        ":modules:connector:integrationTest",
        ":modules:connector:credentialVaultPostgresGate",
        ":modules:explore:integrationTest",
        ":modules:identity-access:identityAuthorityPostgresGate",
        ":modules:execution-runtime:integrationTest",
        ":modules:pages:integrationTest",
        ":modules:semantic:integrationTest",
    )
}

val verifyMetricDisplayContracts = tasks.register<Exec>("verifyMetricDisplayContracts") {
    group = "verification"
    description = "Verifies display currency enums and OpenAPI components against the canonical schema."
    workingDir(rootDir.parentFile)
    commandLine("python3", "scripts/sync_metric_display_contract.py", "--check")
}

tasks.named("check") { dependsOn(verifyMetricDisplayContracts) }
