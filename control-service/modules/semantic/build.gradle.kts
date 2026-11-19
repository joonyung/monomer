import groovy.json.JsonSlurper
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.testing.Test
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest

plugins {
    kotlin("plugin.serialization") version "2.3.20"
}

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-opt-in=kotlin.uuid.ExperimentalUuidApi")
    }
}

description = "Semantic bounded context"

data class MetricFlowArtifact(
    val coordinate: String,
    val relativePath: String,
    val sha256: String,
)

data class MetricFlowArtifactSet(
    val version: String,
    val artifactSetDigest: String,
    val artifacts: List<MetricFlowArtifact>,
)

fun Map<*, *>.requiredString(key: String, context: String): String =
    this[key] as? String ?: error("$context.$key must be a string")

fun loadMetricFlowArtifactSet(contractFile: File): MetricFlowArtifactSet {
    check(contractFile.isFile) {
        "MetricFlow artifact-set contract is missing: $contractFile"
    }
    val contract = JsonSlurper().parse(contractFile) as? Map<*, *>
        ?: error("MetricFlow artifact-set contract must be an object")
    val version = contract.requiredString("version", "MetricFlow artifact-set contract")
    check(
        version.matches(
            Regex(
                "^(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)" +
                    "(?:-[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?" +
                    "(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?$",
            ),
        ),
    ) {
        "MetricFlow artifact-set version is not SemVer: $version"
    }
    val artifactSetDigest = contract.requiredString(
        "artifactSetDigest",
        "MetricFlow artifact-set contract",
    )
    check(artifactSetDigest.matches(Regex("^sha256:[0-9a-f]{64}$"))) {
        "MetricFlow artifact-set digest is malformed"
    }
    val artifacts = (contract["artifacts"] as? List<*>)
        ?.mapIndexed { index, entry ->
            val artifact = entry as? Map<*, *>
                ?: error("MetricFlow artifact-set artifact[$index] must be an object")
            MetricFlowArtifact(
                coordinate = artifact.requiredString("coordinate", "MetricFlow artifact-set artifact[$index]"),
                relativePath = artifact.requiredString("relativePath", "MetricFlow artifact-set artifact[$index]"),
                sha256 = artifact.requiredString("sha256", "MetricFlow artifact-set artifact[$index]"),
            )
        }
        ?: error("MetricFlow artifact-set artifacts must be an array")
    check(artifacts.isNotEmpty()) { "MetricFlow artifact-set must contain artifacts" }
    check(artifacts.map(MetricFlowArtifact::coordinate).toSet().size == artifacts.size) {
        "MetricFlow artifact-set has duplicate coordinates"
    }
    artifacts.forEach { artifact ->
        val coordinateParts = artifact.coordinate.split(":")
        check(coordinateParts.size == 3 && coordinateParts[0] == "cc.monomer.metricflow") {
            "MetricFlow artifact coordinate is malformed: ${artifact.coordinate}"
        }
        val moduleName = coordinateParts[1]
        val artifactVersion = coordinateParts[2]
        check(artifactVersion == version) {
            "MetricFlow artifact version does not match artifact-set version: ${artifact.coordinate}"
        }
        val expectedRelativePath = listOf(
            coordinateParts[0].replace('.', '/'),
            moduleName,
            artifactVersion,
            "$moduleName-$artifactVersion.jar",
        ).joinToString("/")
        check(artifact.relativePath == expectedRelativePath) {
            "MetricFlow artifact path does not match coordinate: ${artifact.coordinate}"
        }
        check(artifact.sha256.matches(Regex("^sha256:[0-9a-f]{64}$"))) {
            "MetricFlow artifact checksum is malformed: ${artifact.coordinate}"
        }
    }
    return MetricFlowArtifactSet(
        version = version,
        artifactSetDigest = artifactSetDigest,
        artifacts = artifacts,
    )
}

fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) {
                break
            }
            digest.update(buffer, 0, count)
        }
    }
    return "sha256:" + digest.digest().joinToString("") { byte ->
        "%02x".format(byte.toInt() and 0xff)
    }
}

fun verifyArtifactChecksum(
    artifact: MetricFlowArtifact,
    artifactFile: File,
    location: String,
) {
    check(Files.isRegularFile(artifactFile.toPath(), LinkOption.NOFOLLOW_LINKS)) {
        "MetricFlow artifact is not a regular file at $location: ${artifact.coordinate}"
    }
    val actualDigest = sha256(artifactFile)
    check(actualDigest == artifact.sha256) {
        "MetricFlow artifact checksum mismatch at $location: ${artifact.coordinate}"
    }
}

