package cc.monomer.execution.arrow

/**
 * Selects the physical Arrow representation used for JDBC decimal columns.
 *
 * This is an Execution-side driver setting. It is not part of the product
 * request or result model.
 */
enum class JdbcDecimalPhysicalEncoding {
    NATIVE_DECIMAL,
    UTF8_CANONICAL_DECIMAL,
}
