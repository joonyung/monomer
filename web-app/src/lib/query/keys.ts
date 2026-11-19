const protectedRoot = (workspaceId: string, principalId: string) =>
  ["protected", workspaceId, principalId] as const;

export const queryKeys = {
  auth: {
    sampleWorkspace: (principalId: string, organizationId: string) =>
      ["auth", principalId, "organization", organizationId, "sample-workspace"] as const,
    accessibleWorkspaces: (principalId: string) =>
      ["auth", principalId, "accessible-workspaces"] as const,
    managedWorkspaces: (principalId: string, organizationId: string) =>
      ["auth", principalId, "organization", organizationId, "managed-workspaces"] as const,
  },
  workspaceContext: {
    detail: (workspaceId: string, principalId: string) =>
      [...protectedRoot(workspaceId, principalId), "context"] as const,
  },
  workspaceSettings: {
    all: (workspaceId: string, principalId: string) =>
      [...protectedRoot(workspaceId, principalId), "workspace-settings"] as const,
    authorizationAssignments: (workspaceId: string, principalId: string) =>
      [...protectedRoot(workspaceId, principalId), "workspace-settings", "authorization-assignments"] as const,

  },
  search: {
    resources: (workspaceId: string, principalId: string, query: string) =>
      [...protectedRoot(workspaceId, principalId), "search", "resources", query.trim()] as const,
  },
  overview: {
    counts: (workspaceId: string, principalId: string) =>
      [...protectedRoot(workspaceId, principalId), "overview", "counts"] as const,
  },
  explore: {
    all: (workspaceId: string, principalId: string) =>
      [...protectedRoot(workspaceId, principalId), "explore"] as const,
    run: (workspaceId: string, principalId: string, runId: string) =>
      [...protectedRoot(workspaceId, principalId), "explore", "run", runId] as const,
    recentRuns: (workspaceId: string, principalId: string) =>
      [...protectedRoot(workspaceId, principalId), "explore", "recent-runs"] as const,
    semanticGroupBysAll: (workspaceId: string, principalId: string) =>
      [...protectedRoot(workspaceId, principalId), "explore", "semantic-group-bys"] as const,
    semanticGroupBys: (workspaceId: string, principalId: string, connectorId: string, metricNames: readonly string[]) =>
      [...protectedRoot(workspaceId, principalId), "explore", "semantic-group-bys", connectorId, [...metricNames].sort()] as const,
  },
  connectors: {
    all: (workspaceId: string, principalId: string) =>
      [...protectedRoot(workspaceId, principalId), "connectors"] as const,
    list: (workspaceId: string, principalId: string) =>
      [...protectedRoot(workspaceId, principalId), "connectors", "list"] as const,
    detail: (workspaceId: string, principalId: string, connectorId: string) =>
      [...protectedRoot(workspaceId, principalId), "connectors", "detail", connectorId] as const,
  },
  semanticModels: {
    all: (workspaceId: string, principalId: string) =>
      [...protectedRoot(workspaceId, principalId), "semantic-models"] as const,
    history: (workspaceId: string, principalId: string, id: string) =>
      [...protectedRoot(workspaceId, principalId), "semantic-models", "history", id] as const,
    detail: (workspaceId: string, principalId: string, semanticModelId: string) =>
      [...protectedRoot(workspaceId, principalId), "semantic-models", "detail", semanticModelId] as const,
  },
  metrics: {
    all: (workspaceId: string, principalId: string) =>
      [...protectedRoot(workspaceId, principalId), "metrics"] as const,
    history: (workspaceId: string, principalId: string, id: string) =>
      [...protectedRoot(workspaceId, principalId), "metrics", "history", id] as const,
    detail: (workspaceId: string, principalId: string, metricId: string) =>
      [...protectedRoot(workspaceId, principalId), "metrics", "detail", metricId] as const,
  },
  semanticCheck: (workspaceId: string, principalId: string, draftKey: string) =>
    [...protectedRoot(workspaceId, principalId), "semantic-check", draftKey] as const,
  timeSpines: {
    all: (workspaceId: string, principalId: string) =>
      [...protectedRoot(workspaceId, principalId), "time-spines"] as const,
  },
  catalog: {
    all: (workspaceId: string, principalId: string) =>
      [...protectedRoot(workspaceId, principalId), "catalog"] as const,
    catalogs: (workspaceId: string, principalId: string, connectorId: string) =>
      [...protectedRoot(workspaceId, principalId), "catalog", connectorId, "catalogs"] as const,
    connectorSummaries: (workspaceId: string, principalId: string) =>
      [...protectedRoot(workspaceId, principalId), "catalog", "connector-summaries"] as const,
    tableSearch: (workspaceId: string, principalId: string, query: string) =>
      [...protectedRoot(workspaceId, principalId), "catalog", "table-search", query.trim()] as const,
    connectorSelection: (workspaceId: string, principalId: string, connectorId: string) =>
      [...protectedRoot(workspaceId, principalId), "catalog", connectorId, "connector-selection"] as const,
    schemasAll: (workspaceId: string, principalId: string, connectorId: string, catalogName: string | null) =>
      [...protectedRoot(workspaceId, principalId), "catalog", connectorId, "schemas", catalogName] as const,
    schemas: (
      workspaceId: string,
      principalId: string,
      connectorId: string,
      catalogName: string | null,
      page: number,
      pageSize: number,
      query: string,
    ) => [...protectedRoot(workspaceId, principalId), "catalog", connectorId, "schemas", catalogName, page, pageSize, query.trim()] as const,
    relationsAll: (workspaceId: string, principalId: string, connectorId: string, catalogName: string | null, schemaName: string) =>
      [...protectedRoot(workspaceId, principalId), "catalog", connectorId, "relations", catalogName, schemaName] as const,
    relations: (
      workspaceId: string,
      principalId: string,
      connectorId: string,
      catalogName: string | null,
      schemaName: string,
      page: number,
      pageSize: number,
      query: string,
    ) => [...protectedRoot(workspaceId, principalId), "catalog", connectorId, "relations", catalogName, schemaName, page, pageSize, query.trim()] as const,
    relation: (
      workspaceId: string,
      principalId: string,
      connectorId: string,
      catalogName: string | null,
      schemaName: string,
      relationName: string,
    ) => [...protectedRoot(workspaceId, principalId), "catalog", connectorId, "relation", catalogName, schemaName, relationName] as const,
    selection: (workspaceId: string, principalId: string, connectorId: string, catalogName: string | null) =>
      [...protectedRoot(workspaceId, principalId), "catalog", connectorId, "selection", catalogName] as const,
    selectionOptions: (
      workspaceId: string,
      principalId: string,
      connectorId: string,
      parentKind: string,
      catalogName: string | null,
      schemaName: string | null,
      page: number,
      pageSize: number,
      query: string,
    ) => [...protectedRoot(workspaceId, principalId), "catalog", connectorId, "selection-options", parentKind, catalogName, schemaName, page, pageSize, query.trim()] as const,
  },
  lineage: {
    focus: (workspaceId: string, principalId: string, kind: string, resourceId: string) =>
      [...protectedRoot(workspaceId, principalId), "lineage", "focus", kind, resourceId] as const,
  },
  catalogIngest: {
    all: (workspaceId: string, principalId: string) =>
      [...protectedRoot(workspaceId, principalId), "catalog-ingest"] as const,
    active: (workspaceId: string, principalId: string, connectorId: string, catalogName: string | null) =>
      [...protectedRoot(workspaceId, principalId), "catalog-ingest", connectorId, "active", catalogName] as const,
    job: (workspaceId: string, principalId: string, connectorId: string, jobId: string) =>
      [...protectedRoot(workspaceId, principalId), "catalog-ingest", connectorId, "jobs", jobId] as const,
    history: (
      workspaceId: string,
      principalId: string,
      connectorId: string,
      catalogName: string | null,
      page: number,
      pageSize: number,
    ) => [...protectedRoot(workspaceId, principalId), "catalog-ingest", connectorId, "history", catalogName, page, pageSize] as const,
    errors: (
      workspaceId: string,
      principalId: string,
      connectorId: string,
      jobId: string,
      page: number,
      pageSize: number,
    ) => [...protectedRoot(workspaceId, principalId), "catalog-ingest", connectorId, "jobs", jobId, "errors", page, pageSize] as const,
  },
  pages: {
    all: (workspaceId: string, principalId: string) =>
      [...protectedRoot(workspaceId, principalId), "pages"] as const,
    directory: (workspaceId: string, principalId: string) =>
      [...protectedRoot(workspaceId, principalId), "pages", "directory"] as const,
    detail: (workspaceId: string, principalId: string, pageId: string) =>
      [...protectedRoot(workspaceId, principalId), "pages", "detail", pageId] as const,
    documentContent: (workspaceId: string, principalId: string, pageId: string, versionNo: number | null) =>
      [...protectedRoot(workspaceId, principalId), "pages", "document", pageId, "content", versionNo] as const,
    documentVersionsAll: (workspaceId: string, principalId: string, pageId: string) =>
      [...protectedRoot(workspaceId, principalId), "pages", "document", pageId, "versions"] as const,
    documentVersions: (workspaceId: string, principalId: string, pageId: string, limit: number) =>
      [...protectedRoot(workspaceId, principalId), "pages", "document", pageId, "versions", limit] as const,
  },
  dashboards: {
    history: (workspaceId: string, principalId: string, dashboardId: string) =>
      [...protectedRoot(workspaceId, principalId), "dashboards", "history", dashboardId] as const,
    schedules: (workspaceId: string, principalId: string, dashboardId: string, draft: boolean) =>
      [...protectedRoot(workspaceId, principalId), "dashboards", "schedules", dashboardId, draft] as const,
    occurrences: (workspaceId: string, principalId: string, dashboardId: string) =>
      [...protectedRoot(workspaceId, principalId), "dashboards", "occurrences", dashboardId] as const,
    occurrence: (workspaceId: string, principalId: string, dashboardId: string, scheduleKey: string, scheduledAt: string) =>
      [...protectedRoot(workspaceId, principalId), "dashboards", "occurrence", dashboardId, scheduleKey, scheduledAt] as const,
    all: (workspaceId: string, principalId: string) =>
      [...protectedRoot(workspaceId, principalId), "dashboards"] as const,
    list: (workspaceId: string, principalId: string) =>
      [...protectedRoot(workspaceId, principalId), "dashboards", "list"] as const,
    detail: (workspaceId: string, principalId: string, dashboardId: string) =>
      [...protectedRoot(workspaceId, principalId), "dashboards", "detail", dashboardId] as const,
    draft: (workspaceId: string, principalId: string, dashboardId: string) =>
      [...protectedRoot(workspaceId, principalId), "dashboards", "draft", dashboardId] as const,
    session: (workspaceId: string, principalId: string, sessionId: string) =>
      [...protectedRoot(workspaceId, principalId), "dashboards", "session", sessionId] as const,
    sessions: (workspaceId: string, principalId: string, dashboardId: string, cursor: string | null, pageSize: number) =>
      [...protectedRoot(workspaceId, principalId), "dashboards", "sessions", dashboardId, cursor, pageSize] as const,
    sessionQueryRuns: (workspaceId: string, principalId: string, sessionId: string, cursor: string | null, pageSize: number) =>
      [...protectedRoot(workspaceId, principalId), "dashboards", "session-query-runs", sessionId, cursor, pageSize] as const,
    sessionRun: (workspaceId: string, principalId: string, sessionId: string, runId?: string) =>
      [...protectedRoot(workspaceId, principalId), "dashboard-session-run", sessionId, ...(runId ? [runId] : [])] as const,
    result: (workspaceId: string, principalId: string, sessionId: string, runId?: string) =>
      [...protectedRoot(workspaceId, principalId), "dashboard-result", sessionId, ...(runId ? [runId] : [])] as const,
    draftResult: (workspaceId: string, principalId: string, dashboardId: string, runId: string) =>
      [...protectedRoot(workspaceId, principalId), "dashboard-draft-result", dashboardId, runId] as const,
  },
} as const;
