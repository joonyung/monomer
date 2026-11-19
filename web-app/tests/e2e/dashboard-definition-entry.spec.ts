import type { DashboardDefinition, DashboardJsonObject } from "../../src/api/dashboard";
import { expect, test } from "./support/test";
import { mockSignedInWorkspace, problem } from "./support/session";
import { completeOAuthCallback } from "./oauth-callback-correlation";

const workspaceId = "0199f500-0000-7000-8000-000000000001";
const dashboardId = "0199f500-0000-7000-8000-000000000002";
const oldId = "0199f500-0000-7000-8000-000000000003";
const newId = "0199f500-0000-7000-8000-000000000004";
const path = `/workspaces/${workspaceId}/dashboards/${dashboardId}`;
const snapshot: DashboardDefinition = {
  schemaVersion: 1, meta: { title: "Definition entry" }, parameters: {},
  variables: { plan: { type: "TEXT", nullable: false, initial: "Starter" } }, queries: {},
  widgets: { intro: { type: "TEXT", content: "Previous dashboard snapshot" } },
  layout: { sections: [{ id: "intro", height: { mode: "CONTENT" }, root: { type: "WIDGET", id: "intro-node", widgetId: "intro" } }] },
};

async function setup(page: Parameters<typeof mockSignedInWorkspace>[0], current: DashboardDefinition, bindings: DashboardJsonObject = {}, reject = false) {
  const opens: DashboardJsonObject[] = [];
  const resolves: { id: string; variables: DashboardJsonObject }[] = [];
  let opened = false;
  await mockSignedInWorkspace(page, { workspaceId, workspaceName: "Sample", organizationId: "org", role: "EXPLORER", user: { id: "user", displayName: "Sample author", email: "sample@example.test" } });
  const session = (id: string) => ({ workspaceId, dashboardId, dashboardSessionId: id, definition: id === newId ? current : snapshot, parameterBindings: id === newId ? opens.at(-1) ?? {} : bindings, initialVariables: { plan: id === newId ? current.variables.plan.initial : "Starter" }, runningQueryRuns: [] });
  await page.route("**/v2/workspaces/**", async route => {
    const request = route.request();
    const pathname = new URL(request.url()).pathname;
    const json = (data: unknown, status = 200) => route.fulfill({ status, contentType: "application/json", body: JSON.stringify(data) });
    if (pathname.endsWith("/events")) return route.fulfill({ status: 200, contentType: "text/event-stream", body: "" });
    if (pathname.endsWith(":resolve")) {
      const id = pathname.includes(oldId) ? oldId : newId;
      resolves.push({ id, variables: request.postDataJSON().variables });
      return json({ variables: { ...session(id).initialVariables, ...request.postDataJSON().variables }, queryRuns: {} });
    }
    if (pathname.endsWith("/query-runs")) return json({ items: [], nextCursor: null });
    if (pathname.endsWith("/search")) return json({ dashboards: [{ dashboardId, title: current.meta.title }], metrics: [], semanticModels: [], tables: [] });
    if (pathname.includes("/dashboard-sessions/")) return json(session(pathname.includes(newId) ? newId : oldId));
    if (pathname.endsWith(`/dashboards/${dashboardId}/sessions`)) {
      if (request.method() === "POST") {
        opens.push(request.postDataJSON().parameterBindings);
        if (reject && opens.length === 1) return problem(route, 422, "INVALID_SESSION_PARAMETERS", "Inputs do not match the current definition.", "Invalid inputs");
        opened = true;
        return json(session(newId), 202);
      }
      return json({ items: [opened ? newId : oldId].map(id => ({ dashboardId, dashboardSessionId: id, parameterBindings: bindings, createdAt: "2026-10-13T00:00:00Z", updatedAt: "2026-10-13T00:00:00Z", runningQueryRuns: [] })), nextCursor: null });
    }
    if (pathname.endsWith(`/dashboards/${dashboardId}`)) return json({ workspaceId, dashboardId, definition: current, updatedAt: "2026-10-13T00:00:00Z" });
    return json({ items: [] });
  });
  return { opens, resolves };
}

const changed = (): DashboardDefinition => ({ ...snapshot, meta: { title: "Definition entry", description: "Applied presentation change" }, variables: { plan: { type: "TEXT", nullable: false, initial: "Team" } }, widgets: { intro: { type: "TEXT", content: "Current dashboard snapshot" } } });

test("bare internal entry opens Apply snapshot changes once, with immutable inputs and new initial Variables", async ({ page }) => {
  const fixture = await setup(page, changed());
  await completeOAuthCallback(page, { new_user: "false", auth_provider: "local" }, path);
  await expect(page).toHaveURL(new RegExp(`session=${newId}`));
  await expect(page.getByText("Current dashboard snapshot", { exact: true })).toBeVisible();
  await expect(page.getByText("Updated for definition changes. Previous results are in History.")).toBeVisible();
  await expect.poll(() => fixture.resolves.length).toBe(1);
  expect(fixture.opens).toEqual([{}]);
  expect(fixture.resolves).toEqual([{ id: newId, variables: { plan: "Team" } }]);
  await page.evaluate(() => window.dispatchEvent(new Event("focus")));
  expect(fixture.opens).toHaveLength(1);
});

