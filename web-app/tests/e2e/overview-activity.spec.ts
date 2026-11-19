import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { expect, test } from "./support/test";
import type { Page } from "@playwright/test";
import type { components } from "../../src/api/control/generated";
import { completeOAuthCallback } from "./oauth-callback-correlation";
import { mockSignedInWorkspace } from "./support/session";
import { mockSemanticLineage } from "./support/semantic-lineage";
import { mockOverviewSources } from "./support/overview";
import { evidencePath } from "./evidence";

const workspaceId = "019e8000-0000-7000-8000-000000000b01";
const principalId = "019e8000-0000-7000-8000-000000000b02";
const json = (value: unknown) => ({ status: 200, contentType: "application/json", body: JSON.stringify(value) });
const claude = { clientId: "https://claude.ai/oauth/mcp-oauth-client-metadata", name: "Claude", verifiedHost: "claude.ai" };
const items = Array.from({ length: 14 }, (_, index) => ({
  id: `019e8000-0000-7000-8000-${String(index + 1).padStart(12, "0")}`, kind: "EXPLORE", title: `SQL query ${index + 1}`, description: "Explore",
  state: "RUNNING", startedAt: new Date(Date.now() - 90000 - index * 1000).toISOString(), occurredAt: new Date().toISOString(),
  actorId: index % 2 ? "019e8000-0000-7000-8000-000000000b03" : principalId, actorName: index % 2 ? "Mina Park" : "Alex Morgan", actorKind: "USER", credentialSurface: index % 2 ? "WEB" : "AGENT", agentApp: index % 2 ? null : claude, failureReason: null,
  href: `/workspaces/${workspaceId}/explore?run=019e8000-0000-7000-8000-${String(index + 1).padStart(12, "0")}`,
}));
async function authenticate(page: Page, options: { role?: "EXPLORER" | "OWNER"; workspaceName?: string; owners?: Array<{ userId: string; displayName: string }> } = {}) {
  await page.route("**/geist-latin-wght-normal.woff2", (route) => route.fulfill({ contentType: "font/woff2", body: readFileSync("node_modules/@fontsource-variable/geist/files/geist-latin-wght-normal.woff2") }));
  await mockSignedInWorkspace(page, {
    workspaceId,
    workspaceName: options.workspaceName ?? "Commerce Insights",
    organizationId: "organization-1",
    role: options.role ?? "EXPLORER",
    user: { id: principalId, email: "overview@example.test", displayName: "Alex Morgan" },
    owners: options.owners,
  });
  await completeOAuthCallback(page, { new_user: "false", auth_provider: "google" }, `/workspaces/${workspaceId}`);
}

// Registered first so a test's own routes for the same reads take precedence.
test.beforeEach(async ({ page }) => mockOverviewSources(page, workspaceId));

test("Recent activity highlights only fresh resource changes and preserves the row", async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 900 });
  const saved = (id: number, title: string) => ({ ...items[id], kind: "SEMANTIC_MODEL", state: "UPDATED", title, description: "Semantic Model saved", occurredAt: "2026-09-13T05:00:00Z" });
  let current = [saved(0, "Orders model"), saved(1, "Customers model")];
  const historical = saved(2, "Historical model");
  let calls = 0;
  await page.route("**/overview/activity?**", (route) => {
    const params = new URL(route.request().url()).searchParams;
    const recent = params.get("section") === "recent";
    if (recent) calls++;
    return route.fulfill(json({ items: !recent ? [] : params.get("cursor") ? [historical, saved(0, "Orders model")] : params.get("actor") === "me" ? [saved(3, "My model")] : params.get("surface") === "AGENT" ? [{ ...saved(2, "Agent model"), credentialSurface: "AGENT", agentApp: null }, { ...saved(4, "Claude model"), credentialSurface: "AGENT", agentApp: claude }] : current, watched: [], nextCursor: recent && !params.get("cursor") ? "older" : null, unavailableSources: [], observedAt: new Date().toISOString() }));
  });
  await authenticate(page);
  const row = page.locator(`[data-change-id="SEMANTIC_MODEL:${items[0].id}"]`);
  await expect(row).toBeVisible();
  await expect(page.locator("[data-change-active]")).toHaveCount(0);
  await page.screenshot({ path: evidencePath("../screenshots/joo-50/overview-normal.png") });
  await row.getByRole("link").focus();
  const original = await row.elementHandle();
  current = [{ ...current[0], occurredAt: "2026-09-13T05:01:00Z" }, current[1]];
  await expect(row).toHaveAttribute("data-change-active", "true", { timeout: 5000 });
  await row.locator(".change-highlight-overlay").evaluate((element) => { for (const animation of element.getAnimations()) { animation.pause(); animation.currentTime = 150; } });
  await expect(page.locator("[data-change-active]")).toHaveCount(1);
  expect(await original!.evaluate((element) => element.isConnected && element.contains(document.activeElement))).toBe(true);
  await page.screenshot({ path: evidencePath("../screenshots/joo-50/overview-focus-active.png") });
  await page.getByRole("heading", { name: "Recent activity", exact: true }).click();
  await page.screenshot({ path: evidencePath("../screenshots/joo-50/overview-active.png") });
  await row.locator(".change-highlight-overlay").evaluate((element) => { for (const animation of element.getAnimations()) animation.finish(); });
  await expect(row).not.toHaveAttribute("data-change-active");
  await page.screenshot({ path: evidencePath("../screenshots/joo-50/overview-finished.png") });
  const before = calls;
  await expect.poll(() => calls).toBeGreaterThan(before);
  await expect(page.locator("[data-change-active]")).toHaveCount(0);
  await page.getByRole("button", { name: "Load more activity" }).click();
  await expect(page.getByRole("link", { name: /Historical model/ })).toBeVisible();
  await expect(row.locator("time")).toHaveAttribute("datetime", "2026-09-13T05:01:00Z");
  await expect(page.locator("[data-change-active]")).toHaveCount(0);
  await page.getByRole("button", { name: "Mine", exact: true }).click();
  await expect(page.getByRole("link", { name: /My model/ })).toBeVisible();
  await expect(page.locator("[data-change-active]")).toHaveCount(0);
  // Choosing a scope never moves the tabs, and View all activity carries only the scope.
  const agentsTab = page.getByRole("button", { name: "Agents", exact: true });
  const tabLeft = (await agentsTab.boundingBox())!.x;
  await agentsTab.click();
  await expect(agentsTab).toHaveAttribute("aria-pressed", "true");
  expect((await agentsTab.boundingBox())!.x).toBe(tabLeft);
  await expect(page.getByRole("link", { name: "View all activity" })).toHaveAttribute("href", `/workspaces/${workspaceId}/activity?scope=agents`);
  const agentModel = page.getByRole("link", { name: /Agent model.*via agent/ });
  await expect(agentModel.locator('[data-agent-badge="neutral"]')).toBeVisible();
  await expect(agentModel.locator(".ov-person-name .ov-meta")).toHaveText("agent");
  const claudeModel = page.getByRole("link", { name: /Claude model.*via Claude \(claude\.ai\)/ });
  await expect(claudeModel.locator('[data-agent-badge="claude"]')).toBeVisible();
  // A known app shows only its logo badge; no app line under the name.
  await expect(claudeModel.locator(".ov-person-name .ov-meta")).toHaveCount(0);
  await expect(page.locator("[data-change-active]")).toHaveCount(0);
  // Switching between cached scopes shows another list, not new activity.
  const recentSection = page.locator('section[aria-labelledby="overview-recent-title"]');
  await recentSection.evaluate((element) => {
    element.setAttribute("data-highlight-starts", "0");
    new MutationObserver((records) => {
      const starts = records.filter((record) => (record.target as Element).getAttribute("data-change-active") === "true").length;
      if (starts) element.setAttribute("data-highlight-starts", String(Number(element.getAttribute("data-highlight-starts")) + starts));
    }).observe(element, { subtree: true, attributes: true, attributeFilter: ["data-change-active"] });
  });
  await page.getByRole("button", { name: "Mine", exact: true }).click();
  await expect(page.getByRole("link", { name: /My model/ })).toBeVisible();
  await agentsTab.click();
  await expect(claudeModel).toBeVisible();
  await expect(recentSection).toHaveAttribute("data-highlight-starts", "0");
  await page.getByRole("button", { name: "All", exact: true }).click();
  await expect(row).toBeVisible();
  await expect(page.locator("[data-change-active]")).toHaveCount(0);
  await page.locator(".ov-timeline").evaluate((element) => {
    const observer = new MutationObserver(() => {
      const added = element.firstElementChild;
      if (!added?.textContent?.includes("New metric model")) return;
      for (const animation of added.getAnimations()) {
        animation.pause();
        animation.currentTime = 0;
      }
      observer.disconnect();
    });
    observer.observe(element, { childList: true });
  });
  const previousTop = (await row.boundingBox())!.y;
  current = [saved(4, "New metric model"), ...current];
  const added = page.locator(`[data-change-id="SEMANTIC_MODEL:${items[4].id}"]`);
  await expect(added).toHaveAttribute("data-change-active", "true", { timeout: 5000 });
  await expect(row).not.toHaveAttribute("data-change-active");
  const entry = await added.evaluate((element) => ({
    height: element.getBoundingClientRect().height,
    duration: element.getAnimations()[0].effect!.getTiming().duration,
    highlightDuration: element.querySelector(".change-highlight-overlay")!.getAnimations()[0].effect!.getTiming().duration,
  }));
  expect(entry.height).toBeLessThan(3);
  expect(entry.duration).toBe(280);
  expect(entry.highlightDuration).toBe(1800);
  await added.evaluate((element) => { element.getAnimations()[0].currentTime = 140; });
  const middleHeight = (await added.boundingBox())!.height;
  expect(middleHeight).toBeGreaterThan(3);
  expect((await row.boundingBox())!.y).toBeGreaterThan(previousTop);
  await added.evaluate((element) => { element.getAnimations()[0].finish(); });
  expect((await added.boundingBox())!.height).toBeGreaterThan(middleHeight);
  await expect(added.locator("svg.lucide-network")).toHaveCount(1);
});

