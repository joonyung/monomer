description = "Agent Workspace bounded context"

dependencies {
    api(project(":modules:connector"))
    api(project(":modules:identity-access"))
    implementation(platform("org.springframework.boot:spring-boot-dependencies:4.0.7"))
    implementation("com.fasterxml.jackson.core:jackson-databind:2.21.4")
    implementation("com.networknt:json-schema-validator:1.5.9")
    implementation("io.github.erdtman:java-json-canonicalization:1.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}

tasks.processResources {
    from(rootProject.file("modules/connector/src/main/resources/schema")) {
        include("connector.schema.json")
        into("contracts/connector")
    }
    from(rootProject.file("../contracts")) {
        include("agent-workspace.schema.json", "problem-details.schema.json", "metric-display.schema.json")
        into("contracts")
    }
    from(rootProject.file("modules/dashboard/src/main/resources/schema")) {
        include("dashboard.schema.json")
        into("contracts/dashboard")
    }
    from(rootProject.file("modules/semantic/src/main/resources/schema/semantic")) {
        include("model.schema.json", "metric.schema.json", "query.schema.json")
        into("contracts/semantic")
    }
}
