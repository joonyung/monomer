description = "Dashboard bounded context"

plugins {
    kotlin("plugin.serialization") version "2.3.20"
}

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-opt-in=kotlin.uuid.ExperimentalUuidApi")
    }
}

dependencies {
    implementation("com.cronutils:cron-utils:9.2.1")
    api(project(":modules:identity-access"))
    api(project(":modules:connector"))
    api(project(":modules:semantic"))
    api(project(":modules:execution-runtime"))
    implementation(platform("org.springframework.boot:spring-boot-dependencies:4.0.7"))
    implementation("org.springframework:spring-jdbc")
    implementation("org.postgresql:postgresql:42.7.5")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
    implementation("org.slf4j:slf4j-api")
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.named<Test>("test") {
    inputs.file(rootProject.layout.projectDirectory.file("../testkit/dashboard-d10/dashboard.json"))
}