test("Recent activity keeps run settlement quiet and reduces change motion", async ({ page }) => {
  await page.emulateMedia({ reducedMotion: "reduce" });
  let revision = 0;
  let calls = 0;
  await page.route("**/overview/activity?**", (route) => {
    const recent = new URL(route.request().url()).searchParams.get("section") === "recent";
    if (recent) calls++;
    return route.fulfill(json({ items: recent ? [
      { ...items[0], state: revision ? "SUCCEEDED" : "RUNNING", occurredAt: revision ? "2026-09-13T05:01:00Z" : "2026-09-13T05:00:00Z" },
      { ...items[1], kind: "PAGE", state: "UPDATED", title: "Weekly briefing", description: "Page saved", occurredAt: revision > 1 ? "2026-09-13T05:02:00Z" : "2026-09-13T05:00:00Z" },
    ] : [], watched: [], nextCursor: null, unavailableSources: [], observedAt: new Date().toISOString() }));
  });
  await authenticate(page);
  await expect(page.locator(".ov-timeline li")).toHaveCount(2);
  revision = 1;
  const before = calls;
  await expect.poll(() => calls).toBeGreaterThan(before);
  await expect(page.locator(".ov-timeline")).toContainText("Explore · Succeeded");
  await expect(page.locator("[data-change-active]")).toHaveCount(0);
  const row = page.locator(`[data-change-id="PAGE:${items[1].id}"]`);
  await row.evaluate((element) => {
    const observer = new MutationObserver(() => {
      if (!element.hasAttribute("data-change-active")) return;
      for (const animation of element.querySelector(".change-highlight-overlay")!.getAnimations()) animation.pause();
      observer.disconnect();
    });
    observer.observe(element, { attributes: true, attributeFilter: ["data-change-active"] });
  });
  revision = 2;
  await expect(row).toHaveAttribute("data-change-active", "true", { timeout: 5000 });
  const frames = await row.locator(".change-highlight-overlay").evaluate((element) => (element.getAnimations()[0].effect as KeyframeEffect).getKeyframes());
  expect(frames.map((frame) => Number(frame.opacity))).toEqual([1, 1]);
  await row.locator(".change-highlight-overlay").evaluate((element) => { for (const animation of element.getAnimations()) animation.finish(); });
  await expect(row).not.toHaveAttribute("data-change-active");
});

test("Recent activity resumes with one fresh snapshot highlight, without replaying hidden changes", async ({ page }) => {
  let title = "Initial model";
  let reads = 0;
  let delayRead = false;
  let waiting = false;
  let release: () => void = () => undefined;
  const delayed = new Promise<void>((resolve) => { release = resolve; });
  await page.route("**/overview/activity?**", async (route) => {
    const recent = new URL(route.request().url()).searchParams.get("section") === "recent";
    if (recent) {
      reads++;
      if (delayRead) { waiting = true; await delayed; }
    }
    return route.fulfill(json({ items: recent ? [{ ...items[0], kind: "SEMANTIC_MODEL", state: "UPDATED", title, description: "Semantic Model saved" }] : [], watched: [], nextCursor: null, unavailableSources: [], observedAt: new Date().toISOString() }));
  });
  await authenticate(page);
  const row = page.locator(`[data-change-id="SEMANTIC_MODEL:${items[0].id}"]`);
  await expect(row).toContainText("Initial model");
  await row.evaluate((element) => {
    element.setAttribute("data-highlight-starts", "0");
    new MutationObserver(() => {
      if (element.getAttribute("data-change-active") === "true") element.setAttribute("data-highlight-starts", String(Number(element.getAttribute("data-highlight-starts")) + 1));
    }).observe(element, { attributes: true, attributeFilter: ["data-change-active"] });
  });
  await page.evaluate(() => {
    Object.defineProperty(document, "hidden", { configurable: true, value: true });
    Object.defineProperty(document, "visibilityState", { configurable: true, value: "hidden" });
    document.dispatchEvent(new Event("visibilitychange"));
  });
  title = "Intermediate hidden change";
  title = "Latest saved model";
  delayRead = true;
  await page.evaluate(() => {
    Object.defineProperty(document, "hidden", { configurable: true, value: false });
    Object.defineProperty(document, "visibilityState", { configurable: true, value: "visible" });
    document.dispatchEvent(new Event("visibilitychange"));
  });
  await expect.poll(() => waiting).toBe(true);
  await expect(row).toContainText("Initial model");
  await expect(row).not.toHaveAttribute("data-change-active");
  await expect(row).toHaveAttribute("data-highlight-starts", "0");
  delayRead = false;
  release();
  await expect(row).toContainText("Latest saved model");
  await expect(row).toHaveAttribute("data-highlight-starts", "1");
  await expect(page.getByText("Intermediate hidden change")).toHaveCount(0);
  await expect(row).not.toHaveAttribute("data-change-active");
  const before = reads;
  await expect.poll(() => reads).toBeGreaterThan(before);
  await expect(row).toHaveAttribute("data-highlight-starts", "1");
});

