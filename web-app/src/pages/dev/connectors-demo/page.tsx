import { useState } from "react";
import { notFound } from "@/lib/navigation";
import { ConnectorList, type ConnectorSummaries } from "@/components/connectors/ConnectorList";
import { ConnectorMutationDialog } from "@/components/connectors/ConnectorMutationDialog";
import { WorkspacePageHeader } from "@/components/layout/WorkspacePageHeader";
import type { ConnectorMutationRequest } from "@/lib/connectors/mutation-contract";
import type { Connector } from "@/lib/types/connector";

const NOW = Date.now();
const INITIAL_MOCK_CONNECTORS: Connector[] = [
  {
    id: "mock-postgresql",
    workspaceId: "demo-workspace",
    name: "Northstar warehouse",
    type: "POSTGRESQL",
    properties: { host: "warehouse.internal", port: 5432, database: "northstar", schema: "analytics", sslMode: "REQUIRE" },
    createdAtEpochMs: NOW - 1000 * 60 * 60 * 24 * 90,
    updatedAtEpochMs: NOW - 1000 * 60 * 18,
  },
  {
    id: "mock-bigquery",
    workspaceId: "demo-workspace",
    name: "Marketing analytics",
    type: "BIGQUERY",
    properties: { projectId: "northstar-marketing", location: "US" },
    createdAtEpochMs: NOW - 1000 * 60 * 60 * 24 * 45,
    updatedAtEpochMs: NOW - 1000 * 60 * 60 * 26,
  },
  {
    id: "mock-snowflake",
    workspaceId: "demo-workspace",
    name: "Finance reporting",
    type: "SNOWFLAKE",
    properties: { account: "northstar", warehouse: "REPORTING", database: "FINANCE", schema: "PUBLIC" },
    createdAtEpochMs: NOW - 1000 * 60 * 60 * 24 * 30,
    updatedAtEpochMs: NOW - 1000 * 60 * 60 * 24 * 3,
  },
  {
    id: "mock-trino",
    workspaceId: "demo-workspace",
    name: "Lakehouse federation — production",
    type: "TRINO",
    properties: { host: "trino.internal", port: 8443, catalog: "lakehouse", schema: "analytics" },
    createdAtEpochMs: NOW - 1000 * 60 * 60 * 24 * 14,
    updatedAtEpochMs: NOW - 1000 * 60 * 60 * 24 * 8,
  },
];

const iso = (offsetMs: number) => new Date(NOW - offsetMs).toISOString();
const MOCK_SUMMARIES: ConnectorSummaries = {
  status: "loaded",
  byConnectorId: new Map([
    ["mock-postgresql", { connectorId: "mock-postgresql", selectedCatalogCount: 0, selectedSchemaCount: 4, tableCount: 142, lastRefresh: { jobId: "mock-job-1", state: "COMPLETED", catalogName: null, requestedAt: iso(1000 * 60 * 32), finishedAt: iso(1000 * 60 * 30), errorCount: 0, entryFailureCode: null }, lastSuccessfulRefreshAt: iso(1000 * 60 * 30) }],
    ["mock-snowflake", { connectorId: "mock-snowflake", selectedCatalogCount: 2, selectedSchemaCount: 3, tableCount: 38, lastRefresh: { jobId: "mock-job-2", state: "COMPLETED_WITH_ERRORS", catalogName: "FINANCE", requestedAt: iso(1000 * 60 * 60 * 20), finishedAt: iso(1000 * 60 * 60 * 20), errorCount: 1, entryFailureCode: "AUTHENTICATION_FAILED" }, lastSuccessfulRefreshAt: iso(1000 * 60 * 60 * 24 * 2) }],
    ["mock-trino", { connectorId: "mock-trino", selectedCatalogCount: 1, selectedSchemaCount: 2, tableCount: 61, lastRefresh: { jobId: "mock-job-3", state: "RUNNING", catalogName: "lakehouse", requestedAt: iso(1000 * 60 * 2), finishedAt: null, errorCount: 0, entryFailureCode: null }, lastSuccessfulRefreshAt: iso(1000 * 60 * 60 * 24 * 8) }],
  ]),
};

export default function ConnectorsDemoPage() {
  const [connectors, setConnectors] = useState(INITIAL_MOCK_CONNECTORS);
  const [dialogMode, setDialogMode] = useState<"create" | "detail" | "revise" | "rotate" | null>(null);
  const [selectedConnector, setSelectedConnector] = useState<Connector | null>(null);

  if (!import.meta.env.DEV) notFound();

  return (
    <div className="flex h-screen flex-col bg-background" style={{ fontFeatureSettings: '"ss01"' }}>
      <WorkspacePageHeader
        section="Sources"
        title="Connectors"
        actions={
          <button
            type="button"
            onClick={() => {
              setSelectedConnector(null);
              setDialogMode("create");
            }}
            className="inline-flex h-9 items-center rounded-[4px] bg-accent px-3 text-[13px] text-accent-foreground"
          >
            Create connector
          </button>
        }
      />
      <div className="min-h-0 flex-1 overflow-y-auto">
        <div className="mx-auto max-w-[1080px] px-5 py-6 sm:px-8">
          <ConnectorList
            workspaceId="demo-workspace"
            connectors={connectors}
            summaries={MOCK_SUMMARIES}
            connectorLimit={10}
            canChooseTables
            onOpen={(connector) => {
              setSelectedConnector(connector);
              setDialogMode("detail");
            }}
          />
        </div>
      </div>
      <ConnectorMutationDialog
        open={dialogMode !== null}
        mode={dialogMode ?? "create"}
        connector={dialogMode !== "create" ? selectedConnector : null}
        canEdit
        catalogHref={selectedConnector ? "/dev/catalog-production-demo" : null}
        submitting={false}
        error={null}
        onClose={() => {
          setDialogMode(null);
          setSelectedConnector(null);
        }}
        onEdit={() => setDialogMode("revise")}
        onRotate={() => setDialogMode("rotate")}
        onDelete={() => {
          setDialogMode(null);
          setSelectedConnector(null);
        }}
        onCancel={() => {
          if (dialogMode === "revise" || dialogMode === "rotate") {
            setDialogMode("detail");
            return;
          }
          setDialogMode(null);
          setSelectedConnector(null);
        }}
        onSubmit={(request: ConnectorMutationRequest) => {
          if ("kind" in request) { setDialogMode("detail"); return; }
          if (dialogMode === "revise" && selectedConnector) {
            const revisedConnector: Connector = {
              ...selectedConnector,
              name: request.displayName,
              properties: request.properties,
              updatedAtEpochMs: Date.now(),
            };
            setConnectors((current) => current.map((connector) => connector.id === revisedConnector.id ? revisedConnector : connector));
            setSelectedConnector(revisedConnector);
            setDialogMode("detail");
            return;
          }
          if ("connectorType" in request) {
            const createdConnector: Connector = {
              id: `mock-${Date.now()}`,
              workspaceId: "demo-workspace",
              name: request.displayName,
              type: request.connectorType,
              properties: request.properties,
              createdAtEpochMs: Date.now(),
              updatedAtEpochMs: Date.now(),
            };
            setConnectors((current) => [...current, createdConnector]);
          }
          setDialogMode(null);
          setSelectedConnector(null);
        }}
      />
    </div>
  );
}
