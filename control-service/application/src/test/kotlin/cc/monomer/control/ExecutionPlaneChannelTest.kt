package cc.monomer.control

import io.grpc.ManagedChannel
import io.mockk.mockk
import io.grpc.ManagedChannelBuilder
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import cc.monomer.execution.api.ExecutionRunPort
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

class ExecutionPlaneChannelTest {
    @Test
    fun `Spring closes the Execution channel after the beans that dial through it`() {
        val channel = ManagedChannelBuilder.forAddress("127.0.0.1", 1).usePlaintext().build()
        ChannelLifecycleConfiguration.channel = channel
        ChannelLifecycleConfiguration.events.clear()

        AnnotationConfigApplicationContext(ChannelLifecycleConfiguration::class.java).close()

        assertEquals(listOf("dialer closed while channel open"), ChannelLifecycleConfiguration.events)
        assertTrue(channel.isShutdown)
    }

    @Configuration(proxyBeanMethods = false)
    open class ChannelLifecycleConfiguration {
        @Bean(destroyMethod = "close")
        open fun executionPlaneChannel(): ExecutionPlaneChannel =
            ExecutionPlaneChannel(mockk<ExecutionRunPort>(), channel, Duration.ofMillis(100))

        @Bean
        open fun executionRunPort(executionPlaneChannel: ExecutionPlaneChannel): ExecutionRunPort =
            ControlExecutionRuntimeConfiguration().executionRunPort(executionPlaneChannel)

        @Bean(destroyMethod = "close")
        open fun dialer(executionRunPort: ExecutionRunPort): AutoCloseable = AutoCloseable {
            events += if (channel.isShutdown) "dialer closed after channel" else "dialer closed while channel open"
        }

        companion object {
            lateinit var channel: ManagedChannel
            val events = mutableListOf<String>()
        }
    }
}