test.beforeAll(() => mkdirSync(evidencePath("screenshots"), { recursive: true }));
test("production Overview renders bounded runs, native links, filters, and responsive dialog", async ({ page }) => {
  let terminal = false;
  let denied = false;
  await page.route("**/overview/activity?**", (route) => {
    const params = new URL(route.request().url()).searchParams;
    const live = params.get("section") === "live";
    const visible = items.filter((_, index) => !(index === 0 && (terminal || denied)));
    const watched = (params.get("watch") ?? "").split(",").flatMap((key) => {
      const item = items.find((item) => key === `${item.kind}:${item.id}`);
      return !item || (item.id === items[0].id && denied) ? [] : [{ ...item, state: item.id === items[0].id && terminal ? "SUCCEEDED" : "RUNNING" }];
    });
    return route.fulfill(json({ items: live ? visible : [{ ...items[0], title: "Weekly revenue briefing", kind: "PAGE", state: "UPDATED", description: "Page saved · Version 3", href: `/workspaces/${workspaceId}/pages/${items[0].id}` }], watched, nextCursor: null, unavailableSources: [], observedAt: new Date().toISOString() }));
  });
  await page.setViewportSize({ width: 1440, height: 1000 });
  await authenticate(page);
  const main = page.getByRole("region", { name: "Workspace overview" });
  const live = main.getByRole("region", { name: "Live activity" });
  await expect(live).toContainText("14 running");
  // The board shows a bounded number of rows and leaves the rest to View all runs.
  await expect(live.locator(".ov-run-list li")).toHaveCount(5);
  await expect(live).toContainText("Showing 5 of 14");
  await expect(live.locator(".ov-run-list li").first().getByRole("link", { name: /via Claude \(claude\.ai\)/ })).toBeVisible();
  await expect(live.locator(".ov-run-list li").first().locator('[data-agent-badge="claude"]')).toBeVisible();
  await expect(live.locator(".ov-run-list li").first().getByRole("link")).toHaveAttribute("href", items[0].href);
  // Running work comes before the checklist and the activity list.
  await expect(page.locator(".ov-main h2")).toHaveText(["Live activity", "Get started", "Recent activity"]);
  await expect(main.getByRole("link", { name: /Weekly revenue briefing/ })).toHaveAttribute("href", `/workspaces/${workspaceId}/pages/${items[0].id}`);
  await page.screenshot({ animations: "disabled", path: evidencePath("screenshots/overview-production-desktop.png"), fullPage: true });
  await page.getByRole("button", { name: "View all runs" }).click();
  const dialog = page.getByRole("dialog", { name: "All observed runs" });
  await expect(dialog.locator(".ov-run-list li")).toHaveCount(10);
  await dialog.getByRole("button", { name: "Next", exact: true }).click();
  await expect(dialog.locator(".ov-run-list li")).toHaveCount(4);
  await dialog.getByLabel("Responsible person").selectOption(principalId);
  await expect(dialog.locator(".ov-run-list li")).toHaveCount(7);
  await dialog.getByLabel("Search runs").fill("SQL query 1");
  await expect(dialog.locator(".ov-run-list li")).toHaveCount(3);
  await page.screenshot({ animations: "disabled", path: evidencePath("screenshots/overview-production-dialog.png"), fullPage: true });
  await dialog.getByLabel("Search runs").fill("");
  await dialog.getByLabel("Responsible person").selectOption("all");
  terminal = true;
  await expect(dialog.locator(".ov-run-list li").first()).toContainText("Succeeded", { timeout: 10000 });
  await expect(dialog.locator(".ov-run-list li").first().locator('[data-work-status="SUCCEEDED"]')).toHaveClass(/text-success-text/);
  await page.keyboard.press("Escape");
  // The modal dialog hides the page from the accessibility tree, so the board is read after it closes.
  await expect(live).toContainText("13 running · 1 finished");
  await expect(live.locator(".ov-run-list li").first()).toContainText("Succeeded");
  const runningIcon = live.locator('[data-work-status="RUNNING"]').first();
  await expect(runningIcon).toHaveClass(/lucide-loader-circle/);
  await expect(runningIcon).toHaveClass(/text-foreground-secondary/);
  await expect(runningIcon).toHaveCSS("animation-name", "spin");
  denied = true;
  await page.getByRole("button", { name: "View all runs" }).click();
  await expect(dialog.locator(`[data-run-id="EXPLORE:${items[0].id}"]`)).toHaveCount(0, { timeout: 10000 });
  await page.keyboard.press("Escape");
  await page.setViewportSize({ width: 390, height: 844 });
  await page.emulateMedia({ reducedMotion: "reduce" });
  await expect(live.locator('[data-work-status="RUNNING"]').first()).toHaveCSS("animation-name", "none");
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
  await page.screenshot({ animations: "disabled", path: evidencePath("screenshots/overview-production-mobile.png"), fullPage: true });
});

test("the Live board appears only while work runs and stays while the reader points at it", async ({ page }) => {
  let finished = false;
  await page.route("**/overview/activity?**", (route) => {
    const params = new URL(route.request().url()).searchParams;
    const live = params.get("section") === "live";
    const watched = (params.get("watch") ?? "").includes(items[0].id) ? [{ ...items[0], state: finished ? "SUCCEEDED" : "RUNNING" }] : [];
    return route.fulfill(json({ items: live && !finished ? [items[0]] : [], watched: live ? watched : [], nextCursor: null, unavailableSources: [], observedAt: new Date().toISOString() }));
  });
  await page.setViewportSize({ width: 1280, height: 900 });
  await authenticate(page);
  const board = page.getByRole("region", { name: "Live activity" });
  await expect(board).toContainText("1 running");
  await expect(board.locator(".ov-run-list li")).toHaveCount(1);
  await expect(board).not.toContainText("Showing");
  await board.locator(".ov-run-list li").hover();
  finished = true;
  // The finished row keeps its place while the pointer is on it, so nothing moves under the reader.
  await expect(board.locator(".ov-run-list li")).toContainText("Succeeded", { timeout: 10000 });
  await expect(board).toContainText("1 finished");
  await page.mouse.move(0, 0);
  await expect(board).toHaveCount(0);
  await expect(page.locator(".ov-main h2")).toHaveText(["Get started", "Recent activity"]);
});

