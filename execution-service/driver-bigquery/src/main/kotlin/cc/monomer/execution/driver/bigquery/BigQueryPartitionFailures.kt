package cc.monomer.execution.driver.bigquery

internal fun isPartitionFilterRequired(reason: String?, message: String?): Boolean =
    reason == "invalidQuery" && message?.contains("cannot query over table", ignoreCase = true) == true &&
        message.contains("without a filter over column", ignoreCase = true) &&
        message.contains("partition elimination", ignoreCase = true)
