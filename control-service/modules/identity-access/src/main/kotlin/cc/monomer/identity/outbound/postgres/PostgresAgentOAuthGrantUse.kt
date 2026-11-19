package cc.monomer.identity.outbound.postgres

import cc.monomer.identity.domain.model.AgentOAuthGrantUse
import java.sql.SQLException
import java.sql.Timestamp
import java.time.Instant
import javax.sql.DataSource
import kotlin.uuid.Uuid
import kotlin.uuid.toJavaUuid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

private val grantUseLog = LoggerFactory.getLogger("cc.monomer.identity.outbound.postgres.AgentOAuthGrantUse")

/**
 * Records an authenticated grant's use on its own autocommit connection, outside any caller transaction.
 * A grant row another transaction holds is skipped rather than awaited, at most one write per interval
 * succeeds, and a failure is logged without the credential so authentication never fails because of it.
 */
internal suspend fun recordAgentGrantUse(dataSource: DataSource, grantId: Uuid, now: Instant): Unit = withContext(Dispatchers.IO) {
    try {
        dataSource.connection.use { connection ->
            connection.autoCommit = true
            connection.isReadOnly = false
            connection.prepareStatement(
                """UPDATE identity_runtime.agent_oauth_grants SET last_used_at=?
                    WHERE id=(SELECT id FROM identity_runtime.agent_oauth_grants
                        WHERE id=? AND revoked_at IS NULL AND (last_used_at IS NULL OR last_used_at<=?)
                        FOR UPDATE SKIP LOCKED)""",
            ).use { statement ->
                statement.setTimestamp(1, Timestamp.from(now))
                statement.setObject(2, grantId.toJavaUuid())
                statement.setTimestamp(3, Timestamp.from(AgentOAuthGrantUse.recordingThreshold(now)))
                statement.executeUpdate()
            }
        }
    } catch (failure: SQLException) {
        grantUseLog.warn("Could not record use of agent connection {}: {} (SQLState {})", grantId, failure.javaClass.simpleName, failure.sqlState)
    }
}
