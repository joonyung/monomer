import { mockSemanticLineage } from "./support/semantic-lineage";
import type { Page } from "@playwright/test";
import { completeOAuthCallback } from "./oauth-callback-correlation";
import { json, mockSignedInWorkspace, problem } from "./support/session";
import { evidencePath } from "./evidence";
import { expect, test } from "./support/test";

const workspaceId = "019e8000-0000-7000-8000-000000000d57";
const modelId = "019e8000-0000-7000-8000-000000000e57";
const metricId = "019e8000-0000-7000-8000-000000000f57";
const connectorId = "019e8000-0000-7000-8000-000000000c57";
const root = `/v2/workspaces/${workspaceId}/semantic`;
const model = { sourcePartitionInfo: [],
  semanticModelId: modelId, workspaceId, definition: { schemaVersion: 3, name: "orders", label: "Orders", sourceRelations: [{ connectorId, schema: "analytics", relation: "orders" }], entities: [{ name: "order_id", type: "primary", expression: "order_id" }], dimensions: [], measures: [{ name: "amount", aggregation: "sum", expression: "amount" }] }, createdAt: "2026-10-05T10:00:00Z", updatedAt: "2026-10-06T10:00:00Z",
};
const metric = {
  metricId, workspaceId, definition: { schemaVersion: 3, name: "revenue", label: "Revenue", type: "simple", semanticModelName: "orders", measure: "amount" }, createdAt: "2026-10-05T10:00:00Z", updatedAt: "2026-10-06T10:00:00Z",
};
const growth = { ...metric, metricId: "019e8000-0000-7000-8000-000000000f58", definition: { schemaVersion: 3, name: "revenue_growth", label: "Revenue growth", type: "cumulative", inputMetric: { metricName: "revenue" } } };
const filterOnly = { ...metric, metricId: "019e8000-0000-7000-8000-000000000f59", definition: { schemaVersion: 3, name: "filtered_customers", label: "Filtered customers", type: "simple", semanticModelName: "customers", measure: "count", filters: [{ not: { dimension: { semanticModelName: "orders", name: "region" }, operator: "eq", values: ["US"] } }] } };
const actor = { id: "definition-editor", displayName: "Sam Kim" };
const agentApp = { clientId: "https://claude.ai", name: "Claude", verifiedHost: "claude.ai" };
type Kind = "metrics" | "semantic-models";
const idFor = (kind: Kind) => kind === "metrics" ? metricId : modelId;
const nameFor = (kind: Kind) => kind === "metrics" ? "revenue" : "orders";
const targetFor = (kind: Kind) => kind === "metrics" ? metric : model;
const definitionFor = (kind: Kind) => kind === "metrics" ? metric.definition : model.definition;
const detailPath = (kind: Kind) => `/workspaces/${workspaceId}/${kind}/${idFor(kind)}`;

