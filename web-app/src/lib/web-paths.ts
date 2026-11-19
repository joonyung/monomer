const workspaceRoot = (workspaceId: string): string =>
  `/workspaces/${encodeURIComponent(workspaceId)}`;

const organizationRoot = (organizationId: string): string =>
  `/organizations/${encodeURIComponent(organizationId)}`;

export const webPaths = {
  root: "/",
  login: "/login",
  authCallback: "/auth/callback",
  workspaces: "/workspaces",
  workspace: (workspaceId: string) => workspaceRoot(workspaceId),
  activity: (workspaceId: string, scope: "all" | "mine" | "agents" | null) =>
    `${workspaceRoot(workspaceId)}/activity${scope && scope !== "all" ? `?${new URLSearchParams({ scope }).toString()}` : ""}`,
  catalog: (workspaceId: string) => `${workspaceRoot(workspaceId)}/catalog`,
  connectors: (workspaceId: string) => `${workspaceRoot(workspaceId)}/connectors`,
  semantics: (workspaceId: string) => `${workspaceRoot(workspaceId)}/semantics`,
  lineage: (workspaceId: string) => `${workspaceRoot(workspaceId)}/lineage`,
  lineageResource: (workspaceId: string, kind: "RELATION" | "SEMANTIC_MODEL" | "METRIC", id: string) =>
    `${workspaceRoot(workspaceId)}/lineage?${new URLSearchParams({ kind, id }).toString()}`,
  semanticModelCollection: (workspaceId: string) => `${workspaceRoot(workspaceId)}/semantics?view=models`,
  newSemanticModel: (workspaceId: string) => `${workspaceRoot(workspaceId)}/semantic-models/new`,
  semanticModel: (workspaceId: string, semanticModelId: string) =>
    `${workspaceRoot(workspaceId)}/semantic-models/${encodeURIComponent(semanticModelId)}`,
  semanticModelHistory: (workspaceId: string, id: string) =>
    `${workspaceRoot(workspaceId)}/semantic-models/${encodeURIComponent(id)}/history`,
  editSemanticModel: (workspaceId: string, semanticModelId: string) =>
    `${workspaceRoot(workspaceId)}/semantic-models/${encodeURIComponent(semanticModelId)}/edit`,
  metricCollection: (workspaceId: string) => `${workspaceRoot(workspaceId)}/semantics`,
  newMetric: (workspaceId: string, prefill?: { model?: string; measure?: string }) => {
    const params = new URLSearchParams();
    if (prefill?.model) params.set("model", prefill.model);
    if (prefill?.model && prefill.measure) params.set("measure", prefill.measure);
    const query = params.toString();
    return `${workspaceRoot(workspaceId)}/metrics/new${query ? `?${query}` : ""}`;
  },
  metric: (workspaceId: string, metricId: string) =>
    `${workspaceRoot(workspaceId)}/metrics/${encodeURIComponent(metricId)}`,
  metricHistory: (workspaceId: string, id: string) =>
    `${workspaceRoot(workspaceId)}/metrics/${encodeURIComponent(id)}/history`,
  editMetric: (workspaceId: string, metricId: string) =>
    `${workspaceRoot(workspaceId)}/metrics/${encodeURIComponent(metricId)}/edit`,
  explore: (workspaceId: string) => `${workspaceRoot(workspaceId)}/explore`,
  exploreModel: (workspaceId: string, semanticModelId: string) =>
    `${workspaceRoot(workspaceId)}/explore?${new URLSearchParams({ model: semanticModelId }).toString()}`,
  pages: (workspaceId: string) => `${workspaceRoot(workspaceId)}/pages`,
  page: (workspaceId: string, pageId: string) =>
    `${workspaceRoot(workspaceId)}/pages/${encodeURIComponent(pageId)}`,
  dashboards: (workspaceId: string) => `${workspaceRoot(workspaceId)}/dashboards`,
  dashboard: (workspaceId: string, dashboardId: string) =>
    `${workspaceRoot(workspaceId)}/dashboards/${encodeURIComponent(dashboardId)}`,
  dashboardHistory: (workspaceId: string, dashboardId: string) =>
    `${workspaceRoot(workspaceId)}/dashboards/${encodeURIComponent(dashboardId)}/history`,
  dashboardBuilder: (workspaceId: string, dashboardId: string) =>
    `${workspaceRoot(workspaceId)}/dashboards/${encodeURIComponent(dashboardId)}/builder`,
  workspaceSettings: (workspaceId: string) => `${workspaceRoot(workspaceId)}/settings`,
  workspaceMembers: (workspaceId: string) => `${workspaceRoot(workspaceId)}/settings/members`,
  workspacePublicAccess: (workspaceId: string) => `${workspaceRoot(workspaceId)}/settings/public-access`,
  userProfile: "/settings/profile",
  userOrganizations: "/settings/organizations",
  userAgents: "/settings/agents",
  userSecurity: "/settings/security",
  organizationSettings: (organizationId: string) => `${organizationRoot(organizationId)}/settings`,
  organizationWorkspaces: (organizationId: string) => `${organizationRoot(organizationId)}/settings/workspaces`,
  organizationWorkspaceSheet: (organizationId: string, workspaceId: string) =>
    `${organizationRoot(organizationId)}/settings/workspaces?workspace=${encodeURIComponent(workspaceId)}`,
  organizationMembers: (organizationId: string) => `${organizationRoot(organizationId)}/settings/members`,
  organizationMemberSheet: (organizationId: string, userId: string) =>
    `${organizationRoot(organizationId)}/settings/members?member=${encodeURIComponent(userId)}`,
} as const;

const PRESERVED_COLLECTION_SEGMENTS = new Set([
  "activity",
  "catalog",
  "connectors",
  "explore",
  "semantics",
  "lineage",
  "settings",
  "dashboards",
]);

export function workspaceSwitchPath(pathname: string, targetWorkspaceId: string): string {
  const match = pathname.match(/^\/workspaces\/[^/]+(?:\/(.*))?$/);
  const suffix = match?.[1] ?? "";
  if (!suffix) return webPaths.workspace(targetWorkspaceId);
  const segments = suffix.split("/").filter(Boolean);
  const surface = segments[0];
  if (PRESERVED_COLLECTION_SEGMENTS.has(surface) && segments.length === 1) {
    return `${workspaceRoot(targetWorkspaceId)}/${surface}`;
  }
  if (surface === "semantic-models") return webPaths.semanticModelCollection(targetWorkspaceId);
  if (surface === "metrics") return webPaths.metricCollection(targetWorkspaceId);
  if (surface === "pages") return webPaths.pages(targetWorkspaceId);
  if (surface === "dashboards") return webPaths.dashboards(targetWorkspaceId);
  return webPaths.workspace(targetWorkspaceId);
}
