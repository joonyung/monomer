package cc.monomer.control.catalog.application.ingest

import cc.monomer.catalog.adapter.outbound.arrow.CatalogArrowBatchDecoder
import cc.monomer.catalog.application.CatalogInlineArrowBatch
import java.io.ByteArrayOutputStream
import java.nio.channels.Channels
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.apache.arrow.memory.RootAllocator
import org.apache.arrow.vector.BigIntVector
import org.apache.arrow.vector.VarCharVector
import org.apache.arrow.vector.VectorSchemaRoot
import org.apache.arrow.vector.ipc.ArrowFileWriter
import org.apache.arrow.vector.types.pojo.ArrowType
import org.apache.arrow.vector.types.pojo.Field
import org.apache.arrow.vector.types.pojo.FieldType
import org.apache.arrow.vector.types.pojo.Schema
import org.junit.jupiter.api.Test

class CatalogArrowBatchDecoderTest {
    @Test
    fun `decodes one complete inline Arrow file and canonicalizes warehouse column labels`() {
        val batch = CatalogInlineArrowBatch(
            sequence = 1uL,
            arrowIpc = arrowFile(),
            sha256 = "0".repeat(64),
        )

        val rows = CatalogArrowBatchDecoder.decode(batch)

        assertEquals(
            listOf(
                mapOf("schema_name" to "public", "ordinal_position" to "1"),
                mapOf("schema_name" to null, "ordinal_position" to "2"),
            ),
            rows.map { it.values },
        )
    }

    @Test
    fun `rejects column labels that collide after canonicalization`() {
        val batch = CatalogInlineArrowBatch(
            sequence = 1uL,
            arrowIpc = arrowFile("SCHEMA_NAME", "schema_name"),
            sha256 = "0".repeat(64),
        )

        assertFailsWith<IllegalArgumentException> {
            CatalogArrowBatchDecoder.decode(batch)
        }
    }

    private fun arrowFile(
        schemaNameField: String = "SCHEMA_NAME",
        ordinalPositionField: String = "ORDINAL_POSITION",
    ): ByteArray {
        val output = ByteArrayOutputStream()
        RootAllocator().use { allocator ->
            val schema = Schema(
                listOf(
                    Field(schemaNameField, FieldType(true, ArrowType.Utf8(), null), null),
                    Field(ordinalPositionField, FieldType(false, ArrowType.Int(64, true), null), null),
                ),
            )
            VectorSchemaRoot.create(schema, allocator).use { root ->
                val schemaNames = root.getVector(schemaNameField) as VarCharVector
                val ordinals = root.getVector(ordinalPositionField) as BigIntVector
                schemaNames.allocateNew()
                ordinals.allocateNew(2)
                schemaNames.setSafe(0, "public".toByteArray())
                schemaNames.setNull(1)
                ordinals.set(0, 1L)
                ordinals.set(1, 2L)
                schemaNames.valueCount = 2
                ordinals.valueCount = 2
                root.rowCount = 2
                ArrowFileWriter(root, null, Channels.newChannel(output)).use { writer ->
                    writer.start()
                    writer.writeBatch()
                    writer.end()
                }
            }
        }
        return output.toByteArray()
    }
}
