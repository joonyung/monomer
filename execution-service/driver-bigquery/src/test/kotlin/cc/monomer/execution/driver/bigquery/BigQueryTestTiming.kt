package cc.monomer.execution.driver.bigquery

import cc.monomer.execution.driver.spi.ConnectorType
import cc.monomer.execution.driver.spi.DriverOperation
import cc.monomer.execution.driver.spi.DriverOperationTiming

internal fun testTiming() = DriverOperationTiming(ConnectorType.BIGQUERY, DriverOperation.QUERY,
    "019f0000-0000-7000-8000-000000000001", "019f0000-0000-7000-8000-000000000002")
