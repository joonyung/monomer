import type { components } from "./generated";

type Resource = components["schemas"]["AgentWorkspaceAgentResourceEnvelope"];
type ListingItem = components["schemas"]["AgentWorkspaceAgentResourceListingItem"];
type AvailableOperation = components["schemas"]["AgentWorkspaceAgentAvailableOperation"];
type ActionResult = components["schemas"]["AgentWorkspaceAgentActionResult"];

export function narrowAgentResource(resource: Resource) {
  if (resource.kind === "METRIC") {
    const metric: components["schemas"]["AgentWorkspaceMetricResourceBody"] = resource.body;
    // @ts-expect-error Metric has no public revision authority.
    void resource.revision;
    return metric.name;
  }
  if (resource.kind === "DASHBOARD_DRAFT") {
    const revision: string = resource.revision;
    const title: string = resource.body.definition.meta.title;
    return `${title}:${revision}`;
  }
  if (resource.kind === "DASHBOARD_SESSION") {
    // @ts-expect-error Session has no public revision.
    void resource.revision;
    return resource.body.initialVariables;
  }
  if (resource.kind === "CATALOG_ROOT") {
    // @ts-expect-error Catalog navigation is bodyless.
    void resource.body;
  }
  return resource.path;
}

export function narrowAgentListing(item: ListingItem) {
  if (item.kind === "METRIC") {
    const name: string = item.name;
    // @ts-expect-error Metric summary is not a run summary.
    void item.state;
    // @ts-expect-error Metric summary has no Dashboard query name.
    void item.queryName;
    return name;
  }
  return item.path;
}

export function narrowAgentOperation(operation: AvailableOperation) {
  if (operation.operation === "EDIT") {
    // @ts-expect-error Generic JSON Patch has no input discriminator.
    void operation.inputKind;
  }
  if (operation.operation === "INVOKE") {
    const action: string = operation.action;
    return action;
  }
  return operation.operation;
}

export function narrowAgentAction(result: ActionResult) {
  if (result.operation === "CREATE" || result.operation === "EDIT") {
    const status: "COMPLETED" = result.status;
    // @ts-expect-error Only INVOKE results carry action.
    void result.action;
    return status;
  }
  const action: string = result.action;
  return action;
}

export const dashboardBindings: components["schemas"]["AgentWorkspaceDashboardValueMap"] = {
  arbitrary_parameter: "12.5",
  another_variable: ["first", "second"],
  nullable_parameter: null,
  period: { start: "2026-09-01", end: "2026-09-06" },
};

// @ts-expect-error Metric envelopes require the owner definition body.
export const metricWithoutBody: Resource = { requestId: "r1", kind: "METRIC", path: "workspace/metrics/revenue" };
// @ts-expect-error Metric listing names are required.
export const metricWithoutName: ListingItem = { kind: "METRIC", path: "workspace/metrics/revenue" };
// @ts-expect-error INVOKE cannot omit the declared action.
export const invokeWithoutAction: AvailableOperation = { operation: "INVOKE", path: "workspace/explore/runs" };
// @ts-expect-error CREATE inputKind must match its collection.
export const wrongCreateInput: AvailableOperation = { operation: "CREATE", path: "workspace/metrics", inputKind: "DASHBOARD_DEFINITION" };


type ExploreHistory = components["schemas"]["AgentWorkspaceExploreRunResourceBody"];
const sqlHistory = {
  runKind: "SQL", connectorId: "01942b04-6e00-7000-8000-000000000010", state: "SUCCEEDED",
  sql: "select 1", createdAt: "2026-09-06T00:00:00Z", updatedAt: "2026-09-06T00:00:00Z",
} as const;

export function narrowExploreHistory(body: ExploreHistory) {
  const sql: string = body.sql;
  // @ts-expect-error History has no parameters.
  void body.parameters;
  if (body.runKind === "SEMANTIC") {
    const query: components["schemas"]["SemanticQueryDocument"] = body.query;
    return query.metrics;
  }
  // @ts-expect-error SQL history cannot carry a Semantic query.
  void body.query;
  return sql;
}

export function metadataOnlyExploreListing(item: ListingItem) {
  if (item.kind === "EXPLORE_RUN") {
    // @ts-expect-error SQL history is only in exact READ.
    void item.sql;
    // @ts-expect-error Semantic query history is only in exact READ.
    void item.query;
  }
}

export const validSqlHistory: ExploreHistory = sqlHistory;
// @ts-expect-error SQL history requires sql.
export const historyWithoutSql: ExploreHistory = { runKind: "SQL", connectorId: "id", state: "RUNNING", createdAt: "now", updatedAt: "now" };
// @ts-expect-error Semantic history requires query.
export const semanticHistoryWithoutQuery: ExploreHistory = { ...sqlHistory, runKind: "SEMANTIC" };
// @ts-expect-error SQL history cannot carry query, even null.
export const sqlHistoryWithQuery: ExploreHistory = { ...sqlHistory, query: null };
// @ts-expect-error Semantic history cannot carry null query.
export const semanticHistoryWithNullQuery: ExploreHistory = { ...sqlHistory, runKind: "SEMANTIC", query: null };
// @ts-expect-error History has no parameters.
export const historyWithParameters: ExploreHistory = { ...sqlHistory, parameters: {} };

export function narrowAgentSearch(
  request: components["schemas"]["AgentSearchRequest"],
  response: components["schemas"]["AgentSearchResponse"],
) {
  const queries: string[] = request.queries;
  const limit: number = request.limitPerQuery;
  // @ts-expect-error Search no longer accepts a single query.
  void request.query;
  // @ts-expect-error Search has no cursor.
  void request.cursor;
  // @ts-expect-error Search groups items by input query.
  void response.items;
  return { queries, limit, items: response.results.flatMap((result) => result.items) };
}