val metricFlowArtifactContractFile = rootProject.file(
    "../release/contracts/metricflow-artifact-set.json",
)
val metricFlowArtifactSet = loadMetricFlowArtifactSet(metricFlowArtifactContractFile)
val metricFlowArtifactsByCoordinate = metricFlowArtifactSet.artifacts.associateBy(
    MetricFlowArtifact::coordinate,
)
val metricFlowRepositoryProperty = "monomerMetricflowRepository"
val metricFlowRepositoryEnvironmentVariable = "MONOMER_METRICFLOW_REPOSITORY"
val metricFlowRepositoryPath = (
    providers.gradleProperty(metricFlowRepositoryProperty).orNull
        ?: providers.environmentVariable(metricFlowRepositoryEnvironmentVariable).orNull
    )
    ?.takeIf { repositoryPath -> repositoryPath.isNotBlank() }
    ?: error(
        "MetricFlow consumption requires -P$metricFlowRepositoryProperty=/absolute/maven/repository " +
            "or $metricFlowRepositoryEnvironmentVariable=/absolute/maven/repository",
    )
val metricFlowRepositoryFile = file(metricFlowRepositoryPath)
check(metricFlowRepositoryFile.isAbsolute) {
    "MetricFlow repository must be supplied as an absolute path"
}
val metricFlowRepositoryDirectory = metricFlowRepositoryFile.normalize().absoluteFile
check(metricFlowRepositoryDirectory.isDirectory) {
    "MetricFlow repository does not exist or is not a directory: $metricFlowRepositoryDirectory"
}
val metricFlowArtifactSetConfiguration = configurations.create("metricflowArtifactSet") {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
    resolutionStrategy.activateDependencyLocking()
}
val metricFlowRuntimePurityConfiguration = configurations.create("metricflowRuntimePurity") {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = true
    resolutionStrategy.activateDependencyLocking()
}
val forbiddenMetricFlowRuntimeCoordinates = setOf(
    "org.postgresql:postgresql",
    "com.mysql:mysql-connector-j",
    "com.google.cloud:google-cloud-bigquery",
    "net.snowflake:snowflake-jdbc",
    "com.databricks:databricks-jdbc",
    "io.trino:trino-jdbc",
    "com.amazon.redshift:redshift-jdbc42",
)

