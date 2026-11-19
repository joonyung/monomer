package cc.monomer.control.jdbc

internal fun Throwable.causeTypeChain(): String = generateSequence(this) { it.cause }
    .map { it::class.qualifiedName ?: it::class.simpleName ?: "unknown" }
    .joinToString(" -> ")

internal fun Throwable.sqlStateChain(): String = generateSequence(this) { it.cause }
    .mapNotNull { (it as? java.sql.SQLException)?.sqlState }
    .joinToString(" -> ")
