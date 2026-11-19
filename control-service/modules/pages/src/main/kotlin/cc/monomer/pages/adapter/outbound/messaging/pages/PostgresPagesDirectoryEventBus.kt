package cc.monomer.pages.adapter.outbound.messaging.pages

import cc.monomer.pages.application.PagesDirectoryEventBus
import cc.monomer.pages.application.PagesDirectoryWakeup
import cc.monomer.pages.adapter.outbound.messaging.InstanceIdHolder
import cc.monomer.pages.adapter.outbound.messaging.PostgresNotificationListener
import cc.monomer.pages.adapter.outbound.messaging.afterCommit
import jakarta.annotation.PostConstruct
import kotlin.uuid.Uuid
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate

class PostgresPagesDirectoryEventBus(
    @param:Qualifier("pagesJdbcTemplate") private val jdbc: NamedParameterJdbcTemplate,
    private val listener: PostgresNotificationListener,
    instanceIdHolder: InstanceIdHolder,
) : PagesDirectoryEventBus {
    private val log = LoggerFactory.getLogger(javaClass)
    private val instanceId = instanceIdHolder.value
    private val json = Json { ignoreUnknownKeys = false; encodeDefaults = false }
    private val wakeups = MutableSharedFlow<PagesDirectoryWakeup>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    @PostConstruct
    fun register() {
        listener.registerHandler(CHANNEL) { payload -> onNotification(payload) }
    }

    override fun publish(wakeup: PagesDirectoryWakeup) {
        val envelope = NotifyEnvelope(
            instance = instanceId,
            workspaceId = wakeup.workspaceId.toString(),
        )
        val payload = json.encodeToString(NotifyEnvelope.serializer(), envelope)
        jdbc.jdbcTemplate.queryForRowSet("SELECT pg_notify(?, ?)", CHANNEL, payload)
        afterCommit { wakeups.tryEmit(wakeup) }
    }

    override fun subscribe(workspaceId: Uuid): Flow<PagesDirectoryWakeup> =
        wakeups.filter { wakeup -> wakeup.workspaceId == workspaceId }

    private fun onNotification(payload: String) {
        val envelope = runCatching { json.decodeFromString(NotifyEnvelope.serializer(), payload) }
            .onFailure { exception ->
                log.atWarn()
                    .addKeyValue("event", "pages_directory_notification_malformed")
                    .setCause(exception)
                    .log("pages_directory_notification_malformed")
            }
            .getOrNull() ?: return
        if (envelope.instance == instanceId) return
        val workspaceId = runCatching { Uuid.parse(envelope.workspaceId) }.getOrNull() ?: return
        wakeups.tryEmit(PagesDirectoryWakeup(workspaceId))
    }

    @Serializable
    private data class NotifyEnvelope(
        val instance: String,
        val workspaceId: String,
    )

    companion object {
        const val CHANNEL: String = "monomer_pages_directory"
    }
}
