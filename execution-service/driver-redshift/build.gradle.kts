plugins {
    kotlin("jvm")
}

dependencies {
    api(project(":driver-jdbc-core"))
    implementation(platform("software.amazon.awssdk:bom:2.29.45"))
    implementation("software.amazon.awssdk:redshift")
    implementation("com.amazon.redshift:redshift-jdbc42:2.1.0.32")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    testImplementation(kotlin("test"))
    testImplementation(testFixtures(project(":driver-jdbc-core")))
    testImplementation(platform("org.apache.arrow:arrow-bom:18.3.0"))
    testImplementation("org.apache.arrow:arrow-vector")
    testImplementation("com.fasterxml.jackson.core:jackson-databind:2.21.4")
}

tasks.withType<Test>().configureEach {
    jvmArgs("--add-opens=java.base/java.nio=ALL-UNNAMED")
}
