import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    base
    kotlin("jvm") version "2.3.20" apply false
    id("com.google.protobuf") version "0.9.5" apply false
}

group = "cc.monomer.execution"
version = "0.1.0"

subprojects {
    group = rootProject.group
    version = rootProject.version

    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        extensions.configure<KotlinJvmProjectExtension> {
            jvmToolchain(25)
        }

        tasks.withType<KotlinCompile>().configureEach {
            compilerOptions {
                jvmTarget.set(JvmTarget.JVM_25)
                allWarningsAsErrors.set(true)
            }
        }

        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
        }
    }
}

val test by tasks.registering {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    dependsOn(subprojects.map { "${it.path}:test" })
}

val dependencyPurityTest by tasks.registering {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    doLast {
        val forbiddenDependencyFragments =
            listOf(
                "control-service",
                "cc.monomer.control",
                "org.springframework",
                "com.zaxxer",
                "org.flywaydb",
                "org.liquibase",
                "org.hibernate",
                "jakarta.persistence",
            )
        val vendorOwners =
            mapOf(
                "org.postgresql" to ":driver-postgresql",
                "com.google.cloud:google-cloud-bigquery" to ":driver-bigquery",
                "com.google.cloud:google-cloud-bigquerystorage" to ":driver-bigquery",
                "com.amazon.redshift" to ":driver-redshift",
                "software.amazon.awssdk:s3" to ":object-storage-s3",
                "io.trino" to ":driver-trino",
                "net.snowflake" to ":driver-snowflake",
                "com.databricks" to ":driver-databricks",
                "org.duckdb" to ":driver-duckdb",
            )

        subprojects.forEach { subproject ->
            subproject.configurations.forEach { configuration ->
                configuration.dependencies.forEach { dependency ->
                    val coordinate =
                        listOfNotNull(dependency.group, dependency.name)
                            .joinToString(":")
                    check(forbiddenDependencyFragments.none(coordinate::contains)) {
                        "${subproject.path} declares forbidden dependency $coordinate"
                    }
                    vendorOwners.forEach { (vendorCoordinate, ownerPath) ->
                        if (coordinate == vendorCoordinate || coordinate.startsWith("$vendorCoordinate:")) {
                            check(subproject.path == ownerPath) {
                                "$coordinate is allowed only in $ownerPath"
                            }
                        }
                    }
                    if (coordinate.startsWith("software.amazon.awssdk:")) {
                        check(subproject.path == ":driver-redshift" || subproject.path == ":object-storage-s3") {
                            "$coordinate is allowed only in :driver-redshift or :object-storage-s3"
                        }
                    }
                }
            }
        }
    }
}

val purityTest by tasks.registering(Exec::class) {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    dependsOn(dependencyPurityTest)
    workingDir(rootDir)
    commandLine("bash", "scripts/verify-purity.sh")
}

val executionFaultTest by tasks.registering {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    dependsOn(":runtime:executionFaultTest", ":application:executionFaultTest")
}

val boundedMemoryTest by tasks.registering {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    dependsOn(":runtime:boundedMemoryTest")
}

val containerArtifactTest by tasks.registering {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    dependsOn(":application:installDist")
    doLast {
        val launcher = file("application/build/install/application/bin/application")
        check(launcher.isFile && launcher.canExecute()) {
            "execution-service installDist launcher is missing or not executable"
        }
        val dockerfile = file("Dockerfile").readText()
        check(
            "FROM --platform=\$BUILDPLATFORM eclipse-temurin:25-jdk@" +
                "sha256:201fbb8886b2d273218aa3a192f0afbf7b5ff65ee8cc6ef47f5dce2171f013ea " +
                "AS build" in dockerfile,
        )
        check(
            "FROM eclipse-temurin:25-jre@" +
                "sha256:681c543d6f36c50f45e9b5226930a46203dcfa351d3670e9d0bdf0dabae53539" +
                "\n" in dockerfile,
        )
        check(!Regex("""(?m)^ARG\s+.*(?:IMAGE|TAG|DIGEST)""").containsMatchIn(dockerfile))
        check("USER 65532:65532" in dockerfile)
        check("ENTRYPOINT [\"/opt/monomer/execution-service/bin/application\"]" in dockerfile)
        check("control-service" !in dockerfile)
    }
}

tasks.named("check") {
    dependsOn(
        test,
        purityTest,
        executionFaultTest,
        boundedMemoryTest,
        containerArtifactTest,
        ":protocol:verifyRootProtoContract",
    )
}

tasks.register("confidenceSweep") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Manually runs check plus all Execution integration and PostgreSQL verification gates."
    dependsOn("check")
    dependsOn(":driver-postgresql:postgresSecurityGate")
}
