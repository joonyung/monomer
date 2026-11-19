plugins {
    kotlin("jvm")
}

dependencies {
    api(project(":driver-jdbc-core"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.21.4")
    implementation("org.duckdb:duckdb_jdbc:1.5.4.0")
    testImplementation(kotlin("test"))
    testImplementation(platform("org.apache.arrow:arrow-bom:18.3.0"))
    testImplementation("org.apache.arrow:arrow-vector")
    testImplementation(testFixtures(project(":driver-jdbc-core")))

}

tasks.named<Test>("test") {
    jvmArgs("--add-opens=java.base/java.nio=ALL-UNNAMED")
}
