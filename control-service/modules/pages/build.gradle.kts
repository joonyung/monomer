import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.testing.Test
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

plugins {
    kotlin("plugin.spring") version "2.3.20"
    kotlin("plugin.serialization") version "2.3.20"
}

description = "Pages bounded context"

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
    implementation("org.springframework:spring-webflux")
    implementation("jakarta.annotation:jakarta.annotation-api")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-jdk8:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactive:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("org.postgresql:postgresql:42.7.5")
    implementation(enforcedPlatform("software.amazon.awssdk:bom:2.31.10"))
    implementation("software.amazon.awssdk:s3")

    testImplementation(kotlin("test"))
    testImplementation(kotlin("reflect"))
    testImplementation("org.assertj:assertj-core:3.27.3")
    testImplementation("io.mockk:mockk:1.13.16")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("org.testcontainers:testcontainers:2.0.5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

val sourceSets = extensions.getByType<SourceSetContainer>()
val integrationTestSourceSet = sourceSets.create("integrationTest")
val kotlinTarget = extensions.getByType<KotlinJvmProjectExtension>().target
kotlinTarget.compilations.getByName("integrationTest")
    .associateWith(kotlinTarget.compilations.getByName("main"))
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
        "org.testcontainers:testcontainers-postgresql:2.0.5",
    )
}

tasks.register<Test>("integrationTest") {
    group = "verification"
    description = "Runs hermetic Pages persistence integration tests."
    testClassesDirs = integrationTestSourceSet.output.classesDirs
    classpath = integrationTestSourceSet.runtimeClasspath
    useJUnitPlatform()
    shouldRunAfter(tasks.named("test"))
}
