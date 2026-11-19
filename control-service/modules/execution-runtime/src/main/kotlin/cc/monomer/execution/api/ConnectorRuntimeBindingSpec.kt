package cc.monomer.execution.api

sealed interface ConnectorRuntimePropertyValue {
    data class Text(val value: String) : ConnectorRuntimePropertyValue

    data class Integer(val value: Long) : ConnectorRuntimePropertyValue
}

data class ManagedCredentialDispatchReference(
    val credentialVersion: CredentialVersionReference,
    val keyVersion: String,
    val envelopeDigest: Sha256Digest
) {
    init {
        require(keyVersion.isNotBlank())
    }
}

data class ConnectorRuntimeBindingSpec(
    val connectorId: ConnectorReference,
    val connectorType: ConnectorType,
    val properties: Map<String, ConnectorRuntimePropertyValue>,
    val managedCredential: ManagedCredentialDispatchReference
) {
    init {
        require(properties.isNotEmpty())
        require(properties.keys.none(String::isBlank))
    }
}
