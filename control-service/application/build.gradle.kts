import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.testing.Test
import org.springframework.boot.gradle.tasks.bundling.BootJar
import org.springframework.boot.gradle.tasks.run.BootRun

plugins {
    id("org.springframework.boot")
    kotlin("plugin.spring")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-opt-in=kotlin.uuid.ExperimentalUuidApi")
    }
}

dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:4.0.7"))

    implementation(project(":modules:identity-access"))
    implementation(project(":modules:resource-authority"))
    implementation(project(":modules:execution-runtime"))
    implementation(project(":modules:connector"))
    implementation(project(":modules:semantic"))
    implementation(project(":modules:catalog"))
    implementation(project(":modules:explore"))
    implementation(project(":modules:dashboard"))
    implementation(project(":modules:pages"))
    implementation(project(":modules:agent-workspace"))
    implementation(project(":modules:search"))

    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-graphql")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-client")
    implementation("org.springframework.data:spring-data-commons")
    implementation("org.springframework:spring-jdbc")
    implementation("com.zaxxer:HikariCP")
    implementation("org.postgresql:postgresql:42.7.5")
    implementation(enforcedPlatform("software.amazon.awssdk:bom:2.54.13"))
    implementation("software.amazon.awssdk:auth")
    implementation("software.amazon.awssdk:sesv2")
    implementation("software.amazon.awssdk:signin")
    implementation("software.amazon.awssdk:sts")
    implementation("io.grpc:grpc-netty-shaded:1.77.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-slf4j:1.10.2")
    implementation("io.micrometer:context-propagation")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-jdk8:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("com.networknt:json-schema-validator:1.5.9")
    implementation("com.nimbusds:nimbus-jose-jwt:10.3")
    implementation(platform("org.apache.arrow:arrow-bom:18.3.0"))
    implementation("org.apache.arrow:arrow-vector")
    runtimeOnly("org.apache.arrow:arrow-memory-unsafe")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.springframework.graphql:spring-graphql-test")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("com.google.protobuf:protobuf-java:4.32.0")
    testImplementation("io.mockk:mockk:1.13.16")
    testImplementation("io.projectreactor:reactor-test")
    testImplementation(kotlin("test"))
    testImplementation("org.bouncycastle:bcprov-jdk18on:1.83")
    testImplementation("org.bouncycastle:bcpkix-jdk18on:1.83")
    testImplementation("com.networknt:json-schema-validator:1.5.9")
    testImplementation(testFixtures(project(":modules:connector")))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
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
    add(integrationTestSourceSet.implementationConfigurationName, "software.amazon.awssdk:s3")
    add(
        integrationTestSourceSet.implementationConfigurationName,
        "org.postgresql:postgresql:42.7.5",
    )
    add(
        integrationTestSourceSet.implementationConfigurationName,
        "org.springframework:spring-jdbc",
    )
    add(
        integrationTestSourceSet.implementationConfigurationName,
        "org.springframework:spring-tx",
    )
    add(
        integrationTestSourceSet.implementationConfigurationName,
        "org.testcontainers:testcontainers-postgresql:2.0.5",
    )
}

tasks.register<Test>("integrationTest") {
    inputs.files(
        rootProject.file("../testkit/agent-oauth-client/verify_mcp_protocol.py"),
        rootProject.file("../testkit/agent-oauth-client/verify_pages_mcp.py"),
    )
    inputs.property("pagesMcpInteropPython", providers.environmentVariable("MONOMER_MCP_INTEROP_PYTHON").orElse(""))
    inputs.property("mcpProbeCodex", providers.environmentVariable("MONOMER_MCP_PROBE_CODEX").orElse(""))
    group = "verification"
    description = "Boots Product Control and verifies its real health surface."
    testClassesDirs = integrationTestSourceSet.output.classesDirs
    classpath = integrationTestSourceSet.runtimeClasspath
    useJUnitPlatform()
    filter {
        excludeTestsMatching("cc.monomer.control.PostgresAuthorityPersistenceIntegrationTest")
    }
    shouldRunAfter(tasks.named("test"))
}

tasks.register<Test>("postgresAuthorityGate") {
    group = "verification"
    description = "Verifies Phase 3 authority persistence in disposable PostgreSQL."
    testClassesDirs = integrationTestSourceSet.output.classesDirs
    classpath = integrationTestSourceSet.runtimeClasspath
    useJUnitPlatform()
    filter {
        includeTestsMatching("cc.monomer.control.PostgresAuthorityPersistenceIntegrationTest")
    }
    shouldRunAfter(tasks.named("test"))
}

tasks.withType<Test>().configureEach {
    jvmArgs("--add-opens=java.base/java.nio=ALL-UNNAMED")
}

tasks.withType<BootRun>().configureEach {
    jvmArgs("--add-opens=java.base/java.nio=ALL-UNNAMED")
}

tasks.withType<BootJar>().configureEach {
    archiveFileName.set("control-service.jar")
}

tasks.processResources {
    from(rootProject.file("../testkit/data/duckdb/acme-cloud/versions/1/definitions")) {
        include("semantic-models.json", "metrics.json", "time-spine.json", "revenue-overview.json", "product-usage.json")
        into("sample-data/acme-cloud/1/definitions")
    }
    from(rootProject.file("../testkit/data/duckdb/acme-cloud/versions/2/definitions")) {
        include("semantic-models.json", "metrics.json", "time-spine.json", "revenue-overview.json", "product-usage.json")
        into("sample-data/acme-cloud/2/definitions")
    }
    from(rootProject.file("../testkit/data/duckdb/acme-cloud/definitions")) {
        include("semantic-models.json", "metrics.json", "time-spine.json", "board-metrics.json", "revenue-movements.json", "customer-segments.json", "product-engagement.json")
        into("sample-data/acme-cloud/3/definitions")
    }
    from(rootProject.file("../assets/brand/monomer-favicon.png")) {
        into("brand")
    }
    from(rootProject.file("modules/connector/src/main/resources/schema")) {
        include("connector.schema.json")
        into("contracts/connector")
    }
    from(rootProject.file("../contracts")) {
        include(
            "agent-workspace.schema.json",
            "control-service.openapi.json",
            "problem-details.schema.json",
            "metric-display.schema.json",
        )
        into("contracts")
    }
    from(rootProject.file("modules/semantic/src/main/resources/schema/semantic")) {
        include("model.schema.json", "metric.schema.json", "query.schema.json", "time-spine.schema.json")
        into("contracts/semantic")
    }
    from(rootProject.file("modules/dashboard/src/main/resources/schema")) {
        include("dashboard.schema.json")
        into("contracts/dashboard")
    }
}

tasks.named<Test>("test") {
    inputs.files(
        rootProject.file("../docs/research/2026-08-27-agent-public-rest-api-design.html"),
        rootProject.file("../docs/research/2026-08-27-agent-public-rest-api-request-response-catalog.html"),
    ).withPropertyName("agentResponseDocumentation").withPathSensitivity(PathSensitivity.RELATIVE)
}

tasks.register<JavaExec>("initializeLocalSearch") {
    group = "application"
    description = "Populates derived Search from current owner inventories in the canonical local Control database."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("cc.monomer.control.search.SearchIndexBootstrap")
}

springBoot {
    mainClass.set("cc.monomer.control.ControlApplicationKt")
}
