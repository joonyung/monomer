package cc.monomer.control.observability

import org.springframework.beans.factory.support.DefaultListableBeanFactory
import org.springframework.web.reactive.result.method.annotation.RequestMappingHandlerMapping
import org.springframework.web.server.WebFilter
import reactor.core.publisher.Hooks

object RequestCorrelationTestSupport {
    fun filter(): WebFilter {
        RequestLogContextConfiguration()
        Hooks.enableAutomaticContextPropagation()
        return RequestCorrelationWebFilter(DefaultListableBeanFactory().getBeanProvider(RequestMappingHandlerMapping::class.java))
    }
}
