pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

rootProject.name = "monomer-execution-service"

include(
    "protocol",
    "driver-spi",
    "arrow",
    "runtime",
    "object-storage-s3",
    "driver-jdbc-core",
    "driver-postgresql",
    "driver-bigquery",
    "driver-redshift",
    "driver-trino",
    "driver-snowflake",
    "driver-databricks",
    "driver-duckdb",
    "application",
)