dependencies {
    api(project(":modules:identity-access"))
    api(project(":modules:connector"))
    api(project(":modules:catalog"))
    metricFlowArtifactSet.artifacts.forEach { artifact ->
        implementation(artifact.coordinate)
        add(metricFlowArtifactSetConfiguration.name, artifact.coordinate)
        add(metricFlowRuntimePurityConfiguration.name, artifact.coordinate)
    }
    implementation(platform("org.springframework.boot:spring-boot-dependencies:4.0.7"))
    implementation("org.springframework:spring-context")
    implementation("org.springframework:spring-jdbc")
    implementation("org.springframework:spring-tx")
    implementation("org.slf4j:slf4j-api")
    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")

    testImplementation(kotlin("test"))
    testImplementation("org.assertj:assertj-core:3.27.6")
    testImplementation("io.mockk:mockk:1.13.16")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

val generatedMetricFlowRuntimeIdentityDirectory = layout.buildDirectory.dir(
    "generated/sources/metricflow-runtime-identity/kotlin",
)
val generatedMetricFlowRuntimeIdentityFile = generatedMetricFlowRuntimeIdentityDirectory.map { directory ->
    directory.file(
        "cc/monomer/semantic/adapter/outbound/metricflow/MetricFlowRuntimeIdentity.kt",
    )
}
val generateMetricFlowRuntimeIdentity = tasks.register("generateMetricFlowRuntimeIdentity") {
    inputs.file(metricFlowArtifactContractFile)
    outputs.file(generatedMetricFlowRuntimeIdentityFile)

    doLast {
        val outputFile = generatedMetricFlowRuntimeIdentityFile.get().asFile
        outputFile.parentFile.mkdirs()
        outputFile.writeText(
            """
            package cc.monomer.semantic.adapter.outbound.metricflow

            internal object MetricFlowRuntimeIdentity {
                const val version = "${metricFlowArtifactSet.version}"
                const val artifactSetDigest = "${metricFlowArtifactSet.artifactSetDigest}"
            }
            """.trimIndent() + "\n",
        )
    }
}
extensions.configure<KotlinJvmProjectExtension> {
    sourceSets.named("main") {
        kotlin.srcDir(generatedMetricFlowRuntimeIdentityDirectory)
    }
}
tasks.named("compileKotlin") {
    dependsOn(generateMetricFlowRuntimeIdentity)
}

val verifyMetricFlowArtifactSet = tasks.register("verifyMetricFlowArtifactSet") {
    group = "verification"
    description = "Verifies the exact MetricFlow JVM artifact closure and checksums."
    inputs.file(metricFlowArtifactContractFile)

    doLast {
        metricFlowArtifactSet.artifacts.forEach { artifact ->
            verifyArtifactChecksum(
                artifact = artifact,
                artifactFile = metricFlowRepositoryDirectory.resolve(artifact.relativePath),
                location = "the configured local repository",
            )
        }
        val resolvedMetricFlowArtifacts = metricFlowArtifactSetConfiguration
            .resolvedConfiguration
            .resolvedArtifacts
            .associate { artifact ->
                val moduleVersion = artifact.moduleVersion.id
                "${moduleVersion.group}:${moduleVersion.name}:${moduleVersion.version}" to artifact.file
            }
        check(resolvedMetricFlowArtifacts.keys == metricFlowArtifactsByCoordinate.keys) {
            "MetricFlow artifact closure differs from the frozen artifact set. " +
                "Expected ${metricFlowArtifactsByCoordinate.keys.sorted()} but found " +
                resolvedMetricFlowArtifacts.keys.sorted()
        }
        resolvedMetricFlowArtifacts.forEach { (coordinate, file) ->
            verifyArtifactChecksum(
                artifact = metricFlowArtifactsByCoordinate.getValue(coordinate),
                artifactFile = file,
                location = "the resolved Gradle classpath",
            )
        }
    }
}

val verifyMetricFlowRuntimePurity = tasks.register("verifyMetricFlowRuntimePurity") {
    group = "verification"
    description = "Rejects renderer drift, database drivers, and Apache Arrow from the MetricFlow runtime closure."
    inputs.file(metricFlowArtifactContractFile)

    doLast {
        val metricFlowRuntimeArtifacts = metricFlowRuntimePurityConfiguration
            .resolvedConfiguration
            .resolvedArtifacts
        val runtimeMetricFlowArtifacts = metricFlowRuntimeArtifacts
            .filter { artifact -> artifact.moduleVersion.id.group == "cc.monomer.metricflow" }
            .associate { artifact ->
                val moduleVersion = artifact.moduleVersion.id
                "${moduleVersion.group}:${moduleVersion.name}:${moduleVersion.version}" to artifact.file
            }
        check(runtimeMetricFlowArtifacts.keys == metricFlowArtifactsByCoordinate.keys) {
            "MetricFlow runtime closure differs from the frozen artifact set"
        }
        runtimeMetricFlowArtifacts.forEach { (coordinate, artifactFile) ->
            verifyArtifactChecksum(
                artifact = metricFlowArtifactsByCoordinate.getValue(coordinate),
                artifactFile = artifactFile,
                location = "the Semantic runtime classpath",
            )
        }
        val forbiddenCoordinates = metricFlowRuntimeArtifacts
            .map { artifact ->
                val moduleVersion = artifact.moduleVersion.id
                "${moduleVersion.group}:${moduleVersion.name}:${moduleVersion.version}"
            }
            .filter { coordinate ->
                coordinate.substringBeforeLast(':').let { unversionedCoordinate ->
                    unversionedCoordinate in forbiddenMetricFlowRuntimeCoordinates ||
                        unversionedCoordinate.startsWith("org.apache.arrow:")
                }
            }
        check(forbiddenCoordinates.isEmpty()) {
            "Semantic MetricFlow runtime must not contain drivers or Arrow: $forbiddenCoordinates"
        }
    }
}

val verifyMetricFlowConsumption = tasks.register("verifyMetricFlowConsumption") {
    group = "verification"
    description = "Verifies pinned MetricFlow closure, checksums, and runtime purity."
    dependsOn(verifyMetricFlowArtifactSet, verifyMetricFlowRuntimePurity)
}

val sourceSets = extensions.getByType<SourceSetContainer>()
val integrationTestSourceSet = sourceSets.create("integrationTest")
integrationTestSourceSet.compileClasspath +=
    sourceSets.getByName("main").output + sourceSets.getByName("test").output
integrationTestSourceSet.runtimeClasspath +=
    sourceSets.getByName("main").output + sourceSets.getByName("test").output

configurations[integrationTestSourceSet.implementationConfigurationName]
    .extendsFrom(configurations.testImplementation.get())
configurations[integrationTestSourceSet.runtimeOnlyConfigurationName]
    .extendsFrom(configurations.testRuntimeOnly.get())

dependencies {
    add(
        integrationTestSourceSet.implementationConfigurationName,
        "org.postgresql:postgresql:42.7.5",
    )
    add(
        integrationTestSourceSet.implementationConfigurationName,
        "org.testcontainers:testcontainers-postgresql:2.0.5",
    )
    add(
        integrationTestSourceSet.implementationConfigurationName,
        "org.testcontainers:testcontainers-junit-jupiter:2.0.5",
    )
    add(
        integrationTestSourceSet.runtimeOnlyConfigurationName,
        "org.duckdb:duckdb_jdbc:1.5.4.0",
    )
}

tasks.register<Test>("integrationTest") {
    group = "verification"
    description = "Runs hermetic Semantic authority integration tests."
    testClassesDirs = integrationTestSourceSet.output.classesDirs
    classpath = integrationTestSourceSet.runtimeClasspath
    useJUnitPlatform()
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    shouldRunAfter(tasks.named("test"))
}

val verifySemanticAuthority = tasks.register("verifySemanticAuthority") {
    group = "verification"
    description = "Verifies current Semantic definitions, DSL contracts, and request-local rendering."
    dependsOn("test", verifyMetricFlowConsumption)
}

tasks.named("check") {
    dependsOn(verifySemanticAuthority)
}
