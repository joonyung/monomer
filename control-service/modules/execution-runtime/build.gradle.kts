import java.io.File
import com.google.protobuf.gradle.id
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.testing.Test

plugins {
    id("com.google.protobuf")
}

description = "Execution Runtime bounded context"

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-opt-in=kotlin.uuid.ExperimentalUuidApi")
    }
}

dependencies {
    api(project(":modules:identity-access"))
    implementation(platform("org.springframework.boot:spring-boot-dependencies:4.0.7"))
    implementation("org.springframework:spring-jdbc")
    implementation(enforcedPlatform("software.amazon.awssdk:bom:2.31.10"))
    implementation("software.amazon.awssdk:s3")
    implementation("software.amazon.awssdk:url-connection-client")
    implementation(platform("io.grpc:grpc-bom:1.77.0"))
    implementation("com.google.protobuf:protobuf-kotlin:4.32.0")
    implementation("io.grpc:grpc-kotlin-stub:1.5.0")
    implementation("io.grpc:grpc-protobuf")
    implementation("io.grpc:grpc-stub")
    implementation("io.grpc:grpc-netty-shaded")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    compileOnly("javax.annotation:javax.annotation-api:1.3.2")
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    implementation("org.postgresql:postgresql:42.7.5")
    testImplementation("org.testcontainers:testcontainers:2.0.5")
    testImplementation("org.bouncycastle:bcprov-jdk18on:1.83")
    testImplementation("org.bouncycastle:bcpkix-jdk18on:1.83")
    testImplementation("io.grpc:grpc-testing")
    testImplementation("io.grpc:grpc-inprocess")
}

sourceSets {
    main {
        proto {
            setSrcDirs(listOf(rootProject.file("../protos")))
            include("execution_plane.proto")
        }
    }
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:4.32.0"
    }
    plugins {
        id("grpc") {
            artifact = "io.grpc:protoc-gen-grpc-java:1.77.0"
        }
        id("grpckt") {
            artifact = "io.grpc:protoc-gen-grpc-kotlin:1.5.0:jdk8@jar"
        }
    }
    generateProtoTasks {
        all().configureEach {
            builtins {
                id("kotlin")
            }
            plugins {
                id("grpc")
                id("grpckt")
            }
        }
    }
}

val executionRuntimeSourceSets = extensions.getByType<SourceSetContainer>()
val executionRuntimeTestSourceSet = executionRuntimeSourceSets.test.get()
val executionRuntimeGates =
    mapOf(
        "executionRuntimeSchemaGate" to
            "cc.monomer.execution.ExecutionRuntimeSchemaGateTest",
    ).map { (taskName, testClass) ->
        tasks.register<Test>(taskName) {
            group = "verification"
            testClassesDirs = executionRuntimeTestSourceSet.output.classesDirs
            classpath = executionRuntimeTestSourceSet.runtimeClasspath
            filter {
                includeTestsMatching(testClass)
            }
        }
    }

val integrationTestSourceSet = executionRuntimeSourceSets.create("integrationTest")
integrationTestSourceSet.compileClasspath +=
    executionRuntimeSourceSets.getByName("main").output + executionRuntimeSourceSets.getByName("test").output
integrationTestSourceSet.runtimeClasspath +=
    executionRuntimeSourceSets.getByName("main").output + executionRuntimeSourceSets.getByName("test").output

configurations[integrationTestSourceSet.implementationConfigurationName]
    .extendsFrom(configurations.testImplementation.get())
configurations[integrationTestSourceSet.runtimeOnlyConfigurationName]
    .extendsFrom(configurations.testRuntimeOnly.get())
dependencies {
    add(
        integrationTestSourceSet.implementationConfigurationName,
        "org.testcontainers:testcontainers-postgresql:2.0.5",
    )
}

tasks.register<Test>("integrationTest") {
    group = "verification"
    description = "Runs Execution Runtime PostgreSQL integration tests."
    filter { excludeTestsMatching("*ControlExecutionRunQueryIntegrationTest") }
    testClassesDirs = integrationTestSourceSet.output.classesDirs
    classpath = integrationTestSourceSet.runtimeClasspath
    useJUnitPlatform()
    shouldRunAfter(tasks.named("test"))
}

tasks.named("check") {
    dependsOn(executionRuntimeGates)
}

// Execution remains an independent distribution launched only across a process boundary.
dependencies {
    add(integrationTestSourceSet.implementationConfigurationName, "org.apache.arrow:arrow-vector:18.3.0")
    add(integrationTestSourceSet.runtimeOnlyConfigurationName, "org.apache.arrow:arrow-memory-unsafe:18.3.0")
}

tasks.register<Test>("controlExecutionRunQueryGate") {
    group = "verification"
    description = "Dials an independent production Execution JVM using the Control client and disposable PostgreSQL."
    testClassesDirs = integrationTestSourceSet.output.classesDirs
    classpath = integrationTestSourceSet.runtimeClasspath
    filter { includeTestsMatching("*ControlExecutionRunQueryIntegrationTest") }
    jvmArgs("--add-opens=java.base/java.nio=ALL-UNNAMED")
    val distributionPath = providers.gradleProperty("monomerExecutionDistribution")
    inputs.property("executionDistribution", distributionPath).optional(true)
    doFirst {
        val distribution = File(requireNotNull(distributionPath.orNull) {
            "controlExecutionRunQueryGate requires -PmonomerExecutionDistribution=<absolute distribution directory>"
        })
        require(distribution.isAbsolute && distribution.isDirectory) {
            "monomerExecutionDistribution must name an existing absolute distribution directory"
        }
        systemProperty("execution.distribution", distribution.absolutePath)
    }
    systemProperty("execution.process.evidence", layout.buildDirectory.dir("control-execution-run-query").get().asFile.absolutePath)
    outputs.upToDateWhen { false }
}
