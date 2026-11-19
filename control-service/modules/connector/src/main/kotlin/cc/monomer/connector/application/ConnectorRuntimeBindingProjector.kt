@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.connector.application

import cc.monomer.connector.api.ConnectorExecutionBinding
import cc.monomer.connector.api.ConnectorRuntimeBindingProjectionPort
import cc.monomer.execution.api.ConnectorReference
import cc.monomer.execution.api.ConnectorRuntimeBindingSpec
import cc.monomer.execution.api.ConnectorRuntimePropertyValue
import cc.monomer.execution.api.ConnectorType
import cc.monomer.execution.api.CredentialVersionReference
import cc.monomer.execution.api.ManagedCredentialDispatchReference
import cc.monomer.execution.api.Sha256Digest

/** Projects the current Connector binding into the operation runtime's execution shape. */
class ConnectorRuntimeBindingProjector : ConnectorRuntimeBindingProjectionPort {
    override fun project(binding: ConnectorExecutionBinding): ConnectorRuntimeBindingSpec {
        val connectorType = ConnectorType.valueOf(binding.connectorType.uppercase())
        return ConnectorRuntimeBindingSpec(
            connectorId = ConnectorReference(binding.connectorId.value),
            connectorType = connectorType,
            properties = binding.properties.entries.associate { (name, value) ->
                toRuntimePropertyName(connectorType, name) to toRuntimeProperty(connectorType, name, value)
            },
            managedCredential = binding.credentialReference.toManagedCredential(),
        )
    }
}

private fun toRuntimePropertyName(
    connectorType: ConnectorType,
    sourceName: String,
): String =
    when (connectorType) {
        ConnectorType.TRINO -> if (sourceName == "sslVerification") "ssl_verification" else sourceName
        ConnectorType.REDSHIFT -> if (sourceName == "sslMode") "ssl_mode" else sourceName
        ConnectorType.DATABRICKS -> if (sourceName == "warehouseId") "warehouse_id" else sourceName
        else -> sourceName
    }

private fun cc.monomer.connector.api.CredentialEnvelopeReference.toManagedCredential():
    ManagedCredentialDispatchReference =
    ManagedCredentialDispatchReference(
        credentialVersion = CredentialVersionReference(credentialVersionId.value),
        keyVersion = keyVersion,
        envelopeDigest = Sha256Digest(envelopeDigest.value.removePrefix("sha256:")),
    )

private fun toRuntimeProperty(
    connectorType: ConnectorType,
    name: String,
    value: String,
): ConnectorRuntimePropertyValue =
    when (connectorType) {
        ConnectorType.DUCKDB -> {
            require(name == "sample_dataset" && value in setOf("acme-cloud@1", "acme-cloud@2", "acme-cloud@3")) { "Unsupported sample dataset" }
            ConnectorRuntimePropertyValue.Text(value)
        }
        ConnectorType.POSTGRESQL ->
            when (name) {
                "port" -> ConnectorRuntimePropertyValue.Integer(requireNotNull(value.toLongOrNull()))
                "database", "host", "schema", "ssl_mode" -> ConnectorRuntimePropertyValue.Text(value)
                else -> throw IllegalArgumentException("unsupported PostgreSQL runtime property: $name")
            }
        ConnectorType.BIGQUERY -> {
            require(name == "location" || name == "project_id") {
                "unsupported BigQuery runtime property: $name"
            }
            ConnectorRuntimePropertyValue.Text(value)
        }
        ConnectorType.SNOWFLAKE ->
            when (name) {
                "account", "warehouse", "database", "schema", "role" -> ConnectorRuntimePropertyValue.Text(value)
                else -> throw IllegalArgumentException("unsupported Snowflake runtime property: $name")
            }
        ConnectorType.TRINO ->
            when (name) {
                "port" -> ConnectorRuntimePropertyValue.Integer(requireNotNull(value.toLongOrNull()))
                "host", "catalog", "schema", "ssl", "sslVerification" -> ConnectorRuntimePropertyValue.Text(value)
                else -> throw IllegalArgumentException("unsupported Trino runtime property: $name")
            }
        ConnectorType.REDSHIFT ->
            when (name) {
                "port" -> ConnectorRuntimePropertyValue.Integer(requireNotNull(value.toLongOrNull()))
                "host", "database", "schema", "sslMode" -> ConnectorRuntimePropertyValue.Text(value)
                else -> throw IllegalArgumentException("unsupported Redshift runtime property: $name")
            }
        ConnectorType.DATABRICKS ->
            when (name) {
                "host", "catalog", "schema", "warehouseId" -> ConnectorRuntimePropertyValue.Text(value)
                else -> throw IllegalArgumentException("unsupported Databricks runtime property: $name")
            }
    }
