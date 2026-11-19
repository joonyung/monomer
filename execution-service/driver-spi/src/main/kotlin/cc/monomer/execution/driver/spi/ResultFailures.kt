package cc.monomer.execution.driver.spi

class ResultLimitExceededException(
    maximumBytes: ULong,
) : IllegalStateException("Query result exceeds the configured $maximumBytes byte limit")

class UnsupportedResultTypeException(
    columnName: String,
    typeName: String,
) : IllegalStateException("Column '$columnName' has unsupported result type '$typeName'")
