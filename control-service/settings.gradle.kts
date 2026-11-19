import java.nio.file.Path

rootProject.name = "control-service"

val metricflowRepositoryProperty = "monomerMetricflowRepository"
val metricflowRepositoryEnvironmentVariable = "MONOMER_METRICFLOW_REPOSITORY"
val metricflowRepositoryPath = (
    providers.gradleProperty(metricflowRepositoryProperty).orNull
        ?: providers.environmentVariable(metricflowRepositoryEnvironmentVariable).orNull
    )
    ?.takeIf { repositoryPath -> repositoryPath.isNotBlank() }
    ?: throw GradleException(
        "MetricFlow consumption requires -P$metricflowRepositoryProperty=/absolute/maven/repository " +
            "or $metricflowRepositoryEnvironmentVariable=/absolute/maven/repository",
    )
val metricflowRepositoryInputPath = Path.of(metricflowRepositoryPath)
check(metricflowRepositoryInputPath.isAbsolute) {
    "MetricFlow repository must be supplied as an absolute path"
}
val metricflowRepositoryDirectory = metricflowRepositoryInputPath.normalize().toFile()
check(metricflowRepositoryDirectory.isDirectory) {
    "MetricFlow repository does not exist or is not a directory: $metricflowRepositoryDirectory"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        exclusiveContent {
            forRepository {
                maven {
                    name = "monomerMetricflowPinned"
                    url = metricflowRepositoryDirectory.toURI()
                }
            }
            filter {
                includeGroup("cc.monomer.metricflow")
            }
        }
        mavenCentral {
            content {
                excludeGroup("cc.monomer.metricflow")
            }
        }
    }
}

include(
    ":application",
    ":modules:identity-access",
    ":modules:resource-authority",
    ":modules:execution-runtime",
    ":modules:connector",
    ":modules:semantic",
    ":modules:catalog",
    ":modules:explore",
    ":modules:dashboard",
    ":modules:pages",
    ":modules:agent-workspace",
    ":modules:search",
)
