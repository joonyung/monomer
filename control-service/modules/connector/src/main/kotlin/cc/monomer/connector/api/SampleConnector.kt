package cc.monomer.connector.api

import cc.monomer.identity.api.AuthorizedMutationContext

data class CreateSampleConnectorCommand(
    val authorization: AuthorizedMutationContext,
    val dataset: String,
    val mutationIdempotencyKey: String,
)

fun interface SampleConnectorUseCase {
    suspend fun create(command: CreateSampleConnectorCommand): ConnectorMutationResult
}
