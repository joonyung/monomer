package cc.monomer.execution.application

import java.time.Clock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.selects.select

fun main() =
    runBlocking {
        val environment = System.getenv()
        configureExecutionLogging(environment)
        val configuration = ExecutionServiceConfiguration.fromEnvironment(environment)
        val runningService = productionExecutionServiceLauncher(Clock.systemUTC()).start(configuration)
        val shutdownRequested = CompletableDeferred<Unit>()
        val shutdownHook =
            Thread.ofPlatform()
                .name("execution-service-shutdown")
                .unstarted {
                    runBlocking {
                        runningService.closeGracefully()
                    }
                    shutdownRequested.complete(Unit)
                }
        Runtime.getRuntime().addShutdownHook(shutdownHook)
        val serverFailure = async { runningService.awaitServerFailure() }
        try {
            select {
                shutdownRequested.onAwait {}
                serverFailure.onAwait { failure -> throw failure }
            }
        } finally {
            runCatching { Runtime.getRuntime().removeShutdownHook(shutdownHook) }
            serverFailure.cancelAndJoin()
            runningService.closeGracefully()
        }
    }
