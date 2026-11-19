package cc.monomer.execution.application

import cc.monomer.execution.driver.duckdb.DuckDbExecutionDriverProvider
import cc.monomer.execution.driver.spi.ConnectorId
import cc.monomer.execution.driver.spi.ConnectorPropertyValue
import cc.monomer.execution.driver.spi.ConnectorRuntimeBinding
import cc.monomer.execution.driver.spi.ConnectorType
import cc.monomer.execution.driver.spi.DriverOperation
import cc.monomer.execution.driver.spi.DriverOperationTiming
import cc.monomer.execution.driver.spi.MaterializedCredential
import cc.monomer.execution.driver.spi.SecretCharacters
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.time.Clock
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

class LocalSampleDataStartupTest {
    @Test
    fun `prepared local sample opens through the production read-only driver`() = runBlocking {
        val root = Path.of(requireNotNull(System.getProperty("execution.local.sample.root")))
        DuckDbExecutionDriverProvider(32, root, Dispatchers.IO, Clock.systemUTC()).use { provider ->
            for (version in listOf(1, 2, 3)) {
                val database = root.resolve("acme-cloud/$version/acme_cloud.duckdb")
                assertFalse(Files.getPosixFilePermissions(database).any { it in setOf(PosixFilePermission.OWNER_WRITE, PosixFilePermission.GROUP_WRITE, PosixFilePermission.OTHERS_WRITE) })
                val dataset = "acme-cloud@$version"
                val connector = ConnectorRuntimeBinding(1u, ConnectorId.parse("019f0000-0000-7000-8000-000000000003"), ConnectorType.DUCKDB, mapOf("sample_dataset" to ConnectorPropertyValue.Text(dataset)))
                MaterializedCredential(mapOf("dataset" to SecretCharacters.copyOf(dataset.toCharArray()))).use { credential ->
                    provider.validateRawSql(connector, credential, "SELECT count(*) FROM acme.monthly_account_mrr", emptyList(), System.currentTimeMillis() + 60_000L,
                        DriverOperationTiming(ConnectorType.DUCKDB, DriverOperation.VALIDATE, "019f0000-0000-7000-8000-000000000001", "019f0000-0000-7000-8000-000000000002"))
                }
            }
        }
    }
}
