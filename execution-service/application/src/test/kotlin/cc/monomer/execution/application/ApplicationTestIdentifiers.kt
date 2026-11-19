package cc.monomer.execution.application

import cc.monomer.execution.driver.spi.RunId
import cc.monomer.execution.driver.spi.ConnectorId
import cc.monomer.execution.driver.spi.WorkspaceId

val applicationRunId =
    RunId.parse("019f0000-0000-7000-8000-000000000001")
val applicationWorkspaceId =
    WorkspaceId.parse("019f0000-0000-7000-8000-000000000002")
val applicationConnectorId =
    ConnectorId.parse("019f0000-0000-7000-8000-000000000003")
