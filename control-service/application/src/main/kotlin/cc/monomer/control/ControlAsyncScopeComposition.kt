package cc.monomer.control

import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration(proxyBeanMethods = false)
internal class ControlAsyncScopeComposition {
    @Bean("applicationAsyncScope", destroyMethod = "close")
    fun applicationAsyncScope(): ApplicationAsyncScope = ApplicationAsyncScope()
}

internal class ApplicationAsyncScope : CoroutineScope, AutoCloseable {
    override val coroutineContext: CoroutineContext =
        SupervisorJob() + Dispatchers.Default + CoroutineName("control-application-async")

    override fun close() {
        coroutineContext.cancel()
    }
}
