import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.testing.Test

description = "Identity and Access bounded context"

plugins {
    kotlin("plugin.spring")
    kotlin("plugin.serialization") version "2.3.20"
}

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-opt-in=kotlin.uuid.ExperimentalUuidApi")
    }
}

dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:4.0.7"))
    implementation("org.springframework.boot:spring-boot")
    implementation("org.springframework:spring-context")
    implementation("org.springframework:spring-jdbc")
    implementation("org.springframework:spring-tx")
    implementation("org.slf4j:slf4j-api")
    implementation("org.postgresql:postgresql:42.7.5")
    implementation("com.nimbusds:nimbus-jose-jwt:10.3")
    implementation("com.nimbusds:oauth2-oidc-sdk:11.21")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.12.2")
    testImplementation("org.assertj:assertj-core:3.27.3")
    testImplementation("io.mockk:mockk:1.13.16")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.processResources {
    from(rootProject.file("../contracts")) {
        include("time-zones.json")
        into("contracts")
    }
}

val sourceSets = extensions.getByType<SourceSetContainer>()
tasks.named<Test>("test") {
    inputs.files(fileTree("src/main"), fileTree("src/integrationTest"), file("build.gradle.kts"))
        .withPropertyName("identitySchemaContractSources")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

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

val identityAuthorityPostgresGate = tasks.register<Test>("identityAuthorityPostgresGate") {
    group = "verification"
    description = "Verifies one-snapshot identity admission in disposable PostgreSQL."
    testClassesDirs = integrationTestSourceSet.output.classesDirs
    classpath = integrationTestSourceSet.runtimeClasspath
    useJUnitPlatform()
    shouldRunAfter(tasks.named("test"))
}
