plugins {
    kotlin("jvm")
}

dependencies {
    api(project(":driver-spi"))
    implementation(project(":arrow"))
    implementation(platform("com.google.cloud:libraries-bom:26.55.0"))
    implementation("com.google.cloud:google-cloud-bigquery")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}

tasks.withType<Test>().configureEach {
    jvmArgs("--add-opens=java.base/java.nio=ALL-UNNAMED")
}
