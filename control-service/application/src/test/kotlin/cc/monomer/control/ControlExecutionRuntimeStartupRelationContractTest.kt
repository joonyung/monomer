package cc.monomer.control

import java.io.File
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ControlExecutionRuntimeStartupRelationContractTest {
    @Test
    fun `execution startup verifies every durable relation in enabled runtime authorities`() {
        val root = File(checkNotNull(System.getProperty("control.service.root")))
        val schemaFiles =
            listOf(
                "modules/execution-runtime/src/main/resources/db/execution-runtime.sql",
                "modules/connector/src/main/resources/db/connector/schema.sql",
                "modules/catalog/src/main/resources/db/catalog/schema.sql",
                "modules/explore/src/main/resources/db/explore/schema.sql",
                "modules/dashboard/src/main/resources/db/dashboard/schema.sql",
            )
        val schemaRelations =
            schemaFiles.flatMap { relativePath ->
                CREATE_TABLE_PATTERN.findAll(root.resolve(relativePath).readText())
                    .map { match -> match.groupValues[1] }
                    .toList()
            }.toSortedSet()

        assertThat(ControlExecutionRuntimeStartupVerifier.REQUIRED_EXECUTION_RELATIONS)
            .containsExactlyElementsOf(schemaRelations)
    }

    private companion object {
        val CREATE_TABLE_PATTERN =
            Regex(
                "^CREATE TABLE IF NOT EXISTS\\s+([a-z_]+\\.[a-z_]+)\\s*\\(",
                setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE),
            )
    }
}
