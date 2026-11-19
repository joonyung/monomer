package cc.monomer.catalog.adapter.outbound.arrow

import cc.monomer.catalog.application.CatalogInlineArrowBatch
import cc.monomer.catalog.domain.ingestion.CatalogQueryRow
import java.nio.channels.SeekableByteChannel
import java.util.Locale
import org.apache.arrow.memory.RootAllocator
import org.apache.arrow.vector.ipc.ArrowFileReader
import org.apache.arrow.vector.ipc.SeekableReadChannel
import org.apache.arrow.vector.util.ByteArrayReadableSeekableByteChannel

object CatalogArrowBatchDecoder {
    fun decode(batch: CatalogInlineArrowBatch): List<CatalogQueryRow> {
        val bytes = batch.arrowIpc.copyOf()
        try {
            RootAllocator().use { allocator ->
                byteChannel(bytes).use { channel ->
                    ArrowFileReader(SeekableReadChannel(channel), allocator).use { reader ->
                        val rows = mutableListOf<CatalogQueryRow>()
                        reader.recordBlocks.forEach { block ->
                            reader.loadRecordBatch(block)
                            val root = reader.vectorSchemaRoot
                            val vectors = root.fieldVectors
                            require(vectors.map { it.name.lowercase(Locale.ROOT) }.toSet().size == vectors.size) {
                                "Catalog query returned duplicate column names"
                            }
                            repeat(root.rowCount) { rowIndex ->
                                rows += CatalogQueryRow(
                                    vectors.associate { vector ->
                                        vector.name.lowercase(Locale.ROOT) to
                                            if (vector.isNull(rowIndex)) null else vector.getObject(rowIndex).toString()
                                    },
                                )
                            }
                        }
                        return rows
                    }
                }
            }
        } finally {
            bytes.fill(0)
        }
    }

    private fun byteChannel(bytes: ByteArray): SeekableByteChannel =
        ByteArrayReadableSeekableByteChannel(bytes)
}
