import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.testing.Test

plugins {
    `java-test-fixtures`
}

description = "Connector bounded context"

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-opt-in=kotlin.uuid.ExperimentalUuidApi")
    }
}

dependencies {
    api(project(":modules:identity-access"))
    api(project(":modules:execution-runtime"))
    implementation(platform("org.springframework.boot:spring-boot-dependencies:4.0.7"))
    implementation("org.springframework:spring-jdbc")
    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.12.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
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
}

tasks.register<Test>("integrationTest") {
    group = "verification"
    description = "Runs hermetic Connector authority integration tests."
    testClassesDirs = integrationTestSourceSet.output.classesDirs
    classpath = integrationTestSourceSet.runtimeClasspath
    useJUnitPlatform()
    shouldRunAfter(tasks.named("test"))
}

val credentialVaultPostgresGate = tasks.register<Test>("credentialVaultPostgresGate") {
    group = "verification"
    description = "Verifies Connector current state and immutable credential lifecycle in disposable PostgreSQL."
    testClassesDirs = integrationTestSourceSet.output.classesDirs
    classpath = integrationTestSourceSet.runtimeClasspath
    useJUnitPlatform()
    filter {
        includeTestsMatching(
            "cc.monomer.connector.ConnectorCurrentStatePostgresIntegrationTest",
        )
    }
    shouldRunAfter(tasks.named("test"))
}

tasks.register("verifyConnectorAuthority") {
    group = "verification"
    description = "Verifies Connector current-state, credential, policy, and validation-intent invariants."
    dependsOn("test")
}

tasks.named("check") {
    dependsOn("verifyConnectorAuthority")
}

val verifyConnectorSchema = tasks.register<Exec>("verifyConnectorSchema") {
    group = "verification"
    description = "Verifies public Connector schema matches the owner's typed contracts."
    workingDir(projectDir)
    commandLine("python3", "scripts/generate-schema.py", "--check")
}

tasks.named("check") {
    dependsOn(verifyConnectorSchema)
}
