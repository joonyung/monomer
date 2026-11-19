package cc.monomer.control

import cc.monomer.execution.adapter.outbound.postgres.PostgresExecutionCancellationListener
import org.springframework.boot.web.server.context.WebServerApplicationContext
import org.springframework.context.SmartLifecycle

class ExecutionCancellationListenerLifecycle(
    private val listener: PostgresExecutionCancellationListener,
) : SmartLifecycle {
    @Volatile
    private var running = false

    override fun start() {
        listener.start()
        running = true
    }

    override fun stop() {
        running = false
        listener.close()
    }

    override fun isRunning(): Boolean = running

    override fun isAutoStartup(): Boolean = true

    override fun getPhase(): Int = WebServerApplicationContext.START_STOP_LIFECYCLE_PHASE - 1
}
