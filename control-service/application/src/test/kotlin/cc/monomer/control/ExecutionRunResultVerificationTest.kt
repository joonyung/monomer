package cc.monomer.control

import cc.monomer.execution.api.ExecutionArtifactStore
import cc.monomer.execution.api.ExecutionOwnerType
import cc.monomer.execution.api.ExecutionRunId
import cc.monomer.execution.api.ExecutionRunSnapshot
import cc.monomer.execution.api.ExecutionRunUseCase
import cc.monomer.execution.api.PhysicalOutcome
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.time.Clock
import java.time.Duration
import java.time.ZoneOffset
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.uuid.Uuid
import kotlinx.coroutines.runBlocking
import cc.monomer.execution.api.ArrowResultManifest
import cc.monomer.execution.api.ExecutionArtifactHead
import cc.monomer.execution.api.PhysicalOutcomeKind
import cc.monomer.execution.api.ResultSinkKind
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExecutionRunResultVerificationTest {
    @Test
    fun `terminal mismatch and late success never delete before durable recovery`() = runBlocking {
        val runId = ExecutionRunId(Uuid.generateV7())
        val objectKey = "artifacts/workspaces/w/explore/runs/$runId/result.arrow"
        val at = Instant.parse("2026-09-08T00:00:00Z")
        val snapshot = mockk<ExecutionRunSnapshot>(relaxed = true)
        every { snapshot.id } returns runId
        every { snapshot.runtimeBindings.resultSink.kind } returns ResultSinkKind.ARTIFACT_UPLOAD
        every { snapshot.runtimeBindings.resultSink.objectKey } returns objectKey
        val artifacts = mockk<ExecutionArtifactStore>()
        val execution = mockk<ExecutionRunUseCase>()
        val manifest = ArrowResultManifest(objectKey, 128u, "etag", 1u, 1, "application/vnd.apache.arrow.file")
        coEvery { artifacts.head(objectKey) } returns null
        coEvery { execution.recordPhysicalOutcome(any()) } returns ExecutionRunUseCase.TerminalOutcome.TerminalConflict
        val coordinator = ExecutionRunDialCoordinator(
            mockk(), mockk(), execution, mockk(), emptySet(), mockk(), mockk(), artifacts,
            Clock.fixed(at, ZoneOffset.UTC), this, Duration.ofMinutes(5), 4096, 20, 100, 1000,
        )
        val terminal = PhysicalOutcome("$runId:terminal", PhysicalOutcomeKind.SUCCEEDED, null, null, manifest)
        val timing = ExecutionRunDialTiming(runId, ExecutionOwnerType.EXPLORE, ResultSinkKind.ARTIFACT_UPLOAD, at, at)
        suspend fun persist() = suspendCoroutine<Unit> { continuation ->
            val method = ExecutionRunDialCoordinator::class.java.getDeclaredMethod(
                "persistTerminal", ExecutionRunSnapshot::class.java, PhysicalOutcome::class.java,
                ExecutionRunDialTiming::class.java, Continuation::class.java,
            ).apply { isAccessible = true }
            val result = method.invoke(coordinator, snapshot, terminal, timing, continuation)
            if (result !== COROUTINE_SUSPENDED) continuation.resume(Unit)
        }
        persist()
        coVerify { execution.recordPhysicalOutcome(match { it.outcome.failureCode == "RESULT_PERSISTENCE_FAILED" }) }
        coEvery { artifacts.head(objectKey) } returns ExecutionArtifactHead(objectKey, 128u, "etag", at)
        persist()
        coVerify { execution.recordPhysicalOutcome(match { it.outcome.kind == PhysicalOutcomeKind.SUCCEEDED }) }
        coVerify(exactly = 0) { artifacts.delete(any()) }
    }

    @Test
    fun `success accepts only the deterministic key exact size and normalized ETag`() {
        val manifest =
            ArrowResultManifest(
                objectKey = "artifacts/workspaces/w/explore/runs/r/result.arrow",
                byteSize = 128u,
                objectEtag = "abc123",
                rowCount = 4u,
                columnCount = 2,
                mediaType = "application/vnd.apache.arrow.file",
            )
        val head =
            ExecutionArtifactHead(
                objectKey = manifest.objectKey,
                byteSize = manifest.byteSize,
                etag = "\"abc123\"",
                observedAt = Instant.parse("2026-08-18T00:00:00Z"),
            )

        assertTrue(matchesExactArtifact(manifest.objectKey, manifest, head))
        assertFalse(matchesExactArtifact("artifacts/other/result.arrow", manifest, head))
        assertFalse(matchesExactArtifact(manifest.objectKey, manifest, head.copy(byteSize = 129u)))
        assertFalse(matchesExactArtifact(manifest.objectKey, manifest, head.copy(etag = "different")))
    }

    @Test
    fun `only successful raw sql runs require an Arrow artifact`() {
        assertTrue(
            requiresArrowArtifactVerification(
                ResultSinkKind.ARTIFACT_UPLOAD,
                PhysicalOutcomeKind.SUCCEEDED,
            ),
        )
        assertFalse(
            requiresArrowArtifactVerification(
                ResultSinkKind.INLINE_STREAM,
                PhysicalOutcomeKind.SUCCEEDED,
            ),
        )
        assertFalse(
            requiresArrowArtifactVerification(
                ResultSinkKind.ARTIFACT_UPLOAD,
                PhysicalOutcomeKind.FAILED,
            ),
        )
    }
}
