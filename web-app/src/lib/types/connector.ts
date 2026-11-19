import type { components } from "@/api/control/generated";
export type ConnectorType = components["schemas"]["ConnectorType"];

export type ExternalConnectorType = Exclude<ConnectorType, "DUCKDB">;

export interface Connector {
  sample?: { dataset: string; label: string } | null;
  id: string;
  workspaceId: string;
  name: string;
  type: ConnectorType;
  properties: components["schemas"]["ConnectorResponse"]["properties"];
  createdAtEpochMs: number;
  updatedAtEpochMs: number;
}
