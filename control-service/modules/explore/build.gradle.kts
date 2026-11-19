import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.testing.Test

description = "Explore bounded context"

dependencies {
    api(project(":modules:identity-access"))
    api(project(":modules:connector"))
    api(project(":modules:execution-runtime"))
    api(project(":modules:semantic"))
    implementation(platform("org.springframework.boot:spring-boot-dependencies:4.0.7"))
    implementation("org.springframework:spring-jdbc")
    implementation(platform("org.apache.arrow:arrow-bom:18.3.0"))
    implementation("org.apache.arrow:arrow-vector")
    runtimeOnly("org.apache.arrow:arrow-memory-unsafe")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
    implementation("org.slf4j:slf4j-api")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.12.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("org.assertj:assertj-core:3.27.3")
    testImplementation("io.mockk:mockk:1.13.16")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test>().configureEach {
    jvmArgs("--add-opens=java.base/java.nio=ALL-UNNAMED")
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
    description = "Runs hermetic Explore run owner integration tests."
    testClassesDirs = integrationTestSourceSet.output.classesDirs
    classpath = integrationTestSourceSet.runtimeClasspath
    useJUnitPlatform()
    shouldRunAfter(tasks.named("test"))
}
