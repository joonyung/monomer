import { readFileSync } from "node:fs";
import type { Page } from "@playwright/test";
import { expect, test } from "./support/test";
import { completeOAuthCallback } from "./oauth-callback-correlation";
import { mockSignedInWorkspace } from "./support/session";
import { mockOverviewSources } from "./support/overview";
import { evidencePath } from "./evidence";

const workspaceId = "019e8000-0000-7000-8000-000000000d01";
const alex = "019e8000-0000-7000-8000-000000000d02";
const mina = "019e8000-0000-7000-8000-000000000d03";
const claude = { clientId: "https://claude.ai/oauth/mcp-oauth-client-metadata", name: "Claude", verifiedHost: "claude.ai" };
const json = (value: unknown) => ({ status: 200, contentType: "application/json", body: JSON.stringify(value) });

const base = (index: number) => ({
  id: `019e8000-0000-7000-8000-${String(index).padStart(12, "0")}`, kind: "EXPLORE", description: "Explore", state: "SUCCEEDED",
  startedAt: new Date(Date.now() - 120_000).toISOString(), occurredAt: new Date(Date.now() - 60_000).toISOString(), actorKind: "USER",
  failureReason: null, href: `/workspaces/${workspaceId}/explore?run=019e8000-0000-7000-8000-${String(index).padStart(12, "0")}`,
});
const running = { ...base(1), title: "Churn by plan", state: "RUNNING", occurredAt: new Date().toISOString(), actorId: alex, actorName: "Alex Morgan", credentialSurface: "AGENT", agentApp: claude };
const agentRun = { ...base(2), title: "Orders by region", actorId: mina, actorName: "Mina Park", credentialSurface: "AGENT", agentApp: claude };
const webChange = { ...base(3), kind: "METRIC", title: "Revenue", description: "Metric saved · revenue", state: "UPDATED", startedAt: null, actorId: alex, actorName: "Alex Morgan", credentialSurface: "WEB", agentApp: null, href: `/workspaces/${workspaceId}/metrics/019e8000-0000-7000-8000-000000000003` };

async function authenticate(page: Page) {
  await page.route("**/geist-latin-wght-normal.woff2", (route) => route.fulfill({ contentType: "font/woff2", body: readFileSync("node_modules/@fontsource-variable/geist/files/geist-latin-wght-normal.woff2") }));
  await mockSignedInWorkspace(page, {
    workspaceId, workspaceName: "Commerce Insights", organizationId: "organization-1", role: "EXPLORER",
    user: { id: alex, email: "activity@example.test", displayName: "Alex Morgan" },
  });
  await completeOAuthCallback(page, { new_user: "false", auth_provider: "google" }, `/workspaces/${workspaceId}`);
}

test("Activity lists who ran what and keeps only the scope in the URL", async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 900 });
  await mockOverviewSources(page, workspaceId);
  const reads: URLSearchParams[] = [];
  await page.route("**/overview/activity?**", (route) => {
    const params = new URL(route.request().url()).searchParams;
    reads.push(params);
    const matches = (item: { actorId: string; credentialSurface: string }) => (params.get("actor") !== "me" || item.actorId === alex)
      && (!params.get("surface") || item.credentialSurface === params.get("surface"));
    const source = params.get("section") === "live" ? [running] : [agentRun, webChange];
    return route.fulfill(json({ items: source.filter(matches), watched: [], nextCursor: null, unavailableSources: [], observedAt: new Date().toISOString() }));
  });
  await authenticate(page);

  await page.getByRole("link", { name: "View all activity" }).click();
  await expect(page).toHaveURL(new RegExp(`/workspaces/${workspaceId}/activity(\\?scope=all)?$`));
  await expect(page.getByRole("heading", { name: "Activity", level: 1 })).toBeVisible();
  await expect(page.getByRole("navigation", { name: "Workspace navigation" }).getByRole("link", { name: "Activity", exact: true })).toHaveAttribute("aria-current", "page");
  const activity = page.getByRole("region", { name: "Workspace activity" });
  const rows = activity.locator(".ov-timeline li");
  await expect(rows).toHaveCount(3);
  await expect(rows.first()).toContainText("Churn by plan");
  await expect(rows.first().locator('[data-work-status="RUNNING"]')).toBeVisible();
  await expect(activity.getByRole("link", { name: /Orders by region.*Mina Park.*via Claude \(claude\.ai\)/ })).toHaveAttribute("href", agentRun.href);
  await expect(activity.getByRole("combobox")).toHaveCount(0);
  await page.screenshot({ path: evidencePath("screenshots/workspace-activity.png") });

  const agentsTab = activity.getByRole("button", { name: "Agents", exact: true });
  const tabLeft = (await agentsTab.boundingBox())!.x;
  await agentsTab.click();
  // Choosing a scope never moves the tabs.
  expect((await agentsTab.boundingBox())!.x).toBe(tabLeft);
  await expect.poll(() => Object.fromEntries(new URL(page.url()).searchParams)).toEqual({ scope: "agents" });
  await expect(rows).toHaveCount(2);
  expect(reads.some((params) => params.get("section") === "recent" && params.get("surface") === "AGENT" && params.get("actor") === "all")).toBe(true);
  await page.screenshot({ path: evidencePath("screenshots/workspace-activity-agents.png") });

  await page.reload();
  await expect(activity.getByRole("button", { name: "Agents", exact: true })).toHaveAttribute("aria-pressed", "true");
  await expect(rows).toHaveCount(2);

  const mineTab = activity.getByRole("button", { name: "Mine", exact: true });
  const mineLeft = (await mineTab.boundingBox())!.x;
  await mineTab.click();
  expect((await mineTab.boundingBox())!.x).toBe(mineLeft);
  await expect.poll(() => Object.fromEntries(new URL(page.url()).searchParams)).toEqual({ scope: "mine" });
  await expect(rows).toHaveCount(2);
});
