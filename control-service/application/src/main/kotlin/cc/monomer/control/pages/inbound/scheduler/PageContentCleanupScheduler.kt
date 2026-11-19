package cc.monomer.control.pages.inbound.scheduler

import cc.monomer.pages.application.content.PageContentCleanupProcessor
import cc.monomer.pages.adapter.outbound.messaging.InstanceIdHolder
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class PageContentCleanupScheduler(
    private val processor: PageContentCleanupProcessor,
    private val instanceIdHolder: InstanceIdHolder,
    @param:Qualifier("applicationAsyncScope") private val scope: CoroutineScope,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val running = AtomicBoolean(false)

    @Scheduled(fixedDelay = 30_000)
    fun cleanupPageContent() {
        if (!running.compareAndSet(false, true)) return
        scope.launch {
            try {
                processor.processBatch(instanceIdHolder.value)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (exception: Exception) {
                log.atError()
                    .addKeyValue("event", "pages_content_cleanup_batch_failed")
                    .setCause(exception)
                    .log("pages_content_cleanup_batch_failed")
            } finally {
                running.set(false)
            }
        }
    }
}
