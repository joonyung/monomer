package cc.monomer.catalog.application.ingest

internal fun Throwable.causeTypeChain(): String = generateSequence(this) { it.cause }
    .map { it::class.qualifiedName ?: it::class.simpleName ?: "unknown" }
    .joinToString(" -> ")
