import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.testing.Test
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("plugin.serialization") version "2.3.20"
}

description = "Catalog bounded context"

dependencies {
    api(project(":modules:identity-access"))
    api(project(":modules:connector"))
    api(project(":modules:execution-runtime"))
    implementation(platform("org.springframework.boot:spring-boot-dependencies:4.0.7"))
    implementation("org.springframework:spring-context")
    implementation("org.springframework:spring-jdbc")
    implementation("org.springframework.boot:spring-boot-autoconfigure")
    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-slf4j:1.10.2")
    implementation(platform("org.apache.arrow:arrow-bom:18.3.0"))
    implementation("org.apache.arrow:arrow-vector")
    runtimeOnly("org.apache.arrow:arrow-memory-unsafe")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
    implementation("org.postgresql:postgresql:42.7.5")
    implementation("org.slf4j:slf4j-api")
    implementation("jakarta.annotation:jakarta.annotation-api")

    testImplementation(kotlin("test"))
    testImplementation("org.assertj:assertj-core:3.27.3")
    testImplementation("io.mockk:mockk:1.13.16")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation(testFixtures(project(":modules:connector")))
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
    description = "Runs hermetic Catalog authority integration tests."
    testClassesDirs = integrationTestSourceSet.output.classesDirs
    classpath = integrationTestSourceSet.runtimeClasspath
    useJUnitPlatform()
    shouldRunAfter(tasks.named("test"))
}

tasks.register("verifyCatalogAuthority") {
    group = "verification"
    description = "Verifies Catalog current selection, ingest job/task state, and current-object authority."
    dependsOn("test")
}

tasks.named("check") {
    dependsOn("verifyCatalogAuthority")
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions.freeCompilerArgs.add("-opt-in=kotlin.uuid.ExperimentalUuidApi")
}
