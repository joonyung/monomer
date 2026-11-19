package cc.monomer.connector.api

import kotlinx.serialization.json.*

sealed interface ConnectorCredential {
    val kind: String
    class SampleDatasetAccess(
        val dataset: String,
    ) : ConnectorCredential {
        override val kind = "SAMPLE_DATASET_ACCESS"
        init { require(dataset in setOf("acme-cloud@1", "acme-cloud@2", "acme-cloud@3")) { "Unsupported sample dataset" } }
        override fun toString(): String = "SampleDatasetAccess(dataset=$dataset)"
    }
    class UsernamePassword(
        val username: String,
        val password: String,
    ) : ConnectorCredential {
        override val kind = "USERNAME_PASSWORD"
        init { require(username.isNotBlank() && password.isNotBlank()) { "Credential fields must not be empty" } }
        override fun toString(): String = "[REDACTED]"
    }
    class ServiceAccountJson(
        val serviceAccountJson: String,
    ) : ConnectorCredential {
        override val kind = "SERVICE_ACCOUNT_JSON"
        init { require(serviceAccountJson.isNotBlank()) { "Credential fields must not be empty" } }
        override fun toString(): String = "[REDACTED]"
    }
    class UsernamePrivateKey(
        val username: String,
        val privateKey: String,
        val privateKeyPassphrase: String?,
    ) : ConnectorCredential {
        override val kind = "USERNAME_PRIVATE_KEY"
        init { require(username.isNotBlank() && privateKey.isNotBlank() && (privateKeyPassphrase == null || privateKeyPassphrase.isNotBlank())) { "Credential fields must not be empty" } }
        override fun toString(): String = "[REDACTED]"
    }
    class AwsIam(
        val awsAccessKeyId: String,
        val awsSecretAccessKey: String,
        val awsRegion: String,
        val clusterId: String,
        val dbUser: String,
    ) : ConnectorCredential {
        override val kind = "AWS_IAM"
        init { require(awsAccessKeyId.isNotBlank() && awsSecretAccessKey.isNotBlank() && awsRegion.isNotBlank() && clusterId.isNotBlank() && dbUser.isNotBlank()) { "Credential fields must not be empty" } }
        override fun toString(): String = "[REDACTED]"
    }
    class AccessToken(
        val accessToken: String,
    ) : ConnectorCredential {
        override val kind = "ACCESS_TOKEN"
        init { require(accessToken.isNotBlank()) { "Credential fields must not be empty" } }
        override fun toString(): String = "[REDACTED]"
    }
    class OauthClient(
        val oauthClientId: String,
        val oauthClientSecret: String,
    ) : ConnectorCredential {
        override val kind = "OAUTH_CLIENT"
        init { require(oauthClientId.isNotBlank() && oauthClientSecret.isNotBlank()) { "Credential fields must not be empty" } }
        override fun toString(): String = "[REDACTED]"
    }
}

object ConnectorCredentialCodec {
    fun decode(value: JsonObject): ConnectorCredential {
        val kind = value.text("kind")
        val fields = when (kind) {
            "SAMPLE_DATASET_ACCESS" -> setOf("kind", "dataset")
            "USERNAME_PASSWORD" -> setOf("kind", "username", "password")
            "SERVICE_ACCOUNT_JSON" -> setOf("kind", "serviceAccountJson")
            "USERNAME_PRIVATE_KEY" -> setOf("kind", "username", "privateKey", "privateKeyPassphrase")
            "AWS_IAM" -> setOf("kind", "awsAccessKeyId", "awsSecretAccessKey", "awsRegion", "clusterId", "dbUser")
            "ACCESS_TOKEN" -> setOf("kind", "accessToken")
            "OAUTH_CLIENT" -> setOf("kind", "oauthClientId", "oauthClientSecret")
            else -> throw IllegalArgumentException("Credential kind is invalid")
        }
        require(value.keys.all { it in fields }) { "Credential contains an unknown field" }
        return when (kind) {
            "SAMPLE_DATASET_ACCESS" -> ConnectorCredential.SampleDatasetAccess(value.text("dataset"))
            "USERNAME_PASSWORD" -> ConnectorCredential.UsernamePassword(value.text("username"), value.text("password"))
            "SERVICE_ACCOUNT_JSON" -> ConnectorCredential.ServiceAccountJson(value.text("serviceAccountJson"))
            "USERNAME_PRIVATE_KEY" -> ConnectorCredential.UsernamePrivateKey(value.text("username"), value.text("privateKey"), if ("privateKeyPassphrase" in value) value.text("privateKeyPassphrase") else null)
            "AWS_IAM" -> ConnectorCredential.AwsIam(value.text("awsAccessKeyId"), value.text("awsSecretAccessKey"), value.text("awsRegion"), value.text("clusterId"), value.text("dbUser"))
            "ACCESS_TOKEN" -> ConnectorCredential.AccessToken(value.text("accessToken"))
            "OAUTH_CLIENT" -> ConnectorCredential.OauthClient(value.text("oauthClientId"), value.text("oauthClientSecret"))
            else -> throw IllegalArgumentException("Credential kind is invalid")
        }
    }
}

internal fun ConnectorCredential.requireCompatible(type: ConnectorProductType) {
    val compatible = when (type) {
        ConnectorProductType.DUCKDB -> this is ConnectorCredential.SampleDatasetAccess
        ConnectorProductType.POSTGRESQL, ConnectorProductType.TRINO -> this is ConnectorCredential.UsernamePassword
        ConnectorProductType.BIGQUERY -> this is ConnectorCredential.ServiceAccountJson
        ConnectorProductType.SNOWFLAKE -> this is ConnectorCredential.UsernamePassword || this is ConnectorCredential.UsernamePrivateKey
        ConnectorProductType.REDSHIFT -> this is ConnectorCredential.UsernamePassword || this is ConnectorCredential.AwsIam
        ConnectorProductType.DATABRICKS -> this is ConnectorCredential.AccessToken || this is ConnectorCredential.OauthClient
    }
    require(compatible) { "Credential kind does not match connector type" }
}
