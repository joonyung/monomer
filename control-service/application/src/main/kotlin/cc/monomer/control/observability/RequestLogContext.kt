package cc.monomer.control.observability

import io.micrometer.context.ContextRegistry
import io.micrometer.context.ThreadLocalAccessor
import org.slf4j.MDC
import org.slf4j.spi.LoggingEventBuilder
import org.springframework.context.annotation.Configuration

internal class RequestLogContext(val requestId: String, val actor: String) {
    @Volatile var code: String? = null
    @Volatile var clientId: String? = null
    @Volatile var errorType: String? = null
    @Volatile var exception: Throwable? = null

    companion object {
        const val KEY = "monomer.request-log-context"
        private val current = ThreadLocal<RequestLogContext>()

        fun problem(code: String) { current.get()?.code = code }
        fun agent(clientId: String) { current.get()?.clientId = clientId }
        fun failure(failure: Throwable) {
            current.get()?.let {
                it.errorType = failure.javaClass.name
                it.exception = failure
            }
        }

        internal class Accessor : ThreadLocalAccessor<RequestLogContext> {
            override fun key(): Any = KEY
            override fun getValue(): RequestLogContext? = current.get()
            override fun setValue(value: RequestLogContext) {
                current.set(value)
                MDC.put("request_id", value.requestId)
            }
            override fun setValue() {
                current.remove()
                MDC.remove("request_id")
            }
        }
    }
}

@Configuration
internal class RequestLogContextConfiguration {
    init {
        ContextRegistry.getInstance().registerThreadLocalAccessor(RequestLogContext.Companion.Accessor())
    }
}

internal fun LoggingEventBuilder.addCorrelationId(field: String, value: String?): LoggingEventBuilder =
    if (MDC.get(field) == null) addKeyValue(field, value) else this
