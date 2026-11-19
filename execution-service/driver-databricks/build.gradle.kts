import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.testing.Test
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

plugins {
    kotlin("jvm")
}

val sourceSets = extensions.getByType<SourceSetContainer>()
val databricksExternalDwSourceSet = sourceSets.create("externalDwTest")
val kotlinTarget = extensions.getByType<KotlinJvmProjectExtension>().target
kotlinTarget.compilations.getByName("externalDwTest")
    .associateWith(kotlinTarget.compilations.getByName("main"))
databricksExternalDwSourceSet.compileClasspath +=
    sourceSets.getByName("main").output + sourceSets.getByName("test").output
databricksExternalDwSourceSet.runtimeClasspath +=
    sourceSets.getByName("main").output + sourceSets.getByName("test").output

configurations[databricksExternalDwSourceSet.implementationConfigurationName]
    .extendsFrom(configurations.testImplementation.get())
configurations[databricksExternalDwSourceSet.runtimeOnlyConfigurationName]
    .extendsFrom(configurations.testRuntimeOnly.get())

tasks.register<Test>("databricksExternalDwGate") {
    group = "verification"
    description = "Runs the attended tiny Databricks Statement API gate against the documented fixture."
    testClassesDirs = databricksExternalDwSourceSet.output.classesDirs
    classpath = databricksExternalDwSourceSet.runtimeClasspath
    include("**/DatabricksExternalDwGateTest.class")
    useJUnitPlatform()
    testLogging.showStandardStreams = true
    outputs.upToDateWhen { false }
}

tasks.register<Test>("databricksGateEvidenceTest") {
    group = "verification"
    description = "Checks sanitized Databricks gate failure evidence with synthetic results only."
    testClassesDirs = databricksExternalDwSourceSet.output.classesDirs
    classpath = databricksExternalDwSourceSet.runtimeClasspath
    include("**/DatabricksGateEvidenceTest.class")
    useJUnitPlatform()
    testLogging.showStandardStreams = true
}

dependencies {
    api(project(":driver-spi"))
    implementation(platform("org.apache.arrow:arrow-bom:18.3.0"))
    implementation("org.apache.arrow:arrow-vector")
    runtimeOnly("org.apache.arrow:arrow-memory-unsafe")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.21.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}

tasks.withType<Test>().configureEach {
    jvmArgs("--add-opens=java.base/java.nio=ALL-UNNAMED")
}