test("explicit Session links preserve their snapshot and a same definition bare entry only resolves", async ({ page }) => {
  const fixture = await setup(page, changed());
  await completeOAuthCallback(page, { new_user: "false", auth_provider: "local" }, `${path}?session=${oldId}`);
  await expect(page.getByText("Previous dashboard snapshot", { exact: true })).toBeVisible();
  expect(fixture.opens).toEqual([]);
  await expect.poll(() => fixture.resolves.length).toBe(1);
});

test("unchanged snapshots restore and resolve without opening new results", async ({ page }) => {
  const fixture = await setup(page, snapshot);
  await completeOAuthCallback(page, { new_user: "false", auth_provider: "local" }, path);
  await expect(page).toHaveURL(new RegExp(`session=${oldId}`));
  await expect.poll(() => fixture.resolves.length).toBe(1);
  expect(fixture.opens).toEqual([]);
});

test("cached bare re-entry retries a failed current definition read before restoring results", async ({ page, consoleErrors }) => {
  const fixture = await setup(page, snapshot);
  await completeOAuthCallback(page, { new_user: "false", auth_provider: "local" }, path);
  await expect(page).toHaveURL(new RegExp(`session=${oldId}`));
  await expect(page.getByText("Previous dashboard snapshot", { exact: true })).toBeVisible();
  await expect.poll(() => fixture.resolves.length).toBe(1);

  await page.keyboard.press("ControlOrMeta+k");
  const palette = page.getByRole("dialog", { name: "Search" });
  await palette.getByRole("group", { name: "Go to" }).getByRole("option", { name: /^Dashboards/ }).click();
  await expect(page).toHaveURL(`/workspaces/${workspaceId}/dashboards`);
  await expect(palette).toHaveCount(0);
  await expect(page.getByRole("heading", { name: "Your agent builds dashboards" })).toBeVisible();

  consoleErrors.allow({ message: /Failed to load resource: net::ERR_FAILED/, url: new RegExp(`/dashboards/${dashboardId}$`), reason: "The cached bare entry must expose Retry when refreshing the current definition fails." });
  let failRead = true;
  await page.route(`**/v2/workspaces/${workspaceId}/dashboards/${dashboardId}`, route => failRead ? route.abort("failed") : route.fulfill({
    status: 200,
    contentType: "application/json",
    body: JSON.stringify({ workspaceId, dashboardId, definition: snapshot, updatedAt: "2026-10-13T00:00:00Z" }),
  }));
  await page.keyboard.press("ControlOrMeta+k");
  await expect(palette).toBeVisible();
  await palette.getByRole("combobox", { name: "Search or go to" }).fill(snapshot.meta.title);
  await expect(palette.getByRole("group", { name: "Dashboards" }).getByRole("option", { name: snapshot.meta.title })).toBeVisible();
  await page.keyboard.press("Enter");
  await expect(palette).toHaveCount(0);
  await expect(page.getByText("Failed to fetch", { exact: true })).toBeVisible();
  await expect(page.getByRole("button", { name: "Retry", exact: true })).toBeVisible();
  expect(fixture.resolves).toHaveLength(1);
  expect(fixture.opens).toEqual([]);

  failRead = false;
  await page.getByRole("button", { name: "Retry", exact: true }).click();
  await expect(page).toHaveURL(new RegExp(`session=${oldId}`));
  await expect(page.getByText("Previous dashboard snapshot", { exact: true })).toBeVisible();
  await expect.poll(() => fixture.resolves.length).toBe(2);
  expect(fixture.opens).toEqual([]);
});

test("Control rejects changed types: restore current behavior and prefill compatible Change inputs", async ({ page, consoleErrors }) => {
  const current = { ...changed(), parameters: { region: { type: "TEXT" as const, nullable: false, label: "Region" }, subscribed: { type: "BOOLEAN" as const, nullable: false, label: "Subscribed" } } };
  const fixture = await setup(page, current, { region: "EMEA", subscribed: "previous text", removed: "old" }, true);
  consoleErrors.allow({ status: 422, url: /\/sessions$/, reason: "Control validates the restored bindings against the changed definition before accepting a Session." });
  await completeOAuthCallback(page, { new_user: "false", auth_provider: "local" }, path);
  await expect(page).toHaveURL(new RegExp(`session=${oldId}`));
  const dialog = page.getByRole("dialog", { name: "Change inputs" });
  await expect(dialog).toBeVisible();
  await expect(dialog.getByRole("textbox", { name: "Region" })).toHaveValue("EMEA");
  await expect(page.getByText("The dashboard inputs have changed. Review inputs to use the updated dashboard.")).toBeVisible();
  await expect.poll(() => fixture.resolves.length).toBe(1);
  expect(fixture.opens).toHaveLength(1);
});

test("removed final input still permits explicit empty-input confirmation after Control rejects old bindings", async ({ page, consoleErrors }) => {
  const fixture = await setup(page, changed(), { removed: "old" }, true);
  consoleErrors.allow({ status: 422, url: /\/sessions$/, reason: "The applied definition removed the final Parameter." });
  await completeOAuthCallback(page, { new_user: "false", auth_provider: "local" }, path);
  const dialog = page.getByRole("dialog", { name: "Change inputs" });
  await expect(dialog.getByText("No inputs required.")).toBeVisible();
  await dialog.getByRole("button", { name: "Show results", exact: true }).click();
  await expect(page).toHaveURL(new RegExp(`session=${newId}`));
  expect(fixture.opens).toEqual([{ removed: "old" }, {}]);
});
