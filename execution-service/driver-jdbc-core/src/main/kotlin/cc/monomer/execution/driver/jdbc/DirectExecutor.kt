package cc.monomer.execution.driver.jdbc

import java.util.concurrent.Executor

object DirectExecutor : Executor {
    override fun execute(command: Runnable) {
        command.run()
    }
}
