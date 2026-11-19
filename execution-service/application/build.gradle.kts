import org.gradle.api.tasks.testing.Test
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import javax.inject.Inject
import org.gradle.process.ExecOperations
import org.gradle.jvm.toolchain.JavaLauncher

plugins {
    kotlin("jvm")
    application
}

val sampleDriverRuntime by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    attributes.attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
}

dependencies {
    implementation(project(":protocol"))
    implementation(project(":runtime"))
    implementation(project(":object-storage-s3"))
    implementation(project(":driver-postgresql"))
    implementation(project(":driver-bigquery"))
    implementation(project(":driver-redshift"))
    implementation(project(":driver-trino"))
    implementation(project(":driver-snowflake"))
    implementation(project(":driver-databricks"))
    implementation(project(":driver-duckdb"))
    add(sampleDriverRuntime.name, project(":driver-duckdb"))
    implementation("io.grpc:grpc-netty-shaded:1.77.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.21.4")
    testImplementation(kotlin("test"))
    testImplementation("io.grpc:grpc-inprocess:1.77.0")
}

application {
    mainClass.set("cc.monomer.execution.application.ExecutionServiceMainKt")
    applicationDefaultJvmArgs =
        listOf(
            "--add-opens=java.base/java.nio=ALL-UNNAMED",
        )
}

abstract class PrepareLocalSampleData @Inject constructor(private val execution: ExecOperations) : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val generator: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val generatorSql: RegularFileProperty

    @get:Classpath
    abstract val driverClasspath: ConfigurableFileCollection

    @get:Nested
    abstract val javaLauncher: Property<JavaLauncher>

    @get:OutputFile
    abstract val database: RegularFileProperty

    @TaskAction
    fun prepare() {
        val target = database.get().asFile.toPath()
        Files.createDirectories(target.parent)
        val temporaryDirectory = Files.createTempDirectory(target.parent, "generate-")
        try {
            val generated = temporaryDirectory.resolve("acme_cloud.duckdb")
            execution.javaexec {
                executable = javaLauncher.get().executablePath.asFile.absolutePath
                classpath = driverClasspath
                mainClass.set(generator.get().asFile.absolutePath)
                jvmArgs("--enable-native-access=ALL-UNNAMED")
                args(generatorSql.get().asFile.absolutePath, generated.toString())
            }
            Files.setPosixFilePermissions(generated, setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.GROUP_READ, PosixFilePermission.OTHERS_READ))
            Files.move(generated, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            temporaryDirectory.toFile().deleteRecursively()
        }
    }
}

val localSampleDataRoot = layout.buildDirectory.dir("sample-data")
val prepareLegacyLocalSampleData by tasks.registering(PrepareLocalSampleData::class) {
    group = "application"
    description = "Preserves the original Acme Cloud dataset for existing sample Workspaces."
    generator.set(rootProject.layout.projectDirectory.file("../testkit/data/duckdb/acme-cloud/GenerateDataset.java"))
    generatorSql.set(rootProject.layout.projectDirectory.file("../testkit/data/duckdb/acme-cloud/versions/1/generate.sql"))
    driverClasspath.from(sampleDriverRuntime)
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) })
    database.set(localSampleDataRoot.map { it.file("acme-cloud/1/acme_cloud.duckdb") })
}
val prepareVersionTwoLocalSampleData by tasks.registering(PrepareLocalSampleData::class) {
    group = "application"
    description = "Preserves Acme Cloud version 2 for existing sample Workspaces."
    generator.set(rootProject.layout.projectDirectory.file("../testkit/data/duckdb/acme-cloud/GenerateDataset.java"))
    generatorSql.set(rootProject.layout.projectDirectory.file("../testkit/data/duckdb/acme-cloud/versions/2/generate.sql"))
    driverClasspath.from(sampleDriverRuntime)
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) })
    database.set(localSampleDataRoot.map { it.file("acme-cloud/2/acme_cloud.duckdb") })
}
val prepareLocalSampleData by tasks.registering(PrepareLocalSampleData::class) {
    group = "application"
    description = "Prepares Acme Cloud version 3 and retains versions 1 and 2 for local Execution runs."
    dependsOn(prepareLegacyLocalSampleData, prepareVersionTwoLocalSampleData)
    generator.set(rootProject.layout.projectDirectory.file("../testkit/data/duckdb/acme-cloud/GenerateDataset.java"))
    generatorSql.set(rootProject.layout.projectDirectory.file("../testkit/data/duckdb/acme-cloud/generate.sql"))
    driverClasspath.from(sampleDriverRuntime)
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) })
    database.set(localSampleDataRoot.map { it.file("acme-cloud/3/acme_cloud.duckdb") })
}

tasks.named<JavaExec>("run") {
    if (!providers.environmentVariable("MONOMER_EXECUTION_SAMPLE_DATA_ROOT").isPresent) {
        dependsOn(prepareLocalSampleData)
        environment("MONOMER_EXECUTION_SAMPLE_DATA_ROOT", localSampleDataRoot.get().asFile.absolutePath)
    }
}

tasks.named<Test>("test") {
    dependsOn(prepareLocalSampleData)
    systemProperty("execution.local.sample.root", localSampleDataRoot.get().asFile.absolutePath)
}

val executionFaultTest by tasks.registering(Test::class) {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter {
        includeTestsMatching("*ExecutionPlaneRunTest")
    }
}

tasks.named("check") {
    dependsOn(executionFaultTest)
}

val processIntegrationTestSourceSet = sourceSets.create("integrationTest")
processIntegrationTestSourceSet.compileClasspath += sourceSets.main.get().output
processIntegrationTestSourceSet.runtimeClasspath += sourceSets.main.get().output
configurations[processIntegrationTestSourceSet.implementationConfigurationName]
    .extendsFrom(configurations.testImplementation.get())
configurations[processIntegrationTestSourceSet.runtimeOnlyConfigurationName]
    .extendsFrom(configurations.testRuntimeOnly.get())
dependencies {
    add(processIntegrationTestSourceSet.implementationConfigurationName, "org.testcontainers:testcontainers-postgresql:2.0.5")
}

tasks.register<Test>("executionProcessRestartTest") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Verifies real Execution JVM interruption and restart against disposable PostgreSQL."
    testClassesDirs = processIntegrationTestSourceSet.output.classesDirs
    classpath = processIntegrationTestSourceSet.runtimeClasspath
    systemProperty("execution.process.classpath", sourceSets.main.get().runtimeClasspath.asPath)
    systemProperty("execution.process.evidence", layout.buildDirectory.dir("execution-process-restart").get().asFile.absolutePath)
    outputs.upToDateWhen { false }
    filter { includeTestsMatching("*ExecutionProcessRestartIntegrationTest") }
}
