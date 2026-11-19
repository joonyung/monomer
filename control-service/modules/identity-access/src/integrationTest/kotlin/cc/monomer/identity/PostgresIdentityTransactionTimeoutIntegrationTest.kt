package cc.monomer.identity

import cc.monomer.identity.outbound.postgres.SpringConsistentSnapshotTransactionRunner
import cc.monomer.identity.outbound.postgres.SpringTransactionRunner
import java.sql.SQLException
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.ConnectionHolder
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.SingleConnectionDataSource
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate

class PostgresIdentityTransactionTimeoutIntegrationTest {
    private val dataSource get() = TargetIdentityPostgres.dataSource
    private val jdbc get() = JdbcTemplate(dataSource)

    @BeforeEach
    fun setUp() {
        dataSource.connection.use { connection ->
            TargetIdentityPostgres.applyCanonicalSchemaTwice(connection, javaClass.classLoader)
        }
    }

    @ParameterizedTest
    @EnumSource(TransactionMode::class)
    fun `every identity owner transaction applies local database and transaction bounds`(mode: TransactionMode) = runBlocking<Unit> {
        execute(mode, DataSourceTransactionManager(dataSource)) {
            assertThat(jdbc.queryForObject("SHOW statement_timeout", String::class.java)).isEqualTo("4s")
            assertThat(jdbc.queryForObject("SHOW lock_timeout", String::class.java)).isEqualTo("1s")
            val holder = TransactionSynchronizationManager.getResource(dataSource) as ConnectionHolder
            assertThat(holder.hasTimeout()).isTrue()
            assertThat(holder.timeToLiveInMillis).isBetween(1L, 5_000L)
        }
    }

    @ParameterizedTest
    @EnumSource(TransactionMode::class)
    fun `database lock wait fails within the bound without snapshot retries`(mode: TransactionMode) {
        dataSource.connection.use { blockingConnection ->
            blockingConnection.autoCommit = false
            try {
                blockingConnection.createStatement().use { statement ->
                    statement.execute("LOCK TABLE identity_runtime.users IN ACCESS EXCLUSIVE MODE")
                }
                var actionInvocationCount = 0
                val startedAt = Instant.now()
                val failure = catchThrowable {
                    runBlocking {
                        execute(mode, DataSourceTransactionManager(dataSource)) {
                            actionInvocationCount += 1
                            jdbc.queryForObject("SELECT count(*) FROM identity_runtime.users", Long::class.java)
                        }
                    }
                }
                val elapsed = Duration.between(startedAt, Instant.now())

                assertThat(failure).isNotNull()
                val sqlFailure = generateSequence(failure) { it.cause }.filterIsInstance<SQLException>().first()
                assertThat(sqlFailure.sqlState).isEqualTo("55P03")
                assertThat(elapsed).isBetween(Duration.ofMillis(900), Duration.ofSeconds(5))
                assertThat(actionInvocationCount).isEqualTo(1)
            } finally {
                blockingConnection.rollback()
            }
        }
    }

    @ParameterizedTest
    @EnumSource(TransactionMode::class)
    fun `owner database bounds are restored when the same connection is reused`(mode: TransactionMode) = runBlocking<Unit> {
        dataSource.connection.use { connection ->
            val reusedDataSource = SingleConnectionDataSource(connection, true)
            val reusedJdbc = JdbcTemplate(reusedDataSource)
            val originalStatementTimeout = reusedJdbc.queryForObject("SHOW statement_timeout", String::class.java)
            val originalLockTimeout = reusedJdbc.queryForObject("SHOW lock_timeout", String::class.java)
            execute(mode, DataSourceTransactionManager(reusedDataSource)) {
                assertThat(reusedJdbc.queryForObject("SHOW statement_timeout", String::class.java)).isEqualTo("4s")
                assertThat(reusedJdbc.queryForObject("SHOW lock_timeout", String::class.java)).isEqualTo("1s")
            }
            assertThat(reusedJdbc.queryForObject("SHOW statement_timeout", String::class.java)).isEqualTo(originalStatementTimeout)
            assertThat(reusedJdbc.queryForObject("SHOW lock_timeout", String::class.java)).isEqualTo(originalLockTimeout)
        }
    }

    @Test
    fun `nested identity runners preserve the existing owner's database bounds`() = runBlocking<Unit> {
        val manager = DataSourceTransactionManager(dataSource)
        val ownerTemplate = TransactionTemplate(manager).apply {
            isolationLevel = TransactionDefinition.ISOLATION_REPEATABLE_READ
            timeout = 2
        }
        val transactions = transactions(manager)
        val snapshot = snapshot(manager)
        ownerTemplate.execute {
            jdbc.execute("SELECT set_config('statement_timeout', '1500ms', true), set_config('lock_timeout', '500ms', true)")
            runBlocking {
                transactions.run { assertOwnerBounds() }
                transactions.readOnly { assertOwnerBounds() }
                snapshot.run { assertOwnerBounds() }
            }
        }
    }

    private fun assertOwnerBounds() {
        assertThat(jdbc.queryForObject("SHOW statement_timeout", String::class.java)).isEqualTo("1500ms")
        assertThat(jdbc.queryForObject("SHOW lock_timeout", String::class.java)).isEqualTo("500ms")
        val holder = TransactionSynchronizationManager.getResource(dataSource) as ConnectionHolder
        assertThat(holder.timeToLiveInMillis).isBetween(1L, 2_000L)
    }

    private suspend fun execute(mode: TransactionMode, manager: DataSourceTransactionManager, action: () -> Unit) {
        when (mode) {
            TransactionMode.WRITE -> transactions(manager).run(action)
            TransactionMode.READ_ONLY -> transactions(manager).readOnly(action)
            TransactionMode.SNAPSHOT -> snapshot(manager).run { action() }
        }
    }

    private fun transactions(manager: DataSourceTransactionManager) = SpringTransactionRunner(
        TransactionTemplate(manager),
        TransactionTemplate(manager).apply {
            isReadOnly = true
            isolationLevel = TransactionDefinition.ISOLATION_REPEATABLE_READ
        },
        Dispatchers.Unconfined,
    )

    private fun snapshot(manager: DataSourceTransactionManager) = SpringConsistentSnapshotTransactionRunner(
        TransactionTemplate(manager).apply {
            isolationLevel = TransactionDefinition.ISOLATION_REPEATABLE_READ
        },
        Dispatchers.Unconfined,
    )

    enum class TransactionMode { WRITE, READ_ONLY, SNAPSHOT }

    companion object {
        @JvmField
        @RegisterExtension
        internal val database = TargetIdentityPostgres
    }
}
