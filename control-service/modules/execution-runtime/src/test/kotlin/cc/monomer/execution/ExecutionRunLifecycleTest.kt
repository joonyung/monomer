@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.execution

import cc.monomer.identity.api.WorkspaceId
import cc.monomer.execution.api.ConnectorReference
import cc.monomer.execution.api.ConnectorType
import cc.monomer.execution.api.ExecutionRunId
import cc.monomer.execution.api.ExecutionRunSnapshot
import cc.monomer.execution.api.ExecutionRunState
import cc.monomer.execution.api.ExecutionOwnerId
import cc.monomer.execution.api.ExecutionOwnerReference
import cc.monomer.execution.api.ExecutionOwnerType
import cc.monomer.execution.api.ExecutableRequestKind
import cc.monomer.execution.api.ResultSinkBinding
import cc.monomer.execution.api.ResultSinkKind
import cc.monomer.execution.api.RunRuntimeBindings
import cc.monomer.execution.api.Sha256Digest
import cc.monomer.execution.domain.ExecutionRun
import cc.monomer.execution.domain.RunTransition
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.uuid.Uuid

class ExecutionRunLifecycleTest {
    @Test
    fun `a durable dispatch claim is one shot and cannot redispatch the same run`() {
        val createdAt = Instant.parse("2026-08-18T00:00:00Z")
        val first =
            assertIs<RunTransition.Applied>(
                ExecutionRun.create(snapshot(createdAt)).claimDispatch(
                    claimedAt = createdAt.plusSeconds(1),
                    executionDeadlineAt = createdAt.plusSeconds(60),
                ),
            )

        val second =
            ExecutionRun.restore(first.snapshot).claimDispatch(
                claimedAt = createdAt.plusSeconds(2),
                executionDeadlineAt = createdAt.plusSeconds(60),
            )

        assertIs<RunTransition.InvalidState>(second)
        assertEquals(createdAt.plusSeconds(1), first.snapshot.dispatchClaimedAt)
        assertEquals(createdAt.plusSeconds(60), first.snapshot.deadline)

        assertIs<RunTransition.InvalidState>(
            ExecutionRun.create(snapshot(createdAt)).claimDispatch(
                claimedAt = createdAt.plusSeconds(11),
                executionDeadlineAt = createdAt.plusSeconds(60),
            ),
        )
    }

    @Test
    fun `progress updates heartbeat and preserves the same run identity`() {
        val createdAt = Instant.parse("2026-08-18T00:00:00Z")
        val claimed =
            assertIs<RunTransition.Applied>(
                ExecutionRun.create(snapshot(createdAt)).claimDispatch(
                    claimedAt = createdAt.plusSeconds(1),
                    executionDeadlineAt = createdAt.plusSeconds(60),
                ),
            )
        val observedAt = createdAt.plusSeconds(2)

        val progress =
            assertIs<RunTransition.Applied>(
                ExecutionRun.restore(claimed.snapshot).recordProgress(
                    rowsProcessed = 12u,
                    bytesProcessed = 256u,
                    phase = "streaming",
                    observedAt = observedAt,
                ),
            )

        assertEquals(claimed.snapshot.id, progress.snapshot.id)
        assertEquals(observedAt, progress.snapshot.lastHeartbeatAt)
        assertEquals(12u, progress.snapshot.progress?.rowsProcessed)
        assertEquals("streaming", progress.snapshot.progress?.phase)
    }

    @Test
    fun `execution deadline narrows after waiting without changing claim heartbeat or extending deadline`() {
        val at = Instant.parse("2026-09-09T00:00:00Z")
        val admitted = snapshot(at).copy(dispatchDeadlineAt = at.plusSeconds(3600), deadline = at.plusSeconds(7200))
        val claimed = assertIs<RunTransition.Applied>(ExecutionRun.create(admitted).claimDispatch(at, admitted.deadline)).snapshot
        val heartbeat = assertIs<RunTransition.Applied>(ExecutionRun.restore(claimed).recordProgress(0u, 0u, "executing", at.plusSeconds(120))).snapshot
        val narrowed = assertIs<RunTransition.Applied>(ExecutionRun.restore(heartbeat).narrowExecutionDeadline(
            at.plusSeconds(3720), at.plusSeconds(119),
        )).snapshot
        assertEquals(at, narrowed.dispatchClaimedAt)
        assertEquals(heartbeat.lastHeartbeatAt, narrowed.lastHeartbeatAt)
        assertEquals(heartbeat.updatedAt, narrowed.updatedAt)
        assertEquals(at.plusSeconds(3720), narrowed.deadline)
        assertIs<RunTransition.Duplicate>(ExecutionRun.restore(narrowed).narrowExecutionDeadline(narrowed.deadline, at.plusSeconds(120)))
        assertIs<RunTransition.InvalidState>(ExecutionRun.restore(narrowed).narrowExecutionDeadline(at.plusSeconds(3721), at.plusSeconds(120)))
    }

