import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.testing.Test
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

plugins {
    kotlin("jvm")
}

val sourceSets = extensions.getByType<SourceSetContainer>()
val trinoExternalDwSourceSet = sourceSets.create("externalDwTest")
val kotlinTarget = extensions.getByType<KotlinJvmProjectExtension>().target
kotlinTarget.compilations.getByName("externalDwTest")
    .associateWith(kotlinTarget.compilations.getByName("main"))
trinoExternalDwSourceSet.compileClasspath +=
    sourceSets.getByName("main").output + sourceSets.getByName("test").output
trinoExternalDwSourceSet.runtimeClasspath +=
    sourceSets.getByName("main").output + sourceSets.getByName("test").output

configurations[trinoExternalDwSourceSet.implementationConfigurationName]
    .extendsFrom(configurations.testImplementation.get())
configurations[trinoExternalDwSourceSet.runtimeOnlyConfigurationName]
    .extendsFrom(configurations.testRuntimeOnly.get())

tasks.register<Test>("trinoExternalDwGate") {
    group = "verification"
    description = "Proves Trino driver physical conformance against the external DW lab."
    testClassesDirs = trinoExternalDwSourceSet.output.classesDirs
    classpath = trinoExternalDwSourceSet.runtimeClasspath
    useJUnitPlatform()
    outputs.upToDateWhen { false }
}

dependencies {
    api(project(":driver-jdbc-core"))
    implementation("com.fasterxml.jackson.core:jackson-databind:2.21.4")
    implementation("io.trino:trino-jdbc:471")
    implementation("io.trino:trino-parser:471")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    testImplementation(platform("org.apache.arrow:arrow-bom:18.3.0"))
    testImplementation("org.apache.arrow:arrow-vector")
    testRuntimeOnly("org.apache.arrow:arrow-memory-unsafe")
    testImplementation(kotlin("test"))
    testImplementation(testFixtures(project(":driver-jdbc-core")))
}

tasks.named<Test>("test") {
    jvmArgs("--add-opens=java.base/java.nio=ALL-UNNAMED")
}
