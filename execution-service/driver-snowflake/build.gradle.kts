plugins {
    kotlin("jvm")
}

dependencies {
    api(project(":driver-jdbc-core"))
    implementation("net.snowflake:snowflake-jdbc:3.20.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    testImplementation("com.fasterxml.jackson.core:jackson-databind:2.21.4")
    testImplementation(platform("org.apache.arrow:arrow-bom:18.3.0"))
    testImplementation("org.apache.arrow:arrow-vector")
    testRuntimeOnly("org.apache.arrow:arrow-memory-unsafe")
    testImplementation(kotlin("test"))
    testImplementation(testFixtures(project(":driver-jdbc-core")))
}

tasks.named<Test>("test") {
    jvmArgs("--add-opens=java.base/java.nio=ALL-UNNAMED")
}
