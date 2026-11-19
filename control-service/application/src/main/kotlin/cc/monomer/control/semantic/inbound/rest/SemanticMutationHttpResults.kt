package cc.monomer.control.semantic.inbound.rest

import cc.monomer.semantic.api.SemanticMutationResult
import cc.monomer.semantic.api.SemanticTimeSpineMutationResult
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException

internal fun <T> SemanticMutationResult<T>.requiredHttpValue(): T =
    when (this) {
        is SemanticMutationResult.Committed -> value
        is SemanticMutationResult.Invalid -> throw SemanticDefinitionProblem(
            HttpStatus.UNPROCESSABLE_ENTITY,
            "SEMANTIC_DEFINITION_INVALID",
            "The definition is invalid.",
            reasons.map { reason ->
                SemanticProblemViolation(
                    field = reason.field ?: "definition",
                    code = reason.code.name,
                    message = reason.code.mutationMessage(),
                )
            },
        )
        is SemanticMutationResult.AggregateMetricNameConflict -> throw SemanticDefinitionProblem(
            HttpStatus.CONFLICT, "SEMANTIC_NAME_CONFLICT",
            "Another metric in this workspace already uses this name.",
            listOf(SemanticProblemViolation(field, "SEMANTIC_NAME_CONFLICT", "This name is already used. Choose another name.")),
        )
        SemanticMutationResult.Conflict -> throw SemanticDefinitionProblem(
            HttpStatus.CONFLICT,
            "SEMANTIC_NAME_CONFLICT",
            "Another definition in this workspace already uses this name.",
            listOf(SemanticProblemViolation("name", "SEMANTIC_NAME_CONFLICT", "This name is already used. Choose another name.")),
        )
        SemanticMutationResult.IdempotencyConflict -> throw SemanticDefinitionProblem(
            HttpStatus.CONFLICT,
            "IDEMPOTENCY_CONFLICT",
            "This save was already sent with different content. Reload and save again.",
            emptyList(),
        )
        SemanticMutationResult.NotFoundOrOutsideWorkspace -> throw ResponseStatusException(HttpStatus.NOT_FOUND)
        SemanticMutationResult.InvalidAuthorization -> throw ResponseStatusException(HttpStatus.FORBIDDEN)
    }

private fun cc.monomer.semantic.api.SemanticInvalidReasonCode.mutationMessage(): String = when (this) {
    cc.monomer.semantic.api.SemanticInvalidReasonCode.MODEL_NAME_IMMUTABLE,
    cc.monomer.semantic.api.SemanticInvalidReasonCode.METRIC_NAME_IMMUTABLE,
    -> "The name can't be changed after it is created."
    else -> "This part of the definition is invalid."
}

internal fun <T> SemanticTimeSpineMutationResult<T>.requiredTimeSpineHttpValue(): T =
    when (this) {
        is SemanticTimeSpineMutationResult.Committed -> value
        is SemanticTimeSpineMutationResult.Invalid -> throw SemanticDefinitionProblem(
            HttpStatus.UNPROCESSABLE_ENTITY,
            "SEMANTIC_DEFINITION_INVALID",
            "The time spine definition is invalid.",
            reasons.map { reason ->
                SemanticProblemViolation(
                    field = reason.field ?: "definition",
                    code = reason.code.name,
                    message = if (reason.code == cc.monomer.semantic.api.SemanticInvalidReasonCode.TIME_SPINE_COLUMN_TYPE_INVALID) {
                        "Use a date column or a timestamp column without a time zone."
                    } else reason.code.mutationMessage(),
                )
            },
        )
        SemanticTimeSpineMutationResult.NotFoundOrOutsideWorkspace ->
            throw ResponseStatusException(HttpStatus.NOT_FOUND)
        SemanticTimeSpineMutationResult.InvalidAuthorization ->
            throw ResponseStatusException(HttpStatus.FORBIDDEN)
    }
