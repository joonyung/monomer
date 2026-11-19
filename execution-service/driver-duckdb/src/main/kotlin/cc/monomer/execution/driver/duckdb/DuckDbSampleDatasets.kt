package cc.monomer.execution.driver.duckdb

import cc.monomer.execution.driver.spi.ConnectorPropertyValue
import cc.monomer.execution.driver.spi.ConnectorRuntimeBinding
import cc.monomer.execution.driver.spi.ConnectorType
import cc.monomer.execution.driver.spi.MaterializedCredential
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.util.Properties
import org.duckdb.DuckDBConnection

internal class DuckDbSampleDatasets(allowedRoot: Path) : AutoCloseable {
    private val databases: Map<String, DuckDBConnection>

    init {
        val opened = linkedMapOf<String, DuckDBConnection>()
        try {
            val root = allowedRoot.toRealPath()
            SUPPORTED_DATASETS.forEach { (dataset, locator) ->
                val database = root.resolve(locator).toRealPath()
                require(database.startsWith(root) && Files.isRegularFile(database) && Files.isReadable(database)) { "Sample dataset file is unavailable." }
                opened[dataset] = DriverManager.getConnection("jdbc:duckdb:$database", lockdownProperties()).unwrap(DuckDBConnection::class.java)
            }
            databases = opened.toMap()
        } catch (failure: Throwable) {
            opened.values.forEach { runCatching(it::close) }
            throw IllegalStateException("Supported sample datasets could not be opened.", failure)
        }
    }

    fun requireAccess(connector: ConnectorRuntimeBinding, credential: MaterializedCredential?): String {
        require(connector.connectorType == ConnectorType.DUCKDB)
        require(connector.properties.keys == setOf("sample_dataset")) { "Sample Connector properties are invalid." }
        val dataset = (connector.properties["sample_dataset"] as? ConnectorPropertyValue.Text)?.value
        require(dataset in databases) { "Sample dataset is unsupported." }
        val access = requireNotNull(credential) { "Sample dataset access is required." }
        require(access.fieldNames() == setOf("dataset")) { "Sample dataset access fields are invalid." }
        require(access.useSecret("dataset") { String(it) } == dataset) { "Sample dataset access does not match the Connector." }
        return requireNotNull(dataset)
    }

    fun duplicate(dataset: String): DuckDBConnection = requireNotNull(databases[dataset]).duplicate()

    override fun close() { databases.values.forEach(DuckDBConnection::close) }

    private companion object {
        val SUPPORTED_DATASETS = mapOf(
            "acme-cloud@1" to "acme-cloud/1/acme_cloud.duckdb",
            "acme-cloud@2" to "acme-cloud/2/acme_cloud.duckdb",
            "acme-cloud@3" to "acme-cloud/3/acme_cloud.duckdb",
        )

        fun lockdownProperties() = Properties().apply {
            setProperty("duckdb.read_only", "true")
            setProperty("enable_external_access", "false")
            setProperty("autoinstall_known_extensions", "false")
            setProperty("autoload_known_extensions", "false")
            setProperty("allow_community_extensions", "false")
            setProperty("temp_directory", "")
            setProperty("allow_persistent_secrets", "false")
            setProperty("secret_directory", "disabled")
            setProperty("extension_directory", "disabled")
            setProperty("memory_limit", "256MB")
            setProperty("threads", "1")
            setProperty("jdbc_stream_results", "true")
            setProperty("lock_configuration", "true")
        }
    }
}
