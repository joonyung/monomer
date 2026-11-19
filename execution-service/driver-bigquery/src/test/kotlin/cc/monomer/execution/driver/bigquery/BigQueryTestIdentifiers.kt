package cc.monomer.execution.driver.bigquery

import cc.monomer.execution.driver.spi.RunId
import cc.monomer.execution.driver.spi.ConnectorId
import cc.monomer.execution.driver.spi.WorkspaceId

internal val bigQueryRunId =
    RunId.parse("019f0000-0000-7000-8000-000000000001")
internal val bigQueryWorkspaceId =
    WorkspaceId.parse("019f0000-0000-7000-8000-000000000002")
internal val bigQueryConnectorId =
    ConnectorId.parse("019f0000-0000-7000-8000-000000000003")
internal val alternateBigQueryConnectorId =
    ConnectorId.parse("019f0000-0000-7000-8000-000000000013")