    @Test
    fun `deadline narrowing cannot revive cancelled unclaimed or expired dispatch`() {
        val at = Instant.parse("2026-09-09T00:00:00Z")
        val admitted = snapshot(at)
        assertIs<RunTransition.InvalidState>(ExecutionRun.create(admitted).narrowExecutionDeadline(at.plusSeconds(30), at))
        val claimed = assertIs<RunTransition.Applied>(ExecutionRun.create(admitted).claimDispatch(at, admitted.deadline)).snapshot
        assertIs<RunTransition.InvalidState>(ExecutionRun.restore(claimed).narrowExecutionDeadline(at.plusSeconds(30), admitted.dispatchDeadlineAt))
        val cancelled = assertIs<RunTransition.Applied>(ExecutionRun.restore(claimed).requestCancel("User cancelled", at.plusSeconds(1))).snapshot
        assertIs<RunTransition.InvalidState>(ExecutionRun.restore(cancelled).narrowExecutionDeadline(at.plusSeconds(30), at.plusSeconds(2)))
    }

    @Test
    fun `waiting heartbeat preserves user timestamp and progress cannot move heartbeat backwards`() {
        val at = Instant.parse("2026-09-11T00:00:00Z")
        val admitted = snapshot(at)
        val claimed = assertIs<RunTransition.Applied>(ExecutionRun.create(admitted).claimDispatch(at, admitted.deadline)).snapshot
        val beat = assertIs<RunTransition.Applied>(ExecutionRun.restore(claimed).heartbeat(at.plusSeconds(5))).snapshot
        assertEquals(claimed.updatedAt, beat.updatedAt)
        val progress = assertIs<RunTransition.Applied>(ExecutionRun.restore(beat).recordProgress(1u, 1u, "executing", at.plusSeconds(4))).snapshot
        assertEquals(beat.lastHeartbeatAt, progress.lastHeartbeatAt)
        val expired = assertIs<RunTransition.Applied>(ExecutionRun.restore(progress).recoverExecutionOccupancy(
            admitted.dispatchDeadlineAt, java.time.Duration.ofSeconds(90),
        )).snapshot
        assertEquals("EXECUTION_UNAVAILABLE", expired.physicalOutcome?.failureCode)
        assertEquals(null, expired.executionOccupancyAcquiredAt)
    }

    private fun snapshot(createdAt: Instant): ExecutionRunSnapshot {
        val deadline = createdAt.plusSeconds(60)
        return ExecutionRunSnapshot(
            id = ExecutionRunId(Uuid.generateV7()),
            owner =
                ExecutionOwnerReference(
                    type = ExecutionOwnerType.EXPLORE,
                    id = ExecutionOwnerId(Uuid.generateV7()),
                ),
            workspaceId = WorkspaceId(Uuid.generateV7()),
            connectorId = ConnectorReference(Uuid.generateV7()),
            connectorType = ConnectorType.POSTGRESQL,
            connectorProperties = mapOf("host" to cc.monomer.execution.api.ConnectorRuntimePropertyValue.Text("localhost")),
            requestKind = ExecutableRequestKind.QUERY,
            canonicalRequestDigest = digest('a'),
            runtimeBindings =
                RunRuntimeBindings(
                    connectorBindingDigest = digest('b'),
                    credentialVersion = null,
                    credentialKeyVersion = "local-primary",
                    credentialBindingDigest = digest('c'),
                    resultSink =
                        ResultSinkBinding(
                            schemaVersion = 2u,
                            kind = ResultSinkKind.ARTIFACT_UPLOAD,
                            objectKey = "artifacts/test/result.arrow",
                            maximumArtifactRows = 1_024u,
                            maximumArtifactBytes = 1_024u,
                            expiresAt = deadline,
                            maximumRecordBatchRows = 128,
                            maximumFrameBytes = 1_024,
                            maximumCellBytes = 1_024,
                            maximumMultipartPartBytes = 1_024,
                            bindingDigest = digest('d'),
                        ),
                ),
            executionOccupancyAcquiredAt = null,
            executionOccupancyReleasedAt = null,
            dispatchDeadlineAt = createdAt.plusSeconds(10),
            deadline = deadline,
            state = ExecutionRunState.RUNNING,
            physicalOutcome = null,
            cancelIntent = null,
            ownerSettledAt = null,
            ownerDiscardedAt = null,
            createdAt = createdAt,
            updatedAt = createdAt,
            version = 0,
        )
    }
}

private fun digest(character: Char): Sha256Digest = Sha256Digest(character.toString().repeat(64))