async function install(page: Page, kind: Kind, options: { deleted?: boolean; role?: "EDITOR" | "EXPLORER"; conflict?: boolean; loseDeleteResponse?: boolean } = {}) {
  await mockSignedInWorkspace(page, { workspaceId, workspaceName: "Definition history", organizationId: "organization-57", role: options.role ?? "EDITOR", user: { id: actor.id, displayName: actor.displayName, email: "sam@example.test" } });
  await mockSemanticLineage(page);
  let deleted = options.deleted ?? false;
  const writes: { method: string; path: string; key?: string }[] = [];
  const unexpected: string[] = [];
  let lostResponse = false;
  await page.route("**/v2/workspaces/**", async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    const path = url.pathname;
    const method = request.method();
    if (path === `/v2/workspaces/${workspaceId}/connectors`) return json(route, { connectors: [{ id: connectorId, workspaceId, name: "Warehouse", type: "POSTGRESQL", properties: { host: "warehouse.internal", port: 5432, database: "warehouse" }, createdAtEpochMs: 1, updatedAtEpochMs: 1 }] });
    if (method === "GET" && path === `/v2/workspaces/${workspaceId}/catalog/connectors/${connectorId}/relations/orders` && url.searchParams.get("schema") === "analytics") return json(route, { requiresPartitionFilter: null,
      name: "orders", schemaName: "analytics", kind: "TABLE",
      columns: [
        { name: "order_id", nativeType: "bigint", nullable: false, ordinalPosition: 1 },
        { name: "amount", nativeType: "numeric", nullable: true, ordinalPosition: 2 },
      ],
      primaryKey: { columns: ["order_id"] }, clusteringKeys: [], foreignKeys: [], partitionKeys: [],
    });
    if (path === `${root}/semantic-models` && method === "GET") return json(route, kind === "semantic-models" && deleted ? [] : [model]);
    if (path === `${root}/metrics` && method === "GET") return json(route, [metric, growth, filterOnly].filter((item) => !(kind === "metrics" && deleted && item.metricId === metricId)));
    if (path === `${root}/query/group-bys`) return json(route, { groupBys: [], excludedSemanticModels: [] });
    if (path.endsWith("/check")) return json(route, { partitionFilters: [], ok: true, problems: [], preview: null, runsOn: [connectorId] });
    if (path === `${root}/time-spines`) return json(route, []);
    if (path === `${root}/${kind}/${idFor(kind)}/history`) {
      const change = { changeId: "019e8000-0000-7000-8000-000000000157", action: deleted ? "DELETED" : "UPDATED", restorable: deleted, definition: definitionFor(kind), occurredAt: "2026-10-06T10:00:00Z", actor, credentialSurface: "AGENT", agentApp };
      return json(route, { definitionId: idFor(kind), name: nameFor(kind), deleted, changes: [change, { ...change, changeId: "019e8000-0000-7000-8000-000000000158", action: "CREATED", restorable: false, credentialSurface: "WEB", agentApp: null }], nextCursor: null });
    }
    if (path.endsWith("/restore") && method === "POST") {
      writes.push({ method, path, key: request.headers()["idempotency-key"] });
      if (options.conflict) return problem(route, 409, "SEMANTIC_NAME_CONFLICT", "Another definition uses this name.", "Conflict");
      deleted = false;
      return json(route, targetFor(kind));
    }
    if (path === `${root}/${kind}/${idFor(kind)}`) {
      if (method === "DELETE") {
        writes.push({ method, path });
        if (deleted) return problem(route, 404, "NOT_FOUND", "Definition was not found.", "Not Found");
        deleted = true;
        if (options.loseDeleteResponse && !lostResponse) { lostResponse = true; return route.abort("failed"); }
        return route.fulfill({ status: 204 });
      }
      if (method === "GET") return deleted ? problem(route, 404, "NOT_FOUND", "Definition was not found.", "Not Found") : json(route, { ...targetFor(kind), updatedBy: actor, credentialSurface: "AGENT", agentApp });
    }
    unexpected.push(`${method} ${path}`);
    return problem(route, 404, "NOT_FOUND", `No fixture for ${path}.`, "Not Found");
  });
  return { writes, unexpected, markDeleted: () => { deleted = true; } };
}