test("the Live board leaves once its runs dialog closes and nothing runs", async ({ page }) => {
  let finished = false;
  await page.route("**/overview/activity?**", (route) => {
    const params = new URL(route.request().url()).searchParams;
    const live = params.get("section") === "live";
    const watched = (params.get("watch") ?? "").includes(items[0].id) ? [{ ...items[0], state: finished ? "SUCCEEDED" : "RUNNING" }] : [];
    return route.fulfill(json({ items: live && !finished ? [items[0]] : [], watched: live ? watched : [], nextCursor: null, unavailableSources: [], observedAt: new Date().toISOString() }));
  });
  await page.setViewportSize({ width: 1280, height: 900 });
  await authenticate(page);
  const board = page.getByRole("region", { name: "Live activity" });
  await expect(board).toContainText("1 running");
  await board.getByRole("button", { name: "View all runs" }).click();
  finished = true;
  await expect(page.getByRole("dialog").locator(".ov-run-list li")).toContainText("Succeeded", { timeout: 10000 });
  await page.getByRole("button", { name: "Close runs" }).click();
  await page.mouse.move(0, 0);
  await page.getByRole("heading", { name: "Overview", level: 1 }).click();
  await expect(board).toHaveCount(0, { timeout: 70000 });
});

test("empty authorized scan pages stay pageable and source failures preserve honest states", async ({ page }) => {
  let loaded = false;
  await page.route("**/overview/activity?**", (route) => {
    const params = new URL(route.request().url()).searchParams;
    if (params.get("section") === "recent") return route.fulfill(json({ items: [], watched: [], nextCursor: params.get("cursor") ? null : "recent-next", unavailableSources: [], observedAt: new Date().toISOString() }));
    if (params.get("cursor")) loaded = true;
    return route.fulfill(json({ items: loaded ? [items[0]] : [], watched: [], nextCursor: loaded ? null : "next", unavailableSources: [], observedAt: new Date().toISOString() }));
  });
  await authenticate(page);
  await page.getByRole("button", { name: "View all runs" }).click();
  await page.getByRole("button", { name: "Load more runs" }).click();
  await expect(page.getByRole("dialog").locator(".ov-run-list li")).toHaveCount(1);
  await page.keyboard.press("Escape");
  await page.getByRole("button", { name: "Load more activity" }).click();
  await expect(page.getByRole("button", { name: "Load more activity" })).toHaveCount(0);
  await page.context().setOffline(true);
  await expect(page.getByText("Disconnected. Last observed states are shown; current run states are unknown.")).toBeVisible();
  await expect(page.getByRole("region", { name: "Live activity" })).toContainText("1 last seen running");
  const lastSeenMarks = page.locator('[data-work-status="LAST_SEEN_RUNNING"]');
  await expect(lastSeenMarks).toHaveCount(2);
  for (const mark of await lastSeenMarks.all()) await expect(mark).toHaveCSS("animation-name", "none");
  await expect(page.locator('[data-work-status="RUNNING"]')).toHaveCount(0);
  await page.screenshot({ animations: "disabled", path: evidencePath("screenshots/overview-production-offline.png"), fullPage: true });
});

test("partial sources and recent errors recover to an honest empty state", async ({ page, consoleErrors }) => {
  consoleErrors.allow({ status: 503, url: /\/overview\/activity\?section=recent/, reason: "The fixture fails the recent activity read." });
  const recovered = { live: false, recent: false };
  let heldResponses: Promise<void> | undefined;
  await page.route("**/overview/activity?**", async (route) => {
    if (heldResponses) await heldResponses;
    const live = new URL(route.request().url()).searchParams.get("section") === "live";
    if (!live && !recovered.recent) return route.fulfill({ status: 503, contentType: "application/problem+json", body: JSON.stringify({ type: "about:blank", title: "Unavailable", status: 503 }) });
    return route.fulfill(json({ items: [], watched: [], nextCursor: null, unavailableSources: live && !recovered.live ? ["EXPLORE"] : [], observedAt: new Date().toISOString() }));
  });
  // Both sections poll while the page is visible. Hold responses while clicking so a poll cannot recover
  // the section and remove its Retry button before the click lands.
  async function retry(section: keyof typeof recovered, notice: string) {
    let release!: () => void;
    heldResponses = new Promise((resolve) => { release = resolve; });
    recovered[section] = true;
    await page.getByRole("status").filter({ hasText: notice }).getByRole("button", { name: "Retry", exact: true }).click();
    release();
  }
  await authenticate(page);
  await expect(page.getByText("Loading recent activity…")).toBeVisible();
  await expect(page.getByText("Recent activity could not be refreshed.", { exact: false })).toBeVisible({ timeout: 15000 });
  await expect(page.getByText("Some activity sources are unavailable.", { exact: false })).toBeVisible();
  await page.screenshot({ path: evidencePath("screenshots/overview-production-error.png"), fullPage: true });
  await retry("recent", "Recent activity could not be refreshed.");
  await expect(page.getByText("No recent activity", { exact: true })).toBeVisible();
  await retry("live", "Some activity sources are unavailable.");
  await expect(page.getByText("Some activity sources are unavailable.", { exact: false })).toHaveCount(0);
  await expect(page.getByRole("region", { name: "Live activity" })).toHaveCount(0);
  await page.screenshot({ path: evidencePath("screenshots/overview-production-empty.png"), fullPage: true });
});


test("expired pagination cursors recover through a fresh first page", async ({ page, consoleErrors }) => {
  consoleErrors.allow({ status: 400, url: /\/overview\/activity\?.*cursor=expired-process-cursor/, reason: "The fixture rejects the expired pagination cursor." });
  let firstPages = 0;
  let rejectedCursor = false;
  await page.route("**/overview/activity?**", (route) => {
    const params = new URL(route.request().url()).searchParams;
    if (params.get("section") === "live") return route.fulfill(json({ items: [], watched: [], nextCursor: null, unavailableSources: [], observedAt: new Date().toISOString() }));
    if (params.get("cursor")) {
      rejectedCursor = true;
      return route.fulfill({ status: 400, contentType: "application/problem+json", body: JSON.stringify({ title: "Activity cursor is no longer valid; reload activity.", status: 400 }) });
    }
    firstPages += 1;
    return route.fulfill(json({ items: rejectedCursor ? [{ ...items[0], title: "Recovered activity" }] : [], watched: [], nextCursor: rejectedCursor ? null : "expired-process-cursor", unavailableSources: [], observedAt: new Date().toISOString() }));
  });
  await authenticate(page);
  await page.getByRole("button", { name: "Load more activity" }).click();
  await expect(page.getByText("Recent activity could not be refreshed.", { exact: false })).toBeVisible({ timeout: 15000 });
  const beforeRetry = firstPages;
  await page.getByRole("button", { name: "Retry", exact: true }).click();
  await expect(page.getByRole("link", { name: /Recovered activity/ })).toBeVisible();
  expect(firstPages).toBeGreaterThan(beforeRetry);
  await expect(page.getByRole("button", { name: "Load more activity" })).toHaveCount(0);
});

