package cc.monomer.control.semantic.inbound.rest

import cc.monomer.semantic.api.SemanticDefinitionIssue
import cc.monomer.semantic.api.SemanticDefinitionReference
import cc.monomer.semantic.api.SemanticInvalidReason
import cc.monomer.semantic.api.SemanticInvalidReasonCode
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.server.ResponseStatusException

/** A Semantic query the current definitions cannot run; each violation names a saved definition that blocks it. */
class SemanticQueryRejected(
    status: HttpStatus,
    detail: String,
    val violations: List<SemanticProblemViolation>,
) : ResponseStatusException(status, detail)

/** `requestRoot` names the request field a violation points at when the engine cannot attribute it to a definition. */
fun semanticQueryRejected(
    reasons: List<SemanticInvalidReason>,
    definitionIssues: List<SemanticDefinitionIssue>,
    requestRoot: String,
): SemanticQueryRejected = SemanticQueryRejected(
    status = HttpStatus.UNPROCESSABLE_ENTITY,
    detail = reasons.joinToString(",") { reason -> reason.code.name },
    violations = (definitionIssues.map { issue ->
        SemanticProblemViolation(
            field = issue.definition?.resourcePath() ?: requestRoot,
            code = issue.code.name,
            message = issue.message,
        )
    } + reasons.filter { reason ->
        reason.code == SemanticInvalidReasonCode.QUERY_FILTER_UNRENDERABLE && reason.field?.endsWith(".kind") == true
    }.map { reason ->
        SemanticProblemViolation(
            field = "$requestRoot.${requireNotNull(reason.field)}",
            code = reason.code.name,
            message = SEMANTIC_FILTER_KIND_VIOLATION_MESSAGE,
        )
    }).take(MAX_SEMANTIC_QUERY_VIOLATIONS),
).also { it.body.setProperty("code", "INVALID_SEMANTIC_QUERY") }

const val SEMANTIC_FILTER_KIND_VIOLATION_MESSAGE =
    "Filter comparison type must match the column's Catalog type; time dimensions use DATE at day or coarser grain and TIMESTAMP at finer grain."

fun semanticQueryUnsupported(capabilities: Set<cc.monomer.semantic.api.SemanticUnsupportedCapability>): ResponseStatusException =
    ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, capabilities.joinToString(",") { it.name })
        .also { it.body.setProperty("code", "UNSUPPORTED_SEMANTIC_QUERY") }

fun semanticMetricNotFound(name: cc.monomer.semantic.api.SemanticElementName): SemanticQueryRejected {
    val message = "Metric `$name` was not found. It may have been deleted."
    return SemanticQueryRejected(
        status = HttpStatus.NOT_FOUND,
        detail = message,
        violations = listOf(SemanticProblemViolation("workspace/metrics/${name.value}", "METRIC_NOT_FOUND", message)),
    )
}

/** The canonical Agent resource path of a saved definition. */
fun SemanticDefinitionReference.resourcePath(): String = when (this) {
    is SemanticDefinitionReference.Metric -> "workspace/metrics/${name.value}"
    is SemanticDefinitionReference.SemanticModel -> "workspace/semantic-models/${name.value}"
}

internal fun ProblemDetail.setViolations(violations: List<SemanticProblemViolation>) {
    if (violations.isEmpty()) return
    setProperty(
        "violations",
        violations.take(MAX_SEMANTIC_QUERY_VIOLATIONS).map { violation ->
            linkedMapOf(
                "field" to violation.field.take(MAX_VIOLATION_TEXT_LENGTH),
                "code" to violation.code,
                "message" to violation.message.take(MAX_VIOLATION_TEXT_LENGTH),
            )
        },
    )
}

const val MAX_SEMANTIC_QUERY_VIOLATIONS: Int = 100
private const val MAX_VIOLATION_TEXT_LENGTH = 512