for (const kind of ["semantic-models", "metrics"] as const) {
  test(`${kind} delete shows impact, then deleted history restores the original id`, async ({ page }) => {
    const fixture = await install(page, kind);
    await page.setViewportSize({ width: 1440, height: 900 });
    await completeOAuthCallback(page, { new_user: "false", auth_provider: "google" }, detailPath(kind));
    await page.getByRole("button", { name: "Definition actions" }).click();
    await page.getByRole("menuitem", { name: "Delete", exact: true }).click();
    const dialog = page.getByRole("alertdialog");
    await expect(dialog).toContainText("You can restore it later from its history.");
    await expect(dialog).toContainText("Dashboards and pages that use it stop updating.");
    await expect(dialog.getByRole("link", { name: "Revenue growth", exact: true })).toBeVisible();
    if (kind === "semantic-models") await expect(dialog.getByRole("link", { name: "Filtered customers", exact: true })).toBeVisible();
    const remove = dialog.getByRole("button", { name: kind === "metrics" ? "Delete metric" : "Delete model", exact: true });
    const input = dialog.getByRole("textbox", { name: `Type “${nameFor(kind)}” to confirm` });
    await input.fill("wrong_name"); await expect(remove).toBeDisabled();
    await input.fill(nameFor(kind)); await expect(remove).toBeEnabled();
    await page.screenshot({ path: evidencePath(`../screenshots/joo-57-${kind}-delete.png`) });
    await remove.click();
    await expect(page).toHaveURL((url) => url.pathname === `/workspaces/${workspaceId}/semantics` && url.searchParams.get("view") === (kind === "metrics" ? null : "models"));
    expect(fixture.writes.filter((write) => write.method === "DELETE")).toHaveLength(1);
    await page.goto(`${detailPath(kind)}/history`);
    await expect(page.getByRole("heading", { name: `${definitionFor(kind).label} history`, exact: true })).toBeVisible();
    await expect(page.getByText(nameFor(kind), { exact: true })).toBeVisible();
    await expect(page.getByRole("status")).toContainText("Deleted");
    await expect(page.getByText("via Claude", { exact: false }).first()).toBeVisible();
    await page.getByText("View definition", { exact: true }).first().click();
    await expect(page.locator("pre").first()).toContainText('"schemaVersion": 3');
    await page.screenshot({ path: evidencePath(`../screenshots/joo-57-${kind}-history.png`) });
    await page.getByRole("button", { name: "Restore", exact: true }).click();
    await expect(page).toHaveURL(new RegExp(`${detailPath(kind)}(?:\\?edit=false)?$`));
    expect(fixture.writes.at(-1)?.key).toMatch(/^[A-Za-z0-9._:-]{1,200}$/);
    expect(fixture.unexpected).toEqual([]);
  });
}

test("Explorer reads deleted history but has no Delete or Restore operation", async ({ page }) => {
  const fixture = await install(page, "metrics", { role: "EXPLORER" });
  await completeOAuthCallback(page, { new_user: "false", auth_provider: "google" }, detailPath("metrics"));
  await page.getByRole("button", { name: "Definition actions" }).click();
  await expect(page.getByRole("menuitem", { name: "Delete", exact: true })).toHaveCount(0);
  fixture.markDeleted();
  await page.getByRole("menuitem", { name: "History", exact: true }).click();
  await expect(page.getByRole("heading", { name: "Revenue history", exact: true })).toBeVisible();
  await expect(page.getByRole("status")).toContainText("Deleted");
  await expect(page.getByRole("button", { name: /^Restore/ })).toHaveCount(0);
});

test("restore name conflict leaves deleted history and reuses the retry key", async ({ page, consoleErrors }) => {
  const fixture = await install(page, "metrics", { deleted: true, conflict: true });
  consoleErrors.allow({ status: 409, url: /\/history\/[^/]+\/restore$/, reason: "Restoring an original id conflicts with a definition that already uses its name." });
  await completeOAuthCallback(page, { new_user: "false", auth_provider: "google" }, `${detailPath("metrics")}/history`);
  const restore = page.getByRole("button", { name: "Restore", exact: true });
  await restore.click();
  await expect(page.getByRole("alert")).toContainText("The name “revenue” is already used");
  await expect(page.getByRole("status")).toContainText("Deleted");
  await restore.click();
  await expect.poll(() => fixture.writes.length).toBe(2);
  expect(fixture.writes[0].key).toBe(fixture.writes[1].key);
});