const semanticModelId = "019e8000-0000-7000-8000-000000000c01";
const metricId = "019e8000-0000-7000-8000-000000000c02";
const semanticModel = {
  semanticModelId, workspaceId, createdAt: "2026-09-13T00:00:00Z", updatedAt: "2026-09-13T00:01:00Z",
  definition: { schemaVersion: 3, name: "orders", label: "Orders", description: "Current order definition", sourceRelations: [],
    entities: [{ name: "order_id", type: "primary", expression: "id" }], dimensions: [], measures: [{ name: "revenue", aggregation: "sum", expression: "net_amount" }] },
};
const semanticMetric = {
  metricId, workspaceId, createdAt: "2026-09-13T00:00:00Z", updatedAt: "2026-09-13T00:01:00Z",
  definition: { schemaVersion: 3, name: "net_revenue", label: "Net Revenue", description: "Current revenue definition", type: "simple", semanticModelName: "orders", measure: "revenue" },
};
const semanticChanges = (updated: boolean) => [
  { ...items[0], id: semanticModelId, kind: "SEMANTIC_MODEL", title: "Orders", state: "UPDATED", startedAt: null,
    occurredAt: updated ? "2026-09-13T00:01:00Z" : "2026-09-13T00:00:00Z", description: "Semantic Model saved · orders", href: `/workspaces/${workspaceId}/semantic-models/${semanticModelId}` },
  { ...items[0], id: metricId, kind: "METRIC", title: "Net Revenue", state: "UPDATED", startedAt: null,
    occurredAt: updated ? "2026-09-13T00:01:00Z" : "2026-09-13T00:00:00Z", description: "Metric saved · net_revenue", href: `/workspaces/${workspaceId}/metrics/${metricId}` },
];

test("an empty Overview polls slowly, then discovers semantic updates on the 2 second poll and opens current definitions", async ({ page }) => {
  let phase = 0;
  const activityRequests: string[] = [];
  const writes: string[] = [];
  const semanticPath = `/v2/workspaces/${workspaceId}/semantic`;
  const checkResult = { partitionFilters: [], ok: false, runsOn: [], preview: null, problems: [] } satisfies components["schemas"]["SemanticCheckView"];
  page.on("request", (request) => {
    const path = new URL(request.url()).pathname;
    const isCheckRead = request.method() === "POST" && path === `${semanticPath}/check`;
    const isGraphqlMutation = request.method() === "POST" && path === "/v2/graphql" && /\bmutation\s/.test((request.postDataJSON() as { query: string }).query);
    if (isGraphqlMutation || (path.startsWith(`/v2/workspaces/${workspaceId}/`) && request.method() !== "GET" && !isCheckRead)) writes.push(request.url());
  });
  await page.route("**/v2/workspaces/*/connectors", (route) => route.fulfill(json({ connectors: [] })));
  await page.route("**/v2/workspaces/*/semantic/**", (route) => {
    const path = new URL(route.request().url()).pathname;
    if (path === `${semanticPath}/check` && route.request().method() === "POST") return route.fulfill(json(checkResult));
    if (path.endsWith(`/semantic-models/${semanticModelId}`)) return route.fulfill(json(semanticModel));
    if (path.endsWith(`/metrics/${metricId}`)) return route.fulfill(json(semanticMetric));
    return route.fulfill(json(path.endsWith("/semantic-models") ? [semanticModel] : [semanticMetric]));
  });
  await page.route("**/overview/activity?**", (route) => {
    const params = new URL(route.request().url()).searchParams;
    activityRequests.push(params.get("section")!);
    return route.fulfill(json({ items: params.get("section") === "recent" && phase ? semanticChanges(phase === 2) : [], watched: [], nextCursor: null, unavailableSources: [], observedAt: new Date().toISOString() }));
  });
  await page.setViewportSize({ width: 1280, height: 1080 });
  await authenticate(page);
  await mockSemanticLineage(page);
  await expect(page.getByText("No recent activity", { exact: true })).toBeVisible();
  const initialRequests = activityRequests.length;
  phase = 1;
  const createdAt = Date.now();
  // An empty workspace polls every 15 seconds; activity returns it to the 2 second poll.
  await expect(page.getByRole("link", { name: /Semantic Model saved/ })).toBeVisible({ timeout: 17_000 });
  await expect(page.getByRole("link", { name: /Metric saved/ })).toBeVisible();
  expect(activityRequests).toHaveLength(initialRequests + 2);
  const createArrivalMs = Date.now() - createdAt;
  phase = 2;
  const updatedAt = Date.now();
  await expect(page.getByRole("link", { name: /Semantic Model saved/ }).locator("time")).toHaveAttribute("datetime", "2026-09-13T00:01:00Z", { timeout: 3_000 });
  await expect(page.getByRole("link", { name: /Metric saved/ }).locator("time")).toHaveAttribute("datetime", "2026-09-13T00:01:00Z");
  await expect(page.getByRole("link", { name: /Semantic Model saved/ })).toBeVisible({ timeout: 3_000 });
  await expect(page.getByRole("link", { name: /Metric saved/ })).toBeVisible();
  const updateArrivalMs = Date.now() - updatedAt;
  const latencyEvidence = JSON.stringify({ fixture: "controlled API response change to visible row; no actual Codex mutation", createArrivalMs, updateArrivalMs });
  writeFileSync(evidencePath("screenshots/overview-semantic-latency.json"), latencyEvidence);
  await test.info().attach("semantic-arrival-latency", { body: latencyEvidence, contentType: "application/json" });
  await expect(page.locator(".ov-timeline li")).toHaveCount(2);
  await page.screenshot({ path: evidencePath("screenshots/overview-semantic-arrival.png"), fullPage: true });
  await page.setViewportSize({ width: 390, height: 844 });
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
  await page.screenshot({ path: evidencePath("screenshots/overview-semantic-mobile.png"), fullPage: true });
  await page.setViewportSize({ width: 1280, height: 1080 });
  await page.getByRole("link", { name: /Semantic Model saved/ }).click();
  await expect(page).toHaveURL(new RegExp(`/semantic-models/${semanticModelId}`));
  await expect(page.getByRole("heading", { name: "Orders", exact: true })).toBeVisible();
  await expect(page.getByText("Current order definition")).toBeVisible();
  await expect(page.getByRole("complementary", { name: "Usage and status" }).getByRole("link", { name: "Open map" })).toHaveAttribute("href", `/workspaces/${workspaceId}/lineage?kind=SEMANTIC_MODEL&id=${semanticModelId}`);
  await page.screenshot({ path: evidencePath("screenshots/overview-semantic-model-definition.png"), fullPage: true });
  await page.goBack();
  await page.getByRole("link", { name: /Metric saved/ }).click();
  await expect(page).toHaveURL(new RegExp(`/metrics/${metricId}`));
  await expect(page.getByRole("heading", { name: "Net Revenue", exact: true })).toBeVisible();
  await expect(page.getByText("Current revenue definition")).toBeVisible();
  await expect(page.getByRole("heading", { name: "How it's calculated", exact: true })).toBeVisible();
  await expect(page.getByRole("complementary", { name: "Calculation path" }).getByRole("link", { name: "Open map" })).toHaveAttribute("href", `/workspaces/${workspaceId}/lineage?kind=METRIC&id=${metricId}`);
  await expect(page.getByRole("region", { name: "Status", exact: true }).getByText("No executable connector", { exact: true })).toBeVisible();
  await page.screenshot({ path: evidencePath("screenshots/overview-semantic-metric-definition.png"), fullPage: true });
  expect(writes).toEqual([]);
});

