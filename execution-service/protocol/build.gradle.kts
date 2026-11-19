import com.google.protobuf.gradle.GenerateProtoTask
import com.google.protobuf.gradle.id

plugins {
    kotlin("jvm")
    id("com.google.protobuf")
}

val pinnedProtoc = configurations.create("pinnedProtoc") {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    add("pinnedProtoc", "com.google.protobuf:protoc:4.32.0:linux-x86_64@exe")
    api("com.google.protobuf:protobuf-kotlin:4.32.0")
    api("io.grpc:grpc-kotlin-stub:1.5.0")
    api("io.grpc:grpc-protobuf:1.77.0")
    implementation("io.grpc:grpc-stub:1.77.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    compileOnly("javax.annotation:javax.annotation-api:1.3.2")
    testImplementation(kotlin("test"))
}

sourceSets {
    main {
        proto {
            srcDir(rootProject.layout.projectDirectory.dir("../protos"))
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

val resolvePinnedProtoc by tasks.registering {
    val outputFile = layout.buildDirectory.file("pinned-protoc/protoc")
    outputs.file(outputFile)
    doLast {
        val target = outputFile.get().asFile
        target.parentFile.mkdirs()
        pinnedProtoc.singleFile.copyTo(target, overwrite = true)
        check(target.setExecutable(true, true)) {
            "pinned protoc must be executable"
        }
    }
}

val verifyRootProtoContract by tasks.registering {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    dependsOn(tasks.withType<GenerateProtoTask>())
    dependsOn(tasks.named("test"))
    val rootProto = rootProject.layout.projectDirectory.file("../protos/execution_plane.proto")
    inputs.file(rootProto)

    doLast {
        val copiedProtoFiles =
            rootProject.fileTree(rootProject.projectDir) {
                include("**/*.proto")
                exclude("**/build/**")
            }.files
        check(copiedProtoFiles.isEmpty()) {
            "execution-service must generate from ../protos/execution_plane.proto without a copied proto"
        }

        val contract = rootProto.asFile.readText()
        listOf(
            "service ExecutionPlane",
            "rpc Run(stream ControlMessage) returns (stream ExecutionEvent)",
            "rpc ValidateRawSql(ValidateRawSqlRequest)",
            "message ValidateRawSqlRequest",
            "message ValidateRawSqlResponse",
            "message RawSqlAccepted",
            "message RawSqlValidationFailed",
            "message ControlAssignment",
            "bytes credential_document = 5",
            "message QueryRequest",
            "message ConnectorRuntimeBinding",
            "PHYSICAL_OUTCOME_KIND_SUCCEEDED",
            "PHYSICAL_OUTCOME_KIND_FAILED",
            "PHYSICAL_OUTCOME_KIND_CANCELLED",
            "message RunProgress",
            "message ArrowResultManifest",
            "message InlineResultBatch",
            "bytes arrow_ipc = 2",
            "string sha256 = 3",
            "cc.monomer.logical_type = DECIMAL",
            "cc.monomer.physical_encoding = UTF8_CANONICAL_DECIMAL",
        ).forEach { requiredFragment ->
            check(contract.contains(requiredFragment)) {
                "root execution contract is missing $requiredFragment"
            }
        }
        listOf(
            "rpc AnalyzeRawSql",
            "message RelationAddress",
            "message ReadQuery",
            "rpc ResolveDirectRelations",
            "message ResolveDirectRelationsRequest",
            "message ResolveDirectRelationsResponse",
            "message DirectRelationsResolved",
            "message DirectRelationsResolutionFailed",
            "rpc OpenWorkerControl",
            "rpc Open" + "Attempt",
            "rpc MaterializeCredential",
            "message CredentialMaterializationRequest",
            "message SignedCapabilityEnvelope",
            "message ReadyToEnter",
            "message WorkerRegistration",
            "message ConnectorProbeResult",
            "PHYSICAL_OUTCOME_KIND_UNKNOWN_ORPHANED",
            "Preview" + "Batch",
            "Run" + "Attempt",
            "message ExternalEntryGrant",
            "message ExternalEntryDenial",
            "message RawSqlReference",
        ).forEach { forbiddenFragment ->
            check(!contract.contains(forbiddenFragment)) {
                "root execution contract must not contain $forbiddenFragment"
            }
        }
    }
}

tasks.named("check") {
    dependsOn(verifyRootProtoContract)
}
