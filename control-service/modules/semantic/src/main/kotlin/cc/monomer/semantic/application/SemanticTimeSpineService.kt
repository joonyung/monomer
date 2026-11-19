package cc.monomer.semantic.application

import cc.monomer.catalog.api.CatalogCurrentObjectsReadPort
import cc.monomer.connector.api.ConnectorWorkspaceReadUseCase
import cc.monomer.identity.api.MutationIntent
import cc.monomer.identity.api.WorkspaceRole
import cc.monomer.identity.api.WorkspaceId
import cc.monomer.semantic.api.PutSemanticTimeSpineConfigurationCommand
import cc.monomer.semantic.api.SemanticInvalidReason
import cc.monomer.semantic.api.SemanticInvalidReasonCode
import cc.monomer.semantic.api.SemanticTimeSpineConfigurationView
import cc.monomer.semantic.api.SemanticTimeSpineMutationResult
import cc.monomer.semantic.api.SemanticTimeSpineUseCase
import cc.monomer.semantic.domain.CurrentTimeSpineConfiguration

interface SemanticTimeSpineRepository {
    fun findTimeSpineConfigurations(workspaceId: WorkspaceId): List<CurrentTimeSpineConfiguration>
    fun upsertTimeSpineConfiguration(configuration: CurrentTimeSpineConfiguration)
}

/** Saves a connector's spines; a spine column whose known Catalog type is not a date or a timestamp without a time zone is rejected. */
class SemanticTimeSpineService(
    private val repository: SemanticTimeSpineRepository,
    private val transactionPort: SemanticTransactionPort,
    private val connectorReadUseCase: ConnectorWorkspaceReadUseCase,
    private val catalogReadPort: CatalogCurrentObjectsReadPort,
) : SemanticTimeSpineUseCase {
    override suspend fun putTimeSpineConfiguration(
        command: PutSemanticTimeSpineConfigurationCommand,
    ): SemanticTimeSpineMutationResult<SemanticTimeSpineConfigurationView> {
        if (command.authorization.role < WorkspaceRole.EDITOR ||
            command.authorization.workspaceId != command.workspaceId ||
            command.authorization.intent != MutationIntent.SEMANTIC_TIME_SPINE_UPDATE
        ) {
            return SemanticTimeSpineMutationResult.InvalidAuthorization
        }
        val connector = connectorReadUseCase.findByWorkspaceAndId(command.workspaceId, command.connectorId)
            ?: return SemanticTimeSpineMutationResult.NotFoundOrOutsideWorkspace
        val dialect = dialectFor(connector)

        val current = CurrentTimeSpineConfiguration(
            workspaceId = command.workspaceId,
            connectorId = command.connectorId,
            definition = command.definition,
        )
        return transactionPort.inMutationTransaction {
            val catalog = catalogReadPort.readCurrent(command.workspaceId, command.connectorId)
                ?.takeIf { it.workspaceId == command.workspaceId && it.connectorId == command.connectorId }
            val invalid = if (catalog == null || dialect == null) emptyList() else
                command.definition.timeSpines.mapIndexedNotNull { index, timeSpine ->
                    timeSpineColumnTypeProblem(timeSpine, catalog, dialect)?.let {
                        SemanticInvalidReason(SemanticInvalidReasonCode.TIME_SPINE_COLUMN_TYPE_INVALID, "timeSpines[$index].primaryColumn.name")
                    }
                }
            if (invalid.isNotEmpty()) return@inMutationTransaction SemanticTimeSpineMutationResult.Invalid(invalid)
            repository.upsertTimeSpineConfiguration(current)
            SemanticTimeSpineMutationResult.Committed(
                SemanticTimeSpineConfigurationView(
                    connectorId = current.connectorId,
                    definition = current.definition,
                ),
            )
        }
    }
}