test("Overview suspends both polling queries while hidden or offline and resumes without reload", async ({ page }) => {
  await page.clock.install();
  let requests = 0;
  await page.route("**/overview/activity?**", (route) => {
    requests++;
    return route.fulfill(json({ items: [], watched: [], nextCursor: null, unavailableSources: [], observedAt: new Date().toISOString() }));
  });
  await authenticate(page, { owners: [{ userId: "019e8000-0000-7000-8000-000000000b03", displayName: "Mina Park" }] });
  await expect(page.getByText("No recent activity", { exact: true })).toBeVisible();
  const start = page.getByRole("region", { name: "Get started" });
  await expect(start).toContainText("0 of 6");
  await expect(start.locator("[data-step]")).toHaveCount(6);
  await expect(start.locator('[aria-current="step"]')).toHaveAttribute("data-step", "connect");
  await expect(start.getByText("Ask Mina Park", { exact: true })).toBeVisible();
  await expect(start.getByRole("link")).toHaveCount(0);
  await expect(page.getByRole("region", { name: "Needs attention" })).toHaveCount(0);
  await expect(page.getByRole("region", { name: "Live activity" })).toHaveCount(0);
  await expect(page.getByTestId("workspace-page-header")).toContainText("Overview");
  await expect(page.getByTestId("workspace-page-header")).not.toContainText("Commerce Insights");
  const setVisibility = (hidden: boolean) => page.evaluate((hidden) => {
    Object.defineProperty(document, "visibilityState", { configurable: true, get: () => hidden ? "hidden" : "visible" });
    document.dispatchEvent(new Event("visibilitychange"));
  }, hidden);
  const initialRequests = requests;
  await setVisibility(true);
  await page.clock.fastForward(4_500);
  expect(requests).toBe(initialRequests);
  await setVisibility(false);
  // Becoming visible reads one fresh snapshot per section at once, not after the next idle poll.
  await expect.poll(() => requests).toBe(initialRequests + 2);
  await page.context().setOffline(true);
  await expect(page.getByText("Disconnected.", { exact: false })).toBeVisible();
  await page.clock.fastForward(4_500);
  expect(requests).toBe(initialRequests + 2);
  await page.context().setOffline(false);
  await expect.poll(() => requests).toBe(initialRequests + 4);
  // The workspace is empty, so the next poll waits 15 seconds rather than 2.
  await page.clock.fastForward(4_500);
  expect(requests).toBe(initialRequests + 4);
});

test("poll request cost includes loaded pages and every 100 observed runs", async ({ page }) => {
  const runs = Array.from({ length: 101 }, (_, index) => ({ ...items[0], id: `019e8000-0000-7000-8000-${String(index + 1).padStart(12, "0")}`, title: `Run ${index + 1}` }));
  const requests: Array<{ section: string; cursor: string | null; watches: number }> = [];
  await page.route("**/overview/activity?**", (route) => {
    const params = new URL(route.request().url()).searchParams;
    const section = params.get("section")!;
    const cursor = params.get("cursor");
    const checked = (params.get("watch") ?? "").split(",").filter(Boolean);
    requests.push({ section, cursor, watches: checked.length });
    const offset = Number(cursor ?? 0);
    const selected = section === "live" ? runs.slice(offset, offset + 50) : semanticChanges(false).slice(offset, offset + 1);
    const next = section === "live" ? offset + 50 < runs.length ? String(offset + 50) : null : offset === 0 ? "1" : null;
    return route.fulfill(json({ items: selected, watched: runs.filter((run) => checked.includes(`EXPLORE:${run.id}`)), nextCursor: next, unavailableSources: [], observedAt: new Date().toISOString() }));
  });
  const frozen = new Date("2026-09-13T00:02:00Z");
  await page.clock.install({ time: frozen });
  await authenticate(page);
  await expect(page.getByRole("button", { name: "View all runs" })).toBeVisible();
  await page.clock.runFor(400);
  await page.getByRole("button", { name: "View all runs" }).click();
  await page.getByRole("button", { name: "Load more runs" }).click();
  await page.clock.runFor(400);
  await expect(page.getByText("100 matching runs", { exact: false })).toBeVisible();
  await page.getByRole("button", { name: "Load more runs" }).click();
  await page.clock.runFor(400);
  await expect(page.getByText("101 matching runs", { exact: false })).toBeVisible();
  await page.keyboard.press("Escape");
  await page.getByRole("button", { name: "Load more activity" }).click();
  await expect(page.locator(".ov-timeline li")).toHaveCount(2);
  await page.clock.pauseAt(new Date(await page.evaluate(() => Date.now()) + 100));
  requests.length = 0;
  await page.clock.runFor(2_000);
  await expect.poll(() => requests.length).toBe(6);
  expect(requests.filter((request) => request.section === "live").map((request) => request.watches)).toEqual([100, 1, 0, 0]);
  expect(requests.filter((request) => request.section === "recent").map((request) => request.cursor)).toEqual([null, "1"]);
});

const connectorId = "019e8000-0000-7000-8000-000000000d01";
const dashboardId = "019e8000-0000-7000-8000-000000000d02";
const failedRunId = "019e8000-0000-7000-8000-000000000d03";
const sessionId = "019e8000-0000-7000-8000-000000000d04";
const recentRun = (runId: string, state: string, minutesAgo: number, extra: Record<string, unknown> = {}) => ({
  runId, kind: "SEMANTIC", workspaceId, connectorId, requestDigest: `sha256:${"a".repeat(64)}`, state, revision: 1, resultAvailable: state === "SUCCEEDED",
  failureCode: state === "FAILED" ? "PERMISSION_DENIED" : null, failureDetail: null,
  createdAt: new Date(Date.now() - minutesAgo * 60_000).toISOString(), updatedAt: new Date(Date.now() - minutesAgo * 60_000).toISOString(),
  resultMetadata: state === "SUCCEEDED" ? { byteSize: "10", mediaType: "application/vnd.apache.arrow.file", rowCount: "24", columnCount: 2, expiresAt: "2099-01-01T00:00:00Z" } : null,
  sql: "SELECT 1", credentialSurface: "WEB",
  query: { schemaVersion: 4, metrics: ["revenue"], groupBys: [{ kind: "METRIC_TIME", granularity: "MONTH" }], metricTimeRange: null, filters: [], orderBy: [], rowLimit: 100 },
  ...extra,
});
const failingSummary = { connectorId, selectedCatalogCount: 1, selectedSchemaCount: 1, tableCount: 142, lastSuccessfulRefreshAt: null,
  lastRefresh: { jobId: "019e8000-0000-7000-8000-000000000d05", state: "COMPLETED_WITH_ERRORS", catalogName: null, requestedAt: "2026-10-01T18:00:00Z", finishedAt: "2026-10-01T18:40:00Z", errorCount: 1, entryFailureCode: "AUTHENTICATION_FAILED" } };
