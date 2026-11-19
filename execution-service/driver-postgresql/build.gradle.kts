import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.testing.Test

plugins {
    kotlin("jvm")
}

dependencies {
    api(project(":driver-jdbc-core"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.21.4")
    implementation("org.postgresql:postgresql:42.7.10")
    testImplementation(kotlin("test"))
    testImplementation(testFixtures(project(":driver-jdbc-core")))
    testImplementation(platform("org.apache.arrow:arrow-bom:18.3.0"))
    testImplementation("org.apache.arrow:arrow-vector")
    testRuntimeOnly("org.postgresql:postgresql:42.7.10")
}

val sourceSets = extensions.getByType<SourceSetContainer>()
val postgresSecurityTestSourceSet = sourceSets.create("integrationTest")
postgresSecurityTestSourceSet.compileClasspath +=
    sourceSets.getByName("main").output + sourceSets.getByName("test").output
postgresSecurityTestSourceSet.runtimeClasspath +=
    sourceSets.getByName("main").output + sourceSets.getByName("test").output

configurations[postgresSecurityTestSourceSet.implementationConfigurationName]
    .extendsFrom(configurations.testImplementation.get())
configurations[postgresSecurityTestSourceSet.runtimeOnlyConfigurationName]
    .extendsFrom(configurations.testRuntimeOnly.get())
dependencies {
    add(
        postgresSecurityTestSourceSet.implementationConfigurationName,
        "org.testcontainers:testcontainers-postgresql:2.0.5",
    )
    add(postgresSecurityTestSourceSet.implementationConfigurationName, platform("org.apache.arrow:arrow-bom:18.3.0"))
    add(postgresSecurityTestSourceSet.implementationConfigurationName, "org.apache.arrow:arrow-vector")
}

kotlin.target.compilations.named("integrationTest") {
    associateWith(kotlin.target.compilations.getByName("main"))
}

val postgresSecurityGate = tasks.register<Test>("postgresSecurityGate") {
    group = "verification"
    description =
        "Proves PostgreSQL nonexecuting validation and read-only execution against a disposable pinned warehouse database."
    testClassesDirs = postgresSecurityTestSourceSet.output.classesDirs
    classpath = postgresSecurityTestSourceSet.runtimeClasspath
    useJUnitPlatform()
    jvmArgs("--add-opens=java.base/java.nio=ALL-UNNAMED")
}

tasks.named<Test>("test") {
    jvmArgs("--add-opens=java.base/java.nio=ALL-UNNAMED")
}
