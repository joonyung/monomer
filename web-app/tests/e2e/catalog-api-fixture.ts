import type { Page } from "@playwright/test";
import { json, mockGraphql } from "./support/session";

const WORKSPACE_ID = "83000000-0000-4000-8000-000000000001";
const SNOWFLAKE_ID = "83000000-0000-4000-8000-000000000010";
const POSTGRES_ID = "83000000-0000-4000-8000-000000000020";
const RUNNING_JOB_ID = "83000000-0000-4000-8000-000000000030";

type TrackedJobState = "RUNNING" | "CANCEL_REQUESTED" | "CANCELLED";

function jobSummary(index: number, trackedJobState: TrackedJobState) {
  const tracked = index === 0;
  return {
    jobId: tracked ? RUNNING_JOB_ID : `83000000-0000-4000-8000-${String(30 + index).padStart(12, "0")}`,
    connectorId: SNOWFLAKE_ID,
    state: tracked ? trackedJobState : index % 3 === 0 ? "COMPLETED_WITH_ERRORS" : "COMPLETED",
    entryKind: "CATALOG",
    catalogName: "analytics",
    requestedAt: new Date(Date.now() - (index + 1) * 60_000).toISOString(),
    terminalAt: tracked && trackedJobState !== "CANCELLED" ? null : new Date(Date.now() - index * 60_000).toISOString(),
    refreshedRelationCount: tracked ? 19 : 240 + index,
    runningTaskCount: tracked && trackedJobState !== "CANCELLED" ? 13 : 0,
    errorCount: tracked ? 2 : index % 3 === 0 ? 4 : 0,
    requestedBy: { principalId: "83000000-0000-7000-8000-000000000003", displayName: "Catalog Operator", credentialSurface: index === 1 ? "AGENT" : "WEB" },
  };
}

function trackedJobTasks(trackedJobState: TrackedJobState) {
  if (trackedJobState === "CANCELLED") return [];
  return [
    { taskId: "task-1", parentTaskId: null, targetKind: "RELATION", catalogName: "analytics", schemaName: "marketing", relationName: "campaigns", relationKind: "TABLE", state: "SUCCEEDED", loading: false, failureCode: null, safeMessage: null },
    { taskId: "task-2", parentTaskId: null, targetKind: "RELATION", catalogName: "analytics", schemaName: "marketing", relationName: "audiences_view", relationKind: "VIEW", state: "RUNNING", loading: true, failureCode: null, safeMessage: null },
  ];
}

type CatalogOptionsState = "loaded" | "never-fetched" | "fetched-empty";