const draftDashboard = { dashboardId, workspaceId, title: "Pipeline health", description: null, updatedAt: "2026-10-01T09:00:00Z", updatedBy: null, credentialSurface: null,
  latestResultsAt: "2026-10-01T09:02:00Z", schedules: [], draftHasChanges: true, pagePlacementCount: 0,
  draftLastEditedBy: { id: "019e8000-0000-7000-8000-000000000e11", displayName: "Sam Kim" }, draftLastEditedAt: "2026-10-01T09:30:00Z", draftLastEditSurface: "AGENT" };
const warehouse = { id: connectorId, workspaceId, name: "Snowflake — Finance", type: "SNOWFLAKE", properties: {}, secretSet: true, createdAtEpochMs: 1, updatedAtEpochMs: 1 };
const quietActivity = (recentItems: unknown[] = [], liveItems: unknown[] = []) => async (route: Parameters<Parameters<Page["route"]>[1]>[0]) => {
  const recent = new URL(route.request().url()).searchParams.get("section") === "recent";
  await route.fulfill(json({ items: recent ? recentItems : liveItems, watched: [], nextCursor: null, unavailableSources: [], observedAt: new Date().toISOString() }));
};

test("Needs attention comes from current state, routes to the fix, and running work follows it", async ({ page }) => {
  await mockOverviewSources(page, workspaceId, {
    connectors: [warehouse], connectorSummaries: [failingSummary], dashboards: [draftDashboard],
    exploreRuns: [recentRun(failedRunId, "FAILED", 30), recentRun("019e8000-0000-7000-8000-000000000d06", "SUCCEEDED", 60)],
    metrics: [{ sourcePartitionInfo: [], metricId: "019e8000-0000-7000-8000-000000000d07", workspaceId, createdAt: "2026-10-01T00:00:00Z", updatedAt: "2026-10-01T00:00:00Z",
      definition: { schemaVersion: 3, name: "revenue", label: "Revenue", type: "simple", semanticModelName: "billing", measure: "amount" } }],
  });
  await page.route("**/overview/activity?**", quietActivity([], [items[1]]));
  await page.setViewportSize({ width: 1280, height: 900 });
  await authenticate(page, { role: "OWNER" });
  const attention = page.getByRole("region", { name: "Needs attention" });
  await expect(attention).toContainText("Snowflake — Finance couldn’t refresh · Sign-in rejected");
  await expect(attention.getByRole("link", { name: "Fix connector" })).toHaveAttribute("href", `/workspaces/${workspaceId}/connectors?connector=${connectorId}`);
  await expect(attention).toContainText("Pipeline health has draft changes waiting to be applied");
  await expect(attention).toContainText("Sam Kim");
  await expect(attention.getByText("via agent", { exact: true })).toBeVisible();
  await expect(attention.getByRole("link", { name: "Review draft changes to Pipeline health" })).toHaveAttribute("href", `/workspaces/${workspaceId}/dashboards/${dashboardId}/builder`);
  await expect(attention).toContainText("Revenue by Month failed · Access denied");
  await expect(attention.getByRole("link", { name: "Open in Explore" })).toHaveAttribute("href", `/workspaces/${workspaceId}/explore?run=${failedRunId}`);
  // Recent activity's Mine filter reopens recent questions and dashboard changes, so there is no separate Continue list.
  await expect(page.getByRole("region", { name: "Continue" })).toHaveCount(0);
  const live = page.getByRole("region", { name: "Live activity" });
  await expect(live).toContainText("1 running");
  await expect(live.getByRole("link", { name: /SQL query 2/ })).toContainText("Mina Park");
  await expect(page.locator(".ov-main h2")).toHaveText(["Needs attention", "Live activity", "Get started", "Recent activity"]);
  await page.screenshot({ path: evidencePath("screenshots/overview-needs-attention.png"), fullPage: true });
});

test("Needs attention drops a failed question once it leaves the 24 hour window", async ({ page }) => {
  const opened = new Date("2026-10-02T12:00:00Z");
  const failedAt = new Date(opened.getTime() - 24 * 3_600_000 + 60_000).toISOString();
  await mockOverviewSources(page, workspaceId, {
    exploreRuns: [recentRun(failedRunId, "FAILED", 0, { createdAt: failedAt, updatedAt: failedAt })],
    metrics: [{ sourcePartitionInfo: [], metricId: "019e8000-0000-7000-8000-000000000d07", workspaceId, createdAt: "2026-10-01T00:00:00Z", updatedAt: "2026-10-01T00:00:00Z",
      definition: { schemaVersion: 3, name: "revenue", label: "Revenue", type: "simple", semanticModelName: "billing", measure: "amount" } }],
  });
  await page.route("**/overview/activity?**", quietActivity());
  await page.clock.install({ time: opened });
  await authenticate(page, { role: "OWNER" });
  await expect(page.getByRole("region", { name: "Needs attention" })).toContainText("Revenue by Month failed · Access denied");
  await page.clock.fastForward(120_000);
  await expect(page.getByText("Revenue by Month failed")).toHaveCount(0);
});

test("Explorers see whom to ask instead of the connector fix", async ({ page }) => {
  await mockOverviewSources(page, workspaceId, { connectors: [warehouse], connectorSummaries: [failingSummary] });
  await page.route("**/overview/activity?**", quietActivity());
  await authenticate(page, { owners: [{ userId: "019e8000-0000-7000-8000-000000000b03", displayName: "Mina Park" }] });
  const attention = page.getByRole("region", { name: "Needs attention" });
  await expect(attention).toContainText("Ask Mina Park");
  await expect(attention.getByRole("link", { name: "Fix connector" })).toHaveCount(0);
});

test("Get started computes its step from state, opens the bridge, offers rename, and hides locally", async ({ page }) => {
  await mockOverviewSources(page, workspaceId, { connectors: [warehouse], connectorSummaries: [{ ...failingSummary, lastRefresh: null }] });
  await page.route("**/overview/activity?**", quietActivity());
  await authenticate(page, { role: "OWNER", workspaceName: "My Workspace" });
  const start = page.getByRole("region", { name: "Get started" });
  await expect(start).toContainText("2 of 6");
  await expect(page.locator(".ov-main h2")).toHaveText(["Get started", "Recent activity"]);
  await expect(start.locator('[data-step="tables"]')).toContainText("142 tables");
  await expect(start.locator('[aria-current="step"]')).toHaveAttribute("data-step", "model");
  await expect(start.getByRole("link", { name: "Open Catalog" })).toHaveAttribute("href", `/workspaces/${workspaceId}/catalog`);
  await expect(start.getByRole("link")).toHaveCount(2);
  await expect(start).toContainText("“My Workspace”");
  await expect(start.getByRole("link", { name: "Rename" })).toHaveAttribute("href", `/workspaces/${workspaceId}/settings`);
  await page.screenshot({ path: evidencePath("screenshots/overview-get-started.png"), fullPage: true });
  await start.getByRole("button", { name: "Hide" }).click();
  await expect(start).toHaveCount(0);
  await page.reload();
  await expect(page.getByRole("heading", { name: "Recent activity" })).toBeVisible();
  await expect(page.getByRole("region", { name: "Get started" })).toHaveCount(0);
});

