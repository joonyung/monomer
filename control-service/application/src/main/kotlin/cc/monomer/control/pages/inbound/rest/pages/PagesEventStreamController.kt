package cc.monomer.control.pages.inbound.rest.pages

import cc.monomer.pages.application.PagesDirectoryProjection
import cc.monomer.pages.application.PagesDirectoryUseCase
import cc.monomer.pages.application.PagesDirectoryWakeup
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import org.springframework.http.MediaType
import org.springframework.http.codec.ServerSentEvent
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ServerWebExchange

@RestController
@RequestMapping("/v2/workspaces/{workspaceId}/pages")
class PagesEventStreamController(
    private val useCase: PagesDirectoryUseCase,
    private val requestAccess: PagesRequestAccess,
) {
    @GetMapping("/tree/events", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    suspend fun events(
        @PathVariable workspaceId: String,
        exchange: ServerWebExchange,
    ): Flow<ServerSentEvent<Any>> {
        val access = requestAccess.require(exchange, workspaceId)
        return channelFlow {
            val wakeups = Channel<PagesDirectoryWakeup>(Channel.CONFLATED)
            val subscription = launch(start = CoroutineStart.UNDISPATCHED) {
                useCase.subscribeEvents(access.workspaceId).collect { wakeup -> wakeups.send(wakeup) }
            }

            suspend fun sendSnapshot(): PagesDirectoryProjection {
                val projection = useCase.getProjection(access.workspaceId)
                send(
                    ServerSentEvent.builder<Any>()
                        .id(projection.snapshot.revision.value.toString())
                        .event("snapshot")
                        .data(projection.toResponse())
                        .build(),
                )
                return projection
            }

            try {
                if (!requestAccess.isCurrentExplorer(access)) return@channelFlow
                var cursor = sendSnapshot()
                for (wakeup in wakeups) {
                    if (!requestAccess.isCurrentExplorer(access)) return@channelFlow
                    val latest = useCase.getProjection(access.workspaceId)
                    if (latest == cursor) continue
                    send(
                        ServerSentEvent.builder<Any>()
                            .id(latest.snapshot.revision.value.toString())
                            .event("snapshot")
                            .data(latest.toResponse())
                            .build(),
                    )
                    cursor = latest
                }
            } finally {
                subscription.cancel()
                wakeups.close()
            }
        }.flowOn(org.springframework.core.PropagationContextElement())
    }

}