test("a lost delete response followed by 404 says already deleted", async ({ page, consoleErrors }) => {
  await install(page, "metrics", { loseDeleteResponse: true });
  consoleErrors.allow({ message: /Failed to load resource: net::ERR_FAILED/, url: /\/semantic\/metrics\/[^/]+$/, reason: "The server commits deletion but this fixture drops its response." });
  consoleErrors.allow({ status: 404, url: /\/semantic\/metrics\/[^/]+$/, reason: "A repeat delete after the lost response returns Not Found." });
  await completeOAuthCallback(page, { new_user: "false", auth_provider: "google" }, detailPath("metrics"));
  await page.getByRole("button", { name: "Definition actions" }).click();
  await page.getByRole("menuitem", { name: "Delete", exact: true }).click();
  const dialog = page.getByRole("alertdialog");
  await dialog.getByRole("textbox").fill("revenue");
  await dialog.getByRole("button", { name: "Delete metric", exact: true }).click();
  await expect(dialog.getByRole("alert")).toBeVisible();
  await dialog.getByRole("button", { name: "Delete metric", exact: true }).click();
  await expect(dialog.getByRole("alert")).toContainText("This definition is already deleted.");
  await dialog.getByRole("button", { name: "Back to Semantics", exact: true }).click();
  await expect(page).toHaveURL(new RegExp(`/workspaces/${workspaceId}/semantics`));
});

test("Lineage renders a missing definition beside healthy resources without offering focus", async ({ page }) => {
  const fixture = await install(page, "semantic-models");
  const modelRef = { kind: "SEMANTIC_MODEL", id: modelId };
  const metricRef = { kind: "METRIC", id: metricId };
  const tableRef = { kind: "RELATION", id: "019e8000-0000-7000-8000-000000000057" };
  const missingRef = { kind: "METRIC", id: "019e8000-0000-7000-8000-000000000058" };
  const resource = (ref: typeof modelRef, label: string, address: string) => ({ resource: ref, label, address, description: null, missing: false, connector: null });
  await page.route("**/v2/graphql", async (route) => {
    const body = route.request().postDataJSON() as { query: string };
    if (!body.query.includes("query LineageFocusCone")) return route.fallback();
    return json(route, { data: { lineageFocusCone: {
      workspaceId, dashboardUsages: [], focus: resource(modelRef, "Orders", "orders"), ancestors: [{ ...resource(tableRef, "orders", "analytics.orders"), connector: { id: connectorId, displayName: "Warehouse", type: "POSTGRESQL" } }], descendants: [resource(metricRef, "Revenue", "revenue")], siblings: [],
      missingDefinitions: [{ ...resource(missingRef, "old_revenue", "old_revenue"), missing: true }],
      edges: [{ dependent: modelRef, parent: tableRef }, { dependent: metricRef, parent: modelRef }, { dependent: metricRef, parent: missingRef }], siblingEdges: [], entityPeers: [],
      detail: { relation: null, semanticModel: { entities: [{ name: "order_id", type: "PRIMARY", label: null, description: null }], dimensions: [], measures: [] }, metric: null },
    } } });
  });
  await completeOAuthCallback(page, { new_user: "false", auth_provider: "google" }, `/workspaces/${workspaceId}/lineage?kind=SEMANTIC_MODEL&id=${modelId}`);
  await expect(page.getByRole("button", { name: "Select old_revenue", exact: true })).toHaveCount(0);
  await page.getByRole("button", { name: "Expand Revenue", exact: true }).click();
  const missingNode = page.locator(`[data-id="METRIC:${missingRef.id}"]`);
  await expect(missingNode).toContainText("Missing definition");
  await missingNode.getByRole("button", { name: "Select old_revenue", exact: true }).click();
  await expect(page.getByRole("button", { name: "Focus selected", exact: true })).toBeDisabled();
  await expect(page.getByRole("region", { name: "Broken references" })).toContainText("old_revenue");
  await page.screenshot({ path: evidencePath("../screenshots/joo-57-missing-lineage.png") });
  expect(fixture.unexpected).toEqual([]);
});