test("the agent step is personal, opens Agents, and is done once the viewer has used a connection", async ({ page }) => {
  await mockOverviewSources(page, workspaceId, {
    connectors: [warehouse], connectorSummaries: [{ ...failingSummary, lastRefresh: null }],
    semanticModels: [semanticModel], metrics: [semanticMetric],
    exploreRuns: [recentRun("019e8000-0000-7000-8000-000000000d06", "SUCCEEDED", 60)],
  });
  let agentUsed = false;
  await page.route("**/overview/activity?**", quietActivity());
  const connection = { id: "c1", clientId: "https://claude.ai/oauth/client", clientName: "Claude", resource: "http://localhost/mcp",
    issuedAt: "2026-10-01T00:00:00Z", expiresAt: "2026-10-31T00:00:00Z", revokedAt: null, lastUsedAt: null as string | null };
  await page.route("**/oauth/connections?**", (route) => route.fulfill(json({ connections: [{ ...connection, lastUsedAt: agentUsed ? "2026-10-02T09:00:00Z" : null }], nextCursor: null })));
  await authenticate(page, { owners: [{ userId: "019e8000-0000-7000-8000-000000000b03", displayName: "Mina Park" }] });
  const start = page.getByRole("region", { name: "Get started" });
  await expect(start).toContainText("5 of 6");
  await expect(start.locator('[aria-current="step"]')).toHaveAttribute("data-step", "agent");
  await expect(start.locator('[data-step="agent"]')).toContainText("Connect an agent");
  await expect(start.getByRole("link", { name: "Connect an agent", exact: true })).toHaveAttribute("href", "/settings/agents");
  await expect(start).toContainText("Work with this Workspace from Claude, Claude Code, or Codex.");
  // The agent step is personal: it offers its own action and never sends the person to an owner.
  await expect(start.locator('[data-step="agent"] .ov-step-ask')).toHaveCount(0);
  await expect(start.getByText("Ask Mina Park", { exact: true })).toHaveCount(0);
  await page.screenshot({ path: evidencePath("screenshots/overview-get-started-agent.png"), fullPage: true });
  agentUsed = true;
  await page.reload();
  await expect(page.getByRole("heading", { name: "Recent activity" })).toBeVisible();
  await expect(page.getByRole("region", { name: "Get started" })).toHaveCount(0);
});

test("grouped Dashboard Sessions name their opener and failed rows name a cause", async ({ page }) => {
  const sessionRow = { ...items[0], id: sessionId, kind: "DASHBOARD_SESSION", title: "Revenue overview", description: "Scheduled refresh · 2 of 8 queries failed",
    state: "FAILED", actorId: null, actorName: null, actorKind: "SCHEDULE", credentialSurface: null, failureReason: "Sign-in rejected",
    occurredAt: "2026-10-02T09:02:00Z", href: `/workspaces/${workspaceId}/dashboards/${dashboardId}?session=${sessionId}` };
  const publicRow = { ...sessionRow, id: "019e8000-0000-7000-8000-000000000d08", description: "Opened · 8 queries refreshed", state: "SUCCEEDED", actorKind: "PUBLIC_READER", failureReason: null };
  await page.route("**/overview/activity?**", quietActivity([sessionRow, publicRow]));
  await authenticate(page);
  const scheduled = page.locator(`[data-change-id="DASHBOARD_SESSION:${sessionId}"]`);
  await expect(scheduled).toContainText("Revenue overview");
  await expect(scheduled).toContainText("Scheduled refresh · 2 of 8 queries failed · Sign-in rejected");
  await expect(scheduled).toContainText("Scheduled");
  await expect(scheduled.getByRole("link")).toHaveAttribute("href", sessionRow.href);
  await expect(page.locator(`[data-change-id="DASHBOARD_SESSION:${publicRow.id}"]`)).toContainText("Public reader");
  await expect(page.getByText("Not recorded")).toHaveCount(0);
});

test("Recent activity lines up people and times in fixed columns", async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 900 });
  const ago = (minutes: number) => new Date(Date.now() - minutes * 60_000).toISOString();
  const spoofedClaude = { clientId: "https://claude-helper.app/client.json", name: "Claude", verifiedHost: "claude-helper.app" };
  const row = (index: number, actorName: string, agentApp: typeof claude | null, minutes: number) => ({
    ...items[index], kind: "SEMANTIC_MODEL", state: "UPDATED", title: `Model ${index + 1}`, description: "Semantic Model saved",
    actorName, credentialSurface: agentApp ? "AGENT" : "WEB", agentApp, occurredAt: ago(minutes),
  });
  await page.route("**/overview/activity?**", quietActivity([
    row(0, "Alex Kim", null, 0), row(1, "Alexandra Morgan-Whitfield", null, 3), row(2, "Jisoo Park", claude, 60 * 24 * 23), row(3, "Sam", spoofedClaude, 90),
  ]));
  await authenticate(page);
  const attributions = page.locator(".ov-timeline .ov-event-attribution");
  await expect(attributions).toHaveCount(4);
  // Each row's kind icon sits at the row's vertical center, apart from the two text lines.
  const iconOffsets = await page.locator(".ov-timeline .ov-recent-link").evaluateAll((links) => links.map((link) => {
    const row = link.getBoundingClientRect();
    const icon = link.querySelector(".ov-resource-icon svg")!.getBoundingClientRect();
    return Math.abs((icon.top + icon.height / 2) - (row.top + row.height / 2));
  }));
  for (const offset of iconOffsets) expect(offset).toBeLessThanOrEqual(1);
  const avatarLefts = await page.locator(".ov-timeline .ov-avatar").evaluateAll((marks) => marks.map((mark) => Math.round(mark.getBoundingClientRect().left)));
  expect(new Set(avatarLefts).size).toBe(1);
  const timeRights = await page.locator(".ov-timeline .ov-event-attribution time").evaluateAll((times) => times.map((time) => Math.round(time.getBoundingClientRect().right)));
  expect(new Set(timeRights).size).toBe(1);
  // The logo is chosen by the verified host: an app that only calls itself Claude gets the neutral badge and its host.
  await expect(page.getByRole("link", { name: /Model 3.*via Claude \(claude\.ai\)/ }).locator('[data-agent-badge="claude"]')).toBeVisible();
  const spoofed = page.getByRole("link", { name: /Model 4.*via claude-helper\.app \(Claude\)/ });
  await expect(spoofed.locator('[data-agent-badge="neutral"]')).toBeVisible();
  await expect(spoofed.locator(".ov-person-name .ov-meta")).toHaveText("claude-helper.app");
  // The badge sits outside the avatar corner and clear of the name.
  const clearances = await page.locator(".ov-timeline .ov-person").evaluateAll((people) => people.flatMap((person) => {
    const badge = person.querySelector("[data-agent-badge]");
    const name = person.querySelector(".ov-person-name");
    return badge && name ? [name.getBoundingClientRect().left - badge.getBoundingClientRect().right] : [];
  }));
  expect(clearances).toHaveLength(2);
  for (const clearance of clearances) expect(clearance).toBeGreaterThanOrEqual(4);
  await page.screenshot({ animations: "disabled", path: evidencePath("screenshots/overview-recent-activity-columns.png"), fullPage: true });
});
