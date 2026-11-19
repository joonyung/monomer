package cc.monomer.pages.application

import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.Flow

data class PagesDirectoryWakeup(
    val workspaceId: Uuid,
)

interface PagesDirectoryEventBus {
    fun publish(wakeup: PagesDirectoryWakeup)
    fun subscribe(workspaceId: Uuid): Flow<PagesDirectoryWakeup>
}
