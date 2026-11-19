plugins {
    kotlin("jvm")
}

dependencies {
    api(project(":driver-spi"))
    implementation(platform("org.apache.arrow:arrow-bom:18.3.0"))
    implementation("org.apache.arrow:arrow-vector")
    runtimeOnly("org.apache.arrow:arrow-memory-unsafe")
    testImplementation(kotlin("test"))
}

tasks.withType<Test>().configureEach {
    jvmArgs("--add-opens=java.base/java.nio=ALL-UNNAMED")
}
