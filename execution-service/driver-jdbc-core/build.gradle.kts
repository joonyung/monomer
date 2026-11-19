plugins {
    kotlin("jvm")
    `java-test-fixtures`
}

dependencies {
    api(project(":driver-spi"))
    api(project(":arrow"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    testFixturesImplementation(kotlin("test"))
    testFixturesImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation(platform("org.apache.arrow:arrow-bom:18.3.0"))
    testImplementation("org.apache.arrow:arrow-vector")
    testRuntimeOnly("org.apache.arrow:arrow-memory-unsafe")
}

tasks.withType<Test>().configureEach {
    jvmArgs("--add-opens=java.base/java.nio=ALL-UNNAMED")
}
