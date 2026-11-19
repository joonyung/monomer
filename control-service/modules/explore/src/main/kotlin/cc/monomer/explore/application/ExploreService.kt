package cc.monomer.explore.application

import cc.monomer.explore.api.ExploreResultArtifactReadOutcome
import cc.monomer.explore.api.ExploreResultProjectionReadOutcome
import cc.monomer.explore.api.ExploreResultReadOutcome
import cc.monomer.explore.api.ExploreRunPage
import cc.monomer.explore.api.ExploreRunPageBoundary
import cc.monomer.explore.api.ExploreRunUseCase
import cc.monomer.explore.api.ListExploreRunsCommand
import cc.monomer.explore.api.ReadExploreResultArtifactCommand
import cc.monomer.explore.api.ReadExploreResultCommand
import cc.monomer.explore.api.StartExploreRunOutcome
import cc.monomer.explore.api.StartSemanticExploreRunCommand
import cc.monomer.explore.api.StartSqlExploreRunCommand
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/** Explore owner facade for both execution variants and the shared result surface. */
class ExploreService(
    private val admissionCoordinator: ExploreAdmissionCoordinator,
    private val resultReader: ExploreResultReader,
    private val repository: ExploreRunRepository,
    private val blockingDispatcher: CoroutineDispatcher,
) : ExploreRunUseCase {
    override suspend fun start(command: StartSqlExploreRunCommand): StartExploreRunOutcome =
        admissionCoordinator.admit(command)

    override suspend fun start(command: StartSemanticExploreRunCommand): StartExploreRunOutcome =
        admissionCoordinator.admit(command)

    override suspend fun list(command: ListExploreRunsCommand): ExploreRunPage {
        val observed = withContext(blockingDispatcher) {
            repository.findRuns(
                workspaceId = command.workspaceId,
                ownerPrincipalId = command.ownerPrincipalId,
                after = command.after,
                limit = command.limit + 1,
            )
        }
        val runs = observed.take(command.limit).map { it.asView() }
        val next = if (observed.size > command.limit) {
            runs.last().let { ExploreRunPageBoundary(it.createdAt, it.id) }
        } else {
            null
        }
        return ExploreRunPage(runs, next)
    }

    override suspend fun readVisibleResult(
        command: ReadExploreResultCommand,
    ): ExploreResultReadOutcome = resultReader.read(command)

    override suspend fun readVisibleResultProjection(
        command: ReadExploreResultCommand,
    ): ExploreResultProjectionReadOutcome = resultReader.readProjection(command)

    override suspend fun readVisibleResultCsv(
        command: ReadExploreResultArtifactCommand,
    ): cc.monomer.explore.api.ExploreResultCsvReadOutcome = resultReader.readCsv(command)

    override suspend fun readVisibleResultDownloadLink(
        command: ReadExploreResultArtifactCommand,
    ): cc.monomer.explore.api.ExploreResultDownloadLinkReadOutcome = resultReader.readDownloadLink(command)

    override suspend fun readVisibleResultArtifact(
        command: ReadExploreResultArtifactCommand,
    ): ExploreResultArtifactReadOutcome = resultReader.readArtifact(command)
}