export async function installCatalogApi(
  page: Page,
  options: { initialCatalogOptions?: CatalogOptionsState; initialSelectedCatalogNames?: string[] } = {},
) {
  let selectedCatalogNames = [...(options.initialSelectedCatalogNames ?? ["analytics"])] as string[];
  let catalogOptionsState: CatalogOptionsState = options.initialCatalogOptions ?? "loaded";
  let catalogListRefreshCount = 0;
  let trackedJobState: TrackedJobState = "RUNNING";
  await mockGraphql(page, {
    AccessibleWorkspaces: (route) => json(route, { data: { accessibleWorkspaces: [
      { id: WORKSPACE_ID, organizationId: "83000000-0000-4000-8000-000000000002", name: "Northstar Studio", publicPages: false, reportingTimeZone: "UTC", role: "OWNER" },
    ] } }),
    WorkspaceOwners: (route) => json(route, { data: { workspaceAccess: { owners: [
      { userId: "83000000-0000-7000-8000-000000000004", displayName: "Mina Park" },
      { userId: "83000000-0000-7000-8000-000000000005", displayName: "Sam Kim" },
    ] } } }),
    LineageFocusCone: (route, variables) => {
      const focus = variables.focus as { kind: string; id: string };
      const table = { resource: focus, label: "campaigns", address: "public.campaigns", description: null, missing: false, connector: { id: POSTGRES_ID, displayName: "Product database", type: "POSTGRESQL" } };
      const model = { resource: { kind: "SEMANTIC_MODEL", id: "83000000-0000-7000-8000-000000000060" }, label: "Campaign performance", address: "campaign_performance", description: null, missing: false, connector: null };
      return json(route, { data: { lineageFocusCone: {
        workspaceId: WORKSPACE_ID, dashboardUsages: [], missingDefinitions: [], focus: table, ancestors: [], descendants: [model], siblings: [],
        edges: [{ dependent: model.resource, parent: focus }], siblingEdges: [], entityPeers: [],
        detail: { relation: { columns: [] }, semanticModel: null, metric: null },
      } } });
    },
  });
  await page.route("**/v2/workspaces/**", async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    const path = url.pathname;
    if (path === `/v2/workspaces/${WORKSPACE_ID}/catalog/connector-summaries`) {
      await json(route, { connectors: [] });
      return;
    }
    if (path === `/v2/workspaces/${WORKSPACE_ID}/catalog/table-search`) {
      await json(route, { tables: [
        { connectorId: POSTGRES_ID, connectorName: "Product database", connectorType: "POSTGRESQL", catalogName: null, schemaName: "public", tableName: "campaigns", relationKind: "TABLE", matchedColumns: ["campaign_id"] },
      ] });
      return;
    }
    if (path === `/v2/workspaces/${WORKSPACE_ID}/connectors`) {
      await json(route, { connectors: [
        { id: SNOWFLAKE_ID, workspaceId: WORKSPACE_ID, name: "Finance warehouse", type: "SNOWFLAKE", properties: { database: "analytics" }, secretSet: true, createdAtEpochMs: 1, updatedAtEpochMs: 1 },
        { id: POSTGRES_ID, workspaceId: WORKSPACE_ID, name: "Product database", type: "POSTGRESQL", properties: { database: "product" }, secretSet: true, createdAtEpochMs: 1, updatedAtEpochMs: 1 },
      ] });
      return;
    }
    const connectorId = path.includes(SNOWFLAKE_ID) ? SNOWFLAKE_ID : POSTGRES_ID;
    if (path.endsWith("/catalog-selection")) {
      if (request.method() === "PUT") {
        const body = request.postDataJSON() as { selectedCatalogNames?: string[] };
        selectedCatalogNames = body.selectedCatalogNames ?? [];
      }
      await json(route, { selectedCatalogNames, committedAt: "2026-08-21T01:00:00Z" });
      return;
    }
    if (path.endsWith("/catalogs")) {
      await json(route, { catalogs: connectorId === SNOWFLAKE_ID ? [{ name: "analytics" }, { name: "raw" }] : [] });
      return;
    }
    if (path.endsWith("/selection/options")) {
      const parentKind = url.searchParams.get("parentKind");
      const schemaName = url.searchParams.get("schemaName");
      const availableOptions = parentKind === "CONNECTOR"
        ? [
            { name: "analytics", relationKind: null, available: true },
            { name: "raw", relationKind: null, available: true },
            { name: "retired_catalog", relationKind: null, available: false },
          ]
        : parentKind === "SCHEMA"
          ? [{ name: "campaigns", relationKind: "TABLE", available: true }]
          : [
              { name: "finance", relationKind: null, available: true },
              { name: "marketing", relationKind: null, available: true },
              { name: "product", relationKind: null, available: true },
            ];
      const optionPage = catalogOptionsState === "never-fetched" ? [] : availableOptions;
      const fetchedAt = catalogOptionsState === "never-fetched" ? null : "2026-08-21T01:00:00Z";
      await json(route, {
        parentKind,
        childKind: parentKind === "CONNECTOR" ? "CATALOG" : parentKind === "SCHEMA" ? "RELATION" : "SCHEMA",
        catalogName: parentKind === "CONNECTOR" ? null : "analytics",
        schemaName,
        fetchedAt,
        options: catalogOptionsState === "fetched-empty" ? [] : optionPage,
        totalCount: catalogOptionsState === "fetched-empty" ? 0 : optionPage.length,
        page: 1,
        pageSize: Number(url.searchParams.get("pageSize") ?? "50"),
        latestJob: catalogOptionsState === "never-fetched" ? null : {
          jobId: "83000000-0000-4000-8000-000000000050",
          parentKind: "CONNECTOR",
          childKind: "CATALOG",
          catalogName: null,
          schemaName: null,
          state: "COMPLETED",
          requestedAt: "2026-08-21T01:00:00Z",
          terminalAt: "2026-08-21T01:00:01Z",
          failureCode: null,
          safeMessage: null,
        },
      });
      return;
    }
    if (path.endsWith("/selection/list-refresh-jobs") && request.method() === "POST") {
      catalogListRefreshCount += 1;
      if (catalogOptionsState === "never-fetched") catalogOptionsState = "fetched-empty";
      await json(route, {
        jobId: `83000000-0000-4000-8000-${String(40 + catalogListRefreshCount).padStart(12, "0")}`,
        parentKind: "CONNECTOR",
        childKind: "CATALOG",
        catalogName: null,
        schemaName: null,
        state: "COMPLETED",
        requestedAt: "2026-08-21T01:00:00Z",
        terminalAt: "2026-08-21T01:00:01Z",
        failureCode: null,
        safeMessage: null,
      }, 202);
      return;
    }
    if (path.includes("/catalogs/") && path.endsWith("/selection")) {
      await json(route, {
        committedAt: "2026-08-21T01:00:00Z",
        selectedSchemas: [
          { schemaName: "marketing", relations: { kind: "ALL_RELATIONS" } },
          { schemaName: "finance", relations: { kind: "SELECTED_RELATIONS", relationNames: ["monthly_close"] } },
          { schemaName: "product", relations: { kind: "ALL_RELATIONS" } },
        ],
      });
      return;
    }
    if (path.endsWith("/selection")) {
      await json(route, {
        committedAt: "2026-08-21T01:00:00Z",
        selectedSchemas: [{ schemaName: "public", relations: { kind: "ALL_RELATIONS" } }],
      });
      return;
    }
    if (path.endsWith("/ingests/active")) {
      if (connectorId === SNOWFLAKE_ID && url.searchParams.get("catalogName") === "analytics" && trackedJobState !== "CANCELLED") {
        await json(route, { ...jobSummary(0, trackedJobState), tasks: trackedJobTasks(trackedJobState) });
      } else {
        await route.fulfill({ status: 204 });
      }
      return;
    }
    if (path.endsWith(`/ingests/${RUNNING_JOB_ID}:cancel`) && request.method() === "POST") {
      trackedJobState = "CANCEL_REQUESTED";
      await json(route, {}, 202);
      return;
    }
    if (path.endsWith(`/ingests/${RUNNING_JOB_ID}`)) {
      await json(route, { ...jobSummary(0, trackedJobState), tasks: trackedJobTasks(trackedJobState) });
      return;
    }
    if (path.endsWith(`/ingests/${RUNNING_JOB_ID}/errors`)) {
      await json(route, {
        errors: [
          { taskId: "error-1", parentTaskId: null, targetKind: "RELATION", catalogName: "analytics", schemaName: "finance", relationName: "monthly_close", relationKind: "TABLE", state: "FAILED", loading: false, failureCode: "WAREHOUSE_QUERY_FAILED", safeMessage: "Metadata query exceeded the warehouse statement timeout." },
          { taskId: "error-2", parentTaskId: null, targetKind: "RELATION", catalogName: "analytics", schemaName: "marketing", relationName: "daily_spend", relationKind: "VIEW", state: "FAILED", loading: false, failureCode: "WAREHOUSE_QUERY_FAILED", safeMessage: "The warehouse rejected the metadata query." },
        ],
        totalCount: 2,
        page: 1,
        pageSize: 10,
      });
      return;
    }
    if (path.endsWith("/ingests")) {
      const pageNumber = Number(url.searchParams.get("page") ?? "1");
      const jobs = Array.from({ length: 14 }, (_, index) => jobSummary(index, trackedJobState));
      await json(route, { jobs: jobs.slice((pageNumber - 1) * 10, (pageNumber - 1) * 10 + 10), totalCount: jobs.length, page: pageNumber, pageSize: 10 });
      return;
    }
    if (path.endsWith("/schemas")) {
      await json(route, { schemas: connectorId === SNOWFLAKE_ID ? [
        { catalogName: "analytics", name: "marketing", lastRefreshedAt: Date.now() - 1_080_000 },
        { catalogName: "analytics", name: "finance", lastRefreshedAt: Date.now() - 1_080_000 },
        { catalogName: "analytics", name: "product", lastRefreshedAt: Date.now() - 1_080_000 },
      ] : [{ catalogName: null, name: "public", lastRefreshedAt: Date.now() - 1_080_000 }], totalCount: connectorId === SNOWFLAKE_ID ? 128 : 1, page: 1, pageSize: 50 });
      return;
    }
    if (path.includes("/schemas/") && path.endsWith("/relations")) {
      await json(route, { relations: [
        { catalogName: connectorId === SNOWFLAKE_ID ? "analytics" : null, schemaName: connectorId === SNOWFLAKE_ID ? "marketing" : "public", name: "campaigns", kind: "TABLE", columnCount: 12, lastRefreshedAt: Date.now() - 1_080_000 },
        { catalogName: connectorId === SNOWFLAKE_ID ? "analytics" : null, schemaName: connectorId === SNOWFLAKE_ID ? "marketing" : "public", name: "audiences_view", kind: "VIEW", columnCount: 6, lastRefreshedAt: Date.now() - 1_080_000 },
      ], totalCount: 184, page: 1, pageSize: 50 });
      return;
    }
    if (path.includes("/relations/")) {
      const name = decodeURIComponent(path.split("/").at(-1) ?? "campaigns");
      await json(route, { requiresPartitionFilter: null,
        lineageResourceRef: { kind: "catalog_table", id: "83000000-0000-4000-8000-000000000099" },
        catalogName: connectorId === SNOWFLAKE_ID ? "analytics" : null,
        schemaName: connectorId === SNOWFLAKE_ID ? "marketing" : "public",
        name,
        kind: name.endsWith("view") ? "VIEW" : "TABLE",
        columns: [
          { name: "campaign_id", nativeType: "BIGINT", ordinalPosition: 1, nullable: false, defaultValue: null },
          { name: "customer_id", nativeType: "BIGINT", ordinalPosition: 2, nullable: false, defaultValue: null },
        ],
        partitionKeys: [], clusteringKeys: [],
        primaryKey: connectorId === POSTGRES_ID ? { name: "campaigns_pkey", columns: ["campaign_id"] } : null,
        foreignKeys: connectorId === POSTGRES_ID ? [
          { name: "campaigns_customer_fkey", columns: ["customer_id"], referencedConnectorId: POSTGRES_ID, referencedCatalogName: null, referencedSchemaName: "public", referencedRelationName: "customers", referencedColumns: ["id"] },
          { name: "campaigns_account_fkey", columns: ["customer_id"], referencedConnectorId: POSTGRES_ID, referencedCatalogName: null, referencedSchemaName: "public", referencedRelationName: "accounts", referencedColumns: ["owner_id"] },
        ] : [],
      });
      return;
    }
    await json(route, { title: `Unhandled fixture request: ${path}` }, 404);
  });
  return {
    runningJobId: RUNNING_JOB_ID,
    finishCancellation: () => { trackedJobState = "CANCELLED"; },
  };
}
