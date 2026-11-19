package cc.monomer.execution.driver.spi

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class QueryResultContractsTest {
    @Test
    fun artifactTargetCarriesArtifactOnlyBoundsAndManifestKeepsArrowFileVocabulary() {
        val target =
            QueryResultTarget.ArtifactUpload(
                objectKey = "artifacts/workspaces/w/r/result.arrow",
                maximumArtifactRows = 10u,
                maximumArtifactBytes = 1_024u,
                maximumRecordBatchRows = 16,
                maximumRecordBatchBytes = 512,
                maximumCellBytes = 128,
                maximumMultipartPartBytes = 512,
            )
        assertEquals(10u, target.maximumArtifactRows)
        assertEquals(512, target.maximumMultipartPartBytes)

        val manifest =
            ArrowResultManifest(
                objectKey = "artifacts/workspaces/w/r/result.arrow",
                byteSize = 42u,
                objectEtag = "etag",
                rowCount = 10u,
                columnCount = 2,
                mediaType = ArrowResultManifest.ARROW_FILE_MEDIA_TYPE,
            )
        assertEquals("application/vnd.apache.arrow.file", manifest.mediaType)
    }

    @Test
    fun resultTargetsRejectNonPositiveOrInconsistentBounds() {
        assertFailsWith<IllegalArgumentException> {
            QueryResultTarget.InlineStream(0, 1, 1)
        }
        assertFailsWith<IllegalArgumentException> {
            QueryResultTarget.ArtifactUpload("key", 1u, 1u, 1, 2, 1, 1)
        }
        assertFailsWith<IllegalArgumentException> {
            QueryResultTarget.ArtifactUpload("key", 1u, 1u, 1, 1, 1, 0)
        }
    }

}
