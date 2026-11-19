package cc.monomer.connector.application

import cc.monomer.connector.api.ConnectorCredential

internal fun ConnectorCredential.toMaterial(): CredentialMaterial {
    val values = when (this) {
        is ConnectorCredential.SampleDatasetAccess -> mapOf("dataset" to dataset)
        is ConnectorCredential.UsernamePassword -> buildMap {
            put("username", username)
            put("password", password)
        }
        is ConnectorCredential.ServiceAccountJson -> buildMap {
            put("service_account_json", serviceAccountJson)
        }
        is ConnectorCredential.UsernamePrivateKey -> buildMap {
            put("username", username)
            put("privateKey", privateKey)
            privateKeyPassphrase?.let { put("privateKeyPassphrase", it) }
        }
        is ConnectorCredential.AwsIam -> buildMap {
            put("awsAccessKeyId", awsAccessKeyId)
            put("awsSecretAccessKey", awsSecretAccessKey)
            put("awsRegion", awsRegion)
            put("clusterId", clusterId)
            put("dbUser", dbUser)
        }
        is ConnectorCredential.AccessToken -> buildMap {
            put("accessToken", accessToken)
        }
        is ConnectorCredential.OauthClient -> buildMap {
            put("oauthClientId", oauthClientId)
            put("oauthClientSecret", oauthClientSecret)
        }
    }.mapValues { (_, value) -> value.toByteArray(Charsets.UTF_8) }
    return try {
        CredentialMaterial.copyOf(values)
    } finally {
        values.values.forEach { it.fill(0) }
    }
}
