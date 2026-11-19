import { readFile } from "node:fs/promises";
import { expect, test, type ConsoleErrors } from "./support/test";
import type { Page } from "@playwright/test";
import AxeBuilder from "@axe-core/playwright";
import { evidencePath } from "./evidence";
import { E2E_ACCESS_TOKEN, json as sessionJson, mockGraphql, mockSessionRefresh, mockSignedOut, type GraphqlOperation } from "./support/session";

const workspace = "019e8000-0000-7000-8000-000000000b01";
const pageId = "019e8000-0000-7000-8000-000000000f01";
const session = "019e8000-0000-7000-8000-000000000e01";
const older = "019e8000-0000-7000-8000-000000000e02";
const run = "019e8000-0000-7000-8000-000000000a01";
const filteredRun = "019e8000-0000-7000-8000-000000000a02";
const time = "2026-09-21T03:00:00Z";
const queryRun = { queryType: "SEMANTIC" as const,
  queryRunId: run, queryName: "revenue", state: "SUCCEEDED", resultAvailable: true,
  failureCode: null, outputColumnNames: ["metric.revenue"], createdAt: time, updatedAt: time,
};
const target = `/workspaces/${workspace}/pages/${pageId}`;
const display = {
  parameters: {
    period: { type: "TEXT", nullable: false, label: "Report period" },
  },
  variables: {
    region: {
      type: "TEXT",
      nullable: false,
      initial: "North",
      label: "Region",
    },
  },
  queryNames: ["revenue"],
  queryTypes: { revenue: "SEMANTIC" },
  widgets: {
    region: { type: "INPUT", variableName: "region" },
    revenue: {
      type: "TABLE",
      queryName: "revenue",
      columns: [{ field: "metric.revenue", header: "Revenue" }],
    },
    chart: {
      type: "CHART",
      queryNames: ["revenue"],
      module: {
        inputs: { parameters: [], variables: [] },
        source: `export default defineChart({ render({ queries }) { return { animation: false, xAxis: { type: "category", data: ["Revenue"] }, yAxis: { type: "value" }, series: [{ type: "bar", zlevel: 0, itemStyle: { color: "#2466c3" }, data: queries.revenue.rows.map(row => Number(row["metric.revenue"])) }, { type: "bar", zlevel: 2, itemStyle: { color: "#a2274c" }, data: queries.revenue.rows.map(row => Number(row["metric.revenue"]) / 2) }] }; } });`,
      },
    },
  },
  layout: {
    sections: [
      {
        id: "input",
        height: { mode: "CONTENT" },
        root: { type: "WIDGET", id: "region-node", widgetId: "region" },
      },
      {
        id: "result",
        height: { mode: "SCALED", scale: 0.7 },
        root: { type: "WIDGET", id: "revenue-node", widgetId: "revenue" },
      },
      {
        id: "chart",
        height: { mode: "SCALED", scale: 1.2 },
        root: { type: "WIDGET", id: "chart-node", widgetId: "chart" },
      },
    ],
  },
};
async function fixture(
  page: Page,
  consoleErrors: ConsoleErrors,
  options: {
    empty?: boolean;
    partial?: boolean;
    document?: boolean;
    denied?: boolean;
    failResolve?: boolean;
    resultFailure?: boolean;
    mode?: "VIEWER" | "PUBLIC";
    authenticated?: boolean;
    graphqlOperations?: Record<string, GraphqlOperation>;
  } = {},
) {
  const calls: {
    url: string;
    method: string;
    body: string | null;
    key?: string;
    authorization?: string;
  }[] = [];
  const state = {
    revoked: false,
    resolveFailures: options.failResolve ? 1 : 0,
  };
  await page.route("**/v2/**", async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    calls.push({
      url: url.pathname + url.search,
      method: request.method(),
      body: request.postData(),
      key: request.headers()["idempotency-key"],
      authorization: request.headers()["authorization"],
    });
    const json = (body: unknown, status = 200) =>
      route.fulfill({
        status,
        contentType: "application/json",
        body: JSON.stringify(body),
      });
    if (url.pathname.endsWith("/pages/access")) return json({ mode: options.mode ?? "PUBLIC" });
    if (!url.pathname.includes("/v2/public/"))
      return json({ title: "Unexpected member API" }, 403);
    if (state.revoked || options.denied)
      return json({ title: "Page unavailable" }, 404);
    if (url.pathname.endsWith("/tree"))
      return json({
        workspaceName: "Test workspace",
        revision: 1,
        pages: [
          {
            pageId,
            kind: options.document ? "DOCUMENT" : "DASHBOARD",
            documentFormat: options.document ? "HTML" : null,
            title: "Weekly revenue",
            displayTitle: "Weekly revenue",
            description: "Revenue by region for the current report period.",
          },
        ],
        placements: [{ pageId, children: [] }],
      });
    if (url.pathname.endsWith("/content"))
      return json({
        title: "Weekly revenue",
        format: "HTML",
        source:
          "<h1>Revenue notes</h1><p>Prepared analysis.</p><script>window.parent.document.title='executed'</script>",
      });
    if (url.pathname.endsWith("/sessions/entry"))
      return json({
        session: options.empty
          ? null
          : {
              sessionId: url.searchParams.get("sessionId") ?? session,
              title: "Revenue",
              display,
              parameterBindings: { period: "September 2026" },
              initialVariables: { region: "North" },
              createdAt: time,
              updatedAt: time,
            },
        initialQueryRuns: options.partial ? {} : { revenue: queryRun },
      });
    if (url.pathname.endsWith("/sessions"))
      return json({
        items: [
          { sessionId: session, createdAt: time, updatedAt: time },
          {
            sessionId: older,
            createdAt: "2026-09-20T03:00:00Z",
            updatedAt: time,
          },
        ],
        nextCursor: null,
      });
    if (url.pathname.endsWith(":resolve")) {
      if (state.resolveFailures-- > 0)
        return json({ title: "Temporary failure" }, 503);
      return json({
        variables: request.postDataJSON().variables,
        queryRuns: { revenue: request.postDataJSON().variables.region === "South" ? filteredRun : run },
      });
    }
    if (url.pathname.endsWith("/query-runs"))
      return json({ items: [queryRun], nextCursor: null });
    if (url.pathname.endsWith("/result") && options.resultFailure)
      return json({ title: "Result unavailable" }, 503);
    if (url.pathname.endsWith("/result"))
      return json({ queryType: "SEMANTIC" as const, reportingTimeZone: null,
        queryRunId: url.pathname.includes(filteredRun) ? filteredRun : run,
        columns: [
          { name: "metric.revenue", logicalType: "DECIMAL", nullable: false },
        ],
        rows: [["42500"]],
      });
    if (url.pathname.endsWith(`/query-runs/${run}`)) return json(queryRun);
    if (url.pathname.endsWith(`/query-runs/${filteredRun}`)) return json({ ...queryRun, queryRunId: filteredRun });
    return json({}, 404);
  });
  if (options.authenticated) await mockSessionRefresh(page);
  else await mockSignedOut(page, consoleErrors);
  await mockGraphql(page, {
    Me: (route) => sessionJson(route, { data: { me: { user: { id: "reader", email: "reader@example.test", displayName: "Reader" }, organizationMemberships: [] } } }),
    AccessibleWorkspaces: (route) => sessionJson(route, { data: { accessibleWorkspaces: [] } }),
    ...options.graphqlOperations,
  });
  return { calls, state };
}

for (const viewport of [{ width: 1440, height: 900 }, { width: 390, height: 844 }]) {
  test(`Workspace Viewer gets the member reader shell with a user menu at ${viewport.width}px`, async ({ page, consoleErrors }) => {
    await page.setViewportSize(viewport);
    await fixture(page, consoleErrors, {
      mode: "VIEWER",
      authenticated: true,
      graphqlOperations: {
        Me: (route) => sessionJson(route, { data: { me: {
          user: { id: "reader", email: "reader@example.test", displayName: "Reader" },
          organizationMemberships: [{ organization: { id: "reader-organization", displayName: "Reader organization", plan: "FREE", createdAt: time }, role: "MEMBER" }],
        } } }),
        AccessibleWorkspaces: (route) => sessionJson(route, { data: { accessibleWorkspaces: [
          { id: workspace, organizationId: "reader-organization", name: "Test workspace", publicPages: false, reportingTimeZone: "UTC", role: "VIEWER" },
        ] } }),
      },
    });
    await page.goto(target);
    await expect(page.getByRole("gridcell", { name: "42,500", exact: true })).toBeVisible();
    const workspaceName = viewport.width >= 1024 ? page.getByTestId("pages-context-bar") : page.getByTestId("reader-shell-name");
    await expect(workspaceName).toContainText("Test workspace");
    await expect(page.getByText("Reader organization · Test workspace", { exact: true }).filter({ visible: true })).toHaveCount(0);
    await expect(page.getByRole("link", { name: "Built with Monomer", exact: true }).filter({ visible: true })).toHaveCount(0);
    await page.getByRole("button", { name: "Open user menu for Reader" }).filter({ visible: true }).first().click();
    await expect(page.getByRole("menuitem", { name: "Switch workspace" })).toHaveAttribute("href", "/workspaces");
    await expect(page.getByRole("menuitem", { name: "Settings" })).toHaveAttribute("href", "/settings/profile");
    await expect(page.getByRole("menuitem", { name: "Sign out" })).toBeVisible();
    await page.keyboard.press("Escape");
    await page.screenshot({ path: evidencePath(`screenshots/pages-reader-member-${viewport.width}.png`), fullPage: false });
  });
}

test("an organization-only public visitor keeps a Workspace-only reader header", async ({ page, consoleErrors }) => {
  let workspaceInventoryReads = 0;
  await fixture(page, consoleErrors, {
    mode: "PUBLIC",
    authenticated: true,
    graphqlOperations: {
      Me: (route) => sessionJson(route, { data: { me: {
        user: { id: "reader", email: "reader@example.test", displayName: "Reader" },
        organizationMemberships: [{ organization: { id: "reader-organization", displayName: "Private organization name", plan: "FREE", createdAt: time }, role: "MEMBER" }],
      } } }),
      AccessibleWorkspaces: (route) => {
        workspaceInventoryReads += 1;
        return sessionJson(route, { data: { accessibleWorkspaces: [
          { id: workspace, organizationId: "reader-organization", name: "Test workspace", publicPages: false, reportingTimeZone: "UTC", role: "VIEWER" },
        ] } });
      },
    },
  });
  await page.goto(target);
  await expect(page.getByRole("gridcell", { name: "42,500", exact: true })).toBeVisible();
  await expect(page.getByText("Test workspace", { exact: true }).filter({ visible: true }).first()).toBeVisible();
  await expect(page.getByText(/Private organization name/)).toHaveCount(0);
  expect(workspaceInventoryReads).toBe(0);
  await page.screenshot({ path: evidencePath("screenshots/pages-reader-public-member.png"), fullPage: false });
});

test("public prepared entry pins once, stays display-only, explores and saves the ready chart", async ({ page, consoleErrors }) => {
  await page.setViewportSize({ width: 1440, height: 1000 });
  const { calls } = await fixture(page, consoleErrors);
  await page.context().grantPermissions(["clipboard-read", "clipboard-write"]);
  await page.goto(`${target}?run=${run}&scheduleKey=weekly`);
  await expect(page).toHaveURL(`${target}?session=${session}`);
  await expect(
    page.getByRole("columnheader", { name: "Revenue", exact: true }),
  ).toBeVisible();
  expect(calls.filter((call) => call.url.includes(":resolve"))).toHaveLength(0);
  expect(
    calls.filter((call) => call.url.includes("/sessions/entry")),
  ).toHaveLength(1);
  await expect(
    page.getByRole("button", { name: "Open history", exact: true }),
  ).toBeVisible();
  await expect(
    page.getByRole("button", { name: "New session", exact: true }),
  ).toHaveCount(0);
  await expect(
    page.getByRole("button", { name: "Refresh", exact: true }),
  ).toHaveCount(0);
  await expect(page.getByTestId("dashboard-chart-widget")).toHaveAttribute(
    "data-chart-status",
    "ready",
  );
  await page.screenshot({
    path: evidencePath("../screenshots/public-pages/desktop.png"),
    fullPage: true,
  });
  await expect(page.getByRole("link", { name: "Built with Monomer", exact: true })).toBeVisible();
  await expect(page.getByRole("link", { name: "Built with Monomer", exact: true }).filter({ visible: true }).first()).toHaveAttribute("href", "https://monomer.cc/");
  await expect(page.getByRole("group", { name: "Inputs and results", exact: true })).toContainText("Results from ");
  await page.getByRole("button", { name: "Copy link", exact: true }).click();
  expect(new URL(await page.evaluate(() => navigator.clipboard.readText())).search).toBe("");
  await expect(page.getByRole("button", { name: "Link copied", exact: true })).toBeVisible();
  await expect(page.getByRole("status").filter({ hasText: "Link copied" })).toHaveCount(1);
  await page.getByRole("button", { name: "Link options", exact: true }).click();
  await page.getByRole("button", { name: /^Current results/ }).click();
  expect(await page.evaluate(() => navigator.clipboard.readText())).toContain(
    `?session=${session}`,
  );
  await page.getByRole("button", { name: "Capture dashboard", exact: true }).click();
  await page.getByRole("button", { name: "Clear", exact: true }).click();
  await page.getByRole("button", { name: "Select chart", exact: true }).click();
  await page.getByRole("button", { name: "Capture", exact: true }).click();
  const download = page.waitForEvent("download");
  await page.getByRole("link", { name: "Save PNG", exact: true }).click();
  const saved = await download;
  expect(saved.suggestedFilename()).toMatch(/\.png$/);
  await saved.saveAs(evidencePath("../screenshots/public-pages/ready-chart.png"));
  const png = await readFile(evidencePath("../screenshots/public-pages/ready-chart.png"));
  const colors = await page.evaluate(async base64 => {
    const image = new Image(); image.src = `data:image/png;base64,${base64}`; await image.decode();
    const canvas = document.createElement("canvas"); canvas.width = image.width; canvas.height = image.height;
    const context = canvas.getContext("2d")!; context.drawImage(image, 0, 0);
    const pixels = context.getImageData(0, 0, canvas.width, canvas.height).data;
    let blue = 0; let red = 0;
    for (let i = 0; i < pixels.length; i += 4) { const [r, g, b] = pixels.slice(i, i + 3); if (b > 120 && b > r * 1.3 && b > g * 1.1) blue++; if (r > 120 && r > g * 1.3 && r > b * 1.3) red++; }
    return { blue, red };
  }, png.toString("base64"));
  expect(colors.blue).toBeGreaterThan(500);
  expect(colors.red).toBeGreaterThan(500);
  await page.getByRole("button", { name: "Close capture preview", exact: true }).click();

  await page.getByRole("textbox", { name: /Region/ }).fill("South");
  await page.getByRole("button", { name: "Apply Region", exact: true }).click();
  await expect
    .poll(() => calls.filter((call) => call.url.includes(":resolve")).length)
    .toBe(1);
  const resolution = calls.find((call) => call.url.includes(":resolve"))!;
  expect(resolution.key).toMatch(/^[0-9a-f-]{36}$/);
  expect(JSON.parse(resolution.body!)).toEqual({
    variables: { region: "South" },
  });
  await page.getByRole("button", { name: "Open history", exact: true }).click();
  await page
    .getByRole("button", { name: "Back to history", exact: true })
    .click();
  await page.getByRole("button", { name: /Opened Sep 20, 2026/ }).click();
  await expect(page).toHaveURL(`${target}?session=${older}`);
  expect(
    calls.every(
      (call) =>
        call.url.includes("/v2/public/") || call.url.endsWith("/pages/access"),
    ),
  ).toBe(true);
});

test("public mobile history is a drawer and empty/private/document states remain safe", async ({ page, consoleErrors }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await fixture(page, consoleErrors);
  await page.goto(target);
  await expect(
    page.getByRole("heading", { name: "Weekly revenue" }),
  ).toBeVisible();
  await page.getByRole("button", { name: "Open history", exact: true }).click();
  await expect(page.getByRole("dialog")).toBeVisible();
  await page.screenshot({
    path: evidencePath("../screenshots/public-pages/mobile-history.png"),
    fullPage: true,
  });
  await page.getByRole("button", { name: "Close history" }).click();
  await expect
    .poll(() =>
      page.evaluate(
        () => document.documentElement.scrollWidth <= window.innerWidth,
      ),
    )
    .toBe(true);
  const accessibility = await new AxeBuilder({ page })
    .withTags(["wcag2a", "wcag2aa"])
    .analyze();
  expect(accessibility.violations).toEqual([]);
});

test("retry retains the same UUID and body, revocation removes displayed data", async ({ page, consoleErrors }) => {
  consoleErrors.allow({ status: 503, url: /:resolve$/, reason: "The fixture fails the first resolve so the reader retries the same request." });
  consoleErrors.allow({ status: 404, url: /\/v2\/public\//, reason: "The fixture revokes the public Page after it is displayed." });
  const { calls, state } = await fixture(page, consoleErrors, {
    partial: true,
    failResolve: true,
  });
  await page.goto(target);
  await expect(
    page.getByRole("button", { name: "Retry inputs" }),
  ).toBeVisible();
  await page.getByRole("button", { name: "Retry inputs" }).click();
  await expect(
    page.getByRole("columnheader", { name: "Revenue", exact: true }),
  ).toBeVisible();
  const requests = calls.filter((call) => call.url.includes(":resolve"));
  expect(requests).toHaveLength(2);
  expect(requests[0].key).toBe(requests[1].key);
  expect(requests[0].body).toBe(requests[1].body);
  state.revoked = true;
  await expect(
    page.getByRole("heading", { name: "Page unavailable" }),
  ).toBeVisible({ timeout: 20_000 });
  await expect(
    page.getByRole("columnheader", { name: "Revenue", exact: true }),
  ).toHaveCount(0);
});

test("empty entry performs no execution", async ({ page, consoleErrors }) => {
  const { calls } = await fixture(page, consoleErrors, { empty: true });
  await page.goto(target);
  await expect(
    page.getByText("No results have been published yet. Check back later."),
  ).toBeVisible();
  expect(calls.some((call) => call.method === "POST")).toBe(false);
});
test("private response reveals no cached title or result", async ({ page, consoleErrors }) => {
  consoleErrors.allow({ status: 404, url: /\/v2\/public\//, reason: "The fixture answers the private Page as not found." });
  await fixture(page, consoleErrors, { denied: true });
  await page.goto(target);
  await expect(
    page.getByRole("heading", { name: "Page unavailable" }),
  ).toBeVisible();
  await expect(page.getByText("Weekly revenue")).toHaveCount(0);
});
test("public documents use the non-executable renderer", async ({ page, consoleErrors }) => {
  consoleErrors.allow({ message: /^Blocked script execution in 'about:srcdoc'/, reason: "Chromium refuses every script in the sandboxed static document frame, including Playwright's own frame evaluation." });
  await fixture(page, consoleErrors, { document: true });
  await page.goto(target);
  await expect(
    page.getByRole("heading", { name: "Weekly revenue" }),
  ).toBeVisible();
  await expect(page.locator("iframe")).toHaveAttribute("sandbox", "");
  await expect(page).not.toHaveTitle("executed");
});

test("unavailable artifact reports an error without launching another query", async ({ page, consoleErrors }) => {
  consoleErrors.allow({ status: 503, url: /\/result$/, reason: "The fixture makes the stored result artifact unavailable." });
  const { calls } = await fixture(page, consoleErrors, { resultFailure: true });
  // immutableResultOptions retries a 503 twice. Start the UI assertion after the final
  // response, so entry/auth reads and the 1s + 2s backoff do not consume its budget.
  let failedResultReads = 0;
  const finalFailure = page.waitForResponse((response) =>
    new URL(response.url()).pathname.endsWith(`/query-runs/${run}/result`)
    && response.status() === 503
    && ++failedResultReads === 3);
  await page.goto(target);
  expect(await (await finalFailure).finished()).toBeNull();
  expect(failedResultReads).toBe(3);
  await expect(
    page.getByText("Results could not be loaded. Please retry.").first(),
  ).toBeVisible();
  expect(calls.some((call) => call.url.includes(":resolve"))).toBe(false);
});

test("a member admission failure never renders prefetched public data", async ({ page, consoleErrors }) => {
  consoleErrors.allow({ status: 403, url: /\/pages\/access$/, reason: "The fixture denies member admission." });
  const { calls } = await fixture(page, consoleErrors);
  await page.route("**/pages/access", (route) =>
    route.fulfill({
      status: 403,
      contentType: "application/json",
      body: JSON.stringify({ title: "Access denied" }),
    }),
  );
  await page.goto(target);
  await expect(
    page.getByRole("heading", { name: "Page unavailable" }),
  ).toBeVisible();
  expect(calls.filter(call => call.url.includes("/v2/public/")).every(call => call.url.endsWith("/tree") || call.url.endsWith("/sessions/entry"))).toBe(true);
  await expect(page.getByRole("heading", { name: "Weekly revenue" })).toHaveCount(0);
  expect(calls.some(call => call.url.includes("/result") || call.method === "POST")).toBe(false);
});

test("public settings disclosure is visible only to the Workspace Owner", async ({
  page,
}) => {
  await page.goto("/dev/workspace-settings-demo?surface=public-access");
  const publicSwitch = page.getByRole("switch", { name: "Public Pages" });
  await expect(publicSwitch).toBeVisible();
  await expect(page.getByRole("heading", { name: "Public access", level: 1 })).toBeVisible();
  await expect(page.getByText("Every Page in this workspace, now and in the future").first()).toBeVisible();
  await expect(page.getByText("Uploaded documents and their replacements").first()).toBeVisible();
  await expect(page.getByText("While on, anyone with Explorer or higher can publish by adding to Pages. 4 people can publish now. Visitors can't edit anything.", { exact: true })).toBeVisible();
  await expect(page.getByText(/link previews already shared may remain/)).toBeVisible();
  await page.screenshot({
    path: evidencePath("../screenshots/public-pages/owner-setting.png"),
    fullPage: true,
  });
  // The demo answers GraphQL in the page, so the first confirmation fails there.
  await page.evaluate(() => {
    const fixtureFetch = window.fetch;
    let failures = 1;
    window.fetch = async (input, init) => {
      if (failures > 0 && typeof init?.body === "string" && init.body.includes("SetWorkspacePublicPages")) {
        failures -= 1;
        return new Response(JSON.stringify({ errors: [{ message: "Permission denied" }] }), { status: 200, headers: { "Content-Type": "application/json" } });
      }
      return fixtureFetch(input, init);
    };
  });
  await publicSwitch.click();
  const confirmation = page.getByRole("alertdialog", { name: "Make all Pages in Revenue Analytics public?" });
  await expect(confirmation).toContainText("Visitors can't edit anything.");
  await confirmation.getByRole("button", { name: "Cancel" }).click();
  await expect(confirmation).toBeHidden();
  await expect(publicSwitch).not.toBeChecked();
  await publicSwitch.click();
  await confirmation.getByRole("button", { name: "Make public" }).click();
  await expect(confirmation.getByRole("alert").filter({ hasText: "Permission denied" })).toBeVisible();
  await expect(confirmation).toBeVisible();
  // The modal confirmation hides the page from the accessibility tree; the switch underneath stays off.
  await expect(page.getByRole("switch", { name: "Public Pages", includeHidden: true })).not.toBeChecked();
  await confirmation.getByRole("button", { name: "Make public" }).click();
  await expect(confirmation).toBeHidden();
  await expect(publicSwitch).toBeChecked();
  await expect(page.getByTestId("desktop-sidebar").getByRole("link", { name: /^Public access/ })).toContainText("On");
  await publicSwitch.click();
  await expect(publicSwitch).not.toBeChecked();
  await page.goto("/dev/workspace-settings-demo?viewer=explorer&surface=public-access");
  await expect(page.getByText("Only workspace owners can change these settings.", { exact: true })).toBeVisible();
  await expect(page.getByRole("switch", { name: "Public Pages" })).toHaveCount(0);
});


test("public entry waits for current identity and history waits for its drawer", async ({ page, consoleErrors }) => {
  const { calls } = await fixture(page, consoleErrors);
  let releaseAuth!: () => void;
  let releaseTree!: () => void;
  const authGate = new Promise<void>(resolve => { releaseAuth = resolve; });
  const treeGate = new Promise<void>(resolve => { releaseTree = resolve; });
  await page.route("**/auth/refresh", async route => {
    await authGate;
    await route.fulfill({ status: 401, body: "" });
  });
  await page.route("**/v2/public/**/tree", async route => {
    await treeGate;
    await route.fallback();
  });
  await page.goto(`${target}?session=${session}`);
  expect(calls.some(call => call.url.includes("/sessions/entry"))).toBe(false);
  expect(calls.some(call => call.url.endsWith("/pages/access"))).toBe(false);
  await expect(page.getByRole("heading", { name: "Weekly revenue" })).toHaveCount(0);
  releaseAuth();
  await expect.poll(() => calls.some(call => call.url.endsWith("/pages/access"))).toBe(true);
  await expect(page.getByRole("heading", { name: "Weekly revenue" })).toHaveCount(0);
  expect(calls.some(call => call.url.includes("/query-runs/"))).toBe(false);
  releaseTree();
  await expect(page.getByRole("columnheader", { name: "Revenue", exact: true })).toBeVisible();
  expect(calls.filter(call => call.url.includes("/sessions/entry"))).toHaveLength(1);
  expect(calls.filter(call => call.url.endsWith("/tree"))).toHaveLength(1);
  expect(calls.filter(call => /\/(sessions|query-runs)\?/.test(call.url))).toHaveLength(0);
  await page.getByRole("button", { name: "Open history", exact: true }).click();
  await expect.poll(() => calls.some(call => call.url.includes("/query-runs?"))).toBe(true);
  expect(calls.some(call => call.url.includes("/sessions?"))).toBe(false);
  await page.getByRole("button", { name: "Back to history", exact: true }).click();
  await expect.poll(() => calls.some(call => call.url.includes("/sessions?"))).toBe(true);
  await page.getByRole("button", { name: "Close history" }).click();
  const historyReads = () => calls.filter(call => /\/(sessions|query-runs)\?/.test(call.url)).length;
  const readsBeforeClosedPolling = historyReads();
  await page.clock.install();
  await page.clock.fastForward(16_000);
  expect(historyReads()).toBe(readsBeforeClosedPolling);
});


test("prepared public metadata skips individual run reads but new filter targets load metadata", async ({ page, consoleErrors }) => {
  const { calls } = await fixture(page, consoleErrors);
  await page.goto(target);
  await expect(page.getByRole("gridcell", { name: "42,500", exact: true })).toBeVisible();
  expect(calls.some(call => call.url.endsWith(`/query-runs/${run}/result`))).toBe(true);
  expect(calls.some(call => call.url.endsWith(`/query-runs/${run}`) || call.url.includes(":resolve"))).toBe(false);
  await page.clock.install();
  await page.clock.fastForward(6_000);
  expect(calls.some(call => call.url.endsWith(`/query-runs/${run}`))).toBe(false);
  await page.getByRole("textbox", { name: /Region/ }).fill("South");
  await page.getByRole("button", { name: "Apply Region", exact: true }).click();
  await expect.poll(() => calls.some(call => call.url.endsWith(`/query-runs/${filteredRun}/result`))).toBe(true);
  expect(calls.filter(call => call.url.endsWith(`/query-runs/${filteredRun}`))).toHaveLength(1);
  expect(calls.filter(call => call.url.includes(":resolve"))).toHaveLength(1);
});

test("successful public entry metadata never authorizes a denied result", async ({ page, consoleErrors }) => {
  consoleErrors.allow({ status: 403, url: /\/result$/, reason: "The fixture denies the result read." });
  const { calls } = await fixture(page, consoleErrors);
  let deniedReads = 0;
  page.on("response", response => {
    if (response.status() === 403 && response.url().endsWith("/result")) deniedReads++;
  });
  await page.route("**/query-runs/*/result", route => route.fulfill({ status: 403, contentType: "application/json", body: JSON.stringify({ title: "Access denied" }) }));
  await page.goto(target);
  await expect(page.getByRole("heading", { name: "Page unavailable" })).toBeVisible();
  await expect(page.getByRole("gridcell", { name: "42,500", exact: true })).toHaveCount(0);
  await page.clock.install();
  await page.clock.fastForward(30_000);
  await expect(page.getByRole("heading", { name: "Page unavailable" })).toBeVisible();
  expect(deniedReads).toBe(1);
  expect(calls.some(call => call.url.endsWith(`/query-runs/${run}`) || call.url.includes(":resolve"))).toBe(false);
  await page.screenshot({ path: evidencePath("../screenshots/public-pages/result-denied.png"), fullPage: true });
});


test("public filter result denial erases the previous ready bundle", async ({ page, consoleErrors }) => {
  consoleErrors.allow({ status: 403, url: /\/result$/, reason: "The fixture denies the filtered result read." });
  await fixture(page, consoleErrors);
  await page.goto(target);
  await expect(page.getByRole("gridcell", { name: "42,500", exact: true })).toBeVisible();
  await page.route(`**/query-runs/${filteredRun}/result`, route => route.fulfill({ status: 403, contentType: "application/json", body: JSON.stringify({ title: "Access denied" }) }));
  await page.getByRole("textbox", { name: /Region/ }).fill("South");
  await page.getByRole("button", { name: "Apply Region", exact: true }).click();
  await expect(page.getByRole("heading", { name: "Page unavailable" })).toBeVisible();
  await expect(page.getByRole("gridcell", { name: "42,500", exact: true })).toHaveCount(0);
});

for (const mode of ["VIEWER", "PUBLIC"] as const) {
  test(`authenticated ${mode} reader filters existing Session with actual identity`, async ({ page, consoleErrors }) => {
    const { calls } = await fixture(page, consoleErrors, { mode, authenticated: true });
    await page.goto(target);
    await expect(page.getByRole("gridcell", { name: "42,500", exact: true })).toBeVisible();
    await expect(page.getByText("Overview", { exact: true })).toHaveCount(0);
    await expect(page.getByRole("button", { name: "Open search" })).toHaveCount(0);
    await page.getByRole("textbox", { name: /Region/ }).fill("South");
    await page.getByRole("button", { name: "Apply Region", exact: true }).click();
    await expect.poll(() => calls.some(call => call.url.includes(":resolve"))).toBe(true);
    expect(calls.filter(call => call.url.includes("/v2/public/")).every(call => call.authorization === `Bearer ${E2E_ACCESS_TOKEN}`)).toBe(true);
    expect(calls.find(call => call.url.includes(":resolve"))?.authorization).toBe(`Bearer ${E2E_ACCESS_TOKEN}`);
    expect(calls.filter(call => call.method === "POST" && !call.url.endsWith("/graphql") && !call.url.includes(":resolve"))).toHaveLength(0);
    expect(calls.filter(call => call.url.includes("/v2/workspaces/") && !call.url.endsWith("/pages/access"))).toHaveLength(0);
    await page.screenshot({ path: evidencePath(`screenshots/role-${mode.toLowerCase()}-page-reader.png`), fullPage: true });
  });
}

test("a signed-in Viewer keeps Pages open after the access token expires", async ({ page, consoleErrors }) => {
  consoleErrors.allow({ status: 401, url: /\/v2\//, reason: "The fixture expires the reader's first access token." });
  await fixture(page, consoleErrors, { mode: "VIEWER", authenticated: true });
  let expired = false;
  let refreshesAfterExpiry = 0;
  await page.route("**/auth/refresh", (route) => {
    if (expired) refreshesAfterExpiry += 1;
    return route.fulfill({ json: { access_token: expired ? "renewed-token" : E2E_ACCESS_TOKEN, expires_at: "2099-01-01T00:00:00Z" } });
  });
  await page.route("**/v2/**", (route) => {
    const authorization = route.request().headers().authorization;
    if (expired && authorization && authorization !== "Bearer renewed-token") return route.fulfill({ status: 401, json: { title: "Access token expired" } });
    return route.fallback();
  });
  await page.clock.install();
  await page.goto(target);
  await expect(page.getByRole("gridcell", { name: "42,500", exact: true })).toBeVisible();

  expired = true;
  // The open tab's 15-second polls run again after the token has expired.
  await page.clock.fastForward(16_000);
  await expect.poll(() => refreshesAfterExpiry).toBe(1);
  await page.clock.fastForward(16_000);
  await expect(page.getByRole("heading", { name: "Page unavailable" })).toHaveCount(0);
  await expect(page.getByRole("gridcell", { name: "42,500", exact: true })).toBeVisible();
});

test("Viewer internal deep links stop before metadata loads and workspace root opens Pages", async ({ page, consoleErrors }) => {
  const { calls } = await fixture(page, consoleErrors, {
    mode: "VIEWER",
    authenticated: true,
    graphqlOperations: {
      WorkspaceAccess: (route) => sessionJson(route, { data: { workspaceAccess: { granted: true, organizationId: "organization", role: "VIEWER", } } }),
      AccessibleWorkspaces: (route) => sessionJson(route, { data: { accessibleWorkspaces: [{ id: workspace, organizationId: "organization", name: "Test workspace", publicPages: false, reportingTimeZone: "UTC", role: "VIEWER" }] } }),
    },
  });
  await page.goto(`/workspaces/${workspace}/dashboards/${pageId}`);
  await expect(page.getByRole("heading", { name: "Your workspace is in Pages" })).toBeVisible();
  expect(calls.filter(call => call.url.includes("/dashboards/") || call.url.includes("/search"))).toHaveLength(0);
  await page.goto(`/workspaces/${workspace}/settings`);
  await expect(page.getByRole("heading", { name: "Your workspace is in Pages" })).toBeVisible();
  await expect(page.getByRole("button", { name: "Add member" })).toHaveCount(0);
  await page.goto(`/workspaces/${workspace}`);
  await expect(page).toHaveURL(new RegExp(`/workspaces/${workspace}/pages$`));
  await expect(page.getByRole("link", { name: "Weekly revenue", exact: true }).first()).toBeVisible();
});


test("authenticated MEMBER admission sends no reader requests while pending or after admission", async ({ page, consoleErrors }) => {
  // Admission hands the route to the member shell; its session reads stay pending so the test observes only the reader.
  const memberSessionRead = () => new Promise<void>(() => {});
  const { calls } = await fixture(page, consoleErrors, {
    authenticated: true,
    graphqlOperations: { WorkspaceAccess: memberSessionRead, AccessibleWorkspaces: memberSessionRead },
  });
  let release!: () => void;
  const gate = new Promise<void>(resolve => { release = resolve; });
  await page.route("**/pages/access", async route => { await gate; await route.fulfill({ json: { mode: "MEMBER" } }); });
  await page.goto(target);
  await expect(page.getByText("Opening Pages…")).toBeVisible();
  expect(calls.filter(call => call.url.includes("/v2/public/"))).toHaveLength(0);
  release();
  await expect(page.getByText("Opening Pages…")).toHaveCount(0);
  expect(calls.filter(call => call.url.includes("/v2/public/"))).toHaveLength(0);
});

test("Viewer read rejection keeps its identity and never falls back to anonymous", async ({ page, consoleErrors }) => {
  consoleErrors.allow({ status: 401, url: /\/v2\/public\//, reason: "The fixture rejects the Viewer bearer on public reads." });
  await fixture(page, consoleErrors, { authenticated: true, mode: "VIEWER" });
  const reads: Array<string | undefined> = [];
  await page.route("**/v2/public/**", async route => {
    reads.push(route.request().headers().authorization);
    await route.fulfill({ status: 401, json: { title: "Invalid bearer" } });
  });
  await page.goto(target);
  await expect(page.getByRole("heading", { name: "Page unavailable" })).toBeVisible();
  await expect(page.getByText("This Page is unavailable or you no longer have access.")).toBeVisible();
  await expect(page.getByRole("link", { name: "Sign in" })).toHaveCount(0);
  expect(reads.length).toBeGreaterThan(0);
  expect(reads.every(header => header === `Bearer ${E2E_ACCESS_TOKEN}`)).toBe(true);
  await page.screenshot({ path: evidencePath("screenshots/wp4-viewer-unavailable.png"), fullPage: true });
});

test("anonymous direct Page prefetch overlaps admission but does not display before admission", async ({ page, consoleErrors }) => {
  const { calls } = await fixture(page, consoleErrors);
  let release!: () => void;
  const gate = new Promise<void>(resolve => { release = resolve; });
  await page.route("**/pages/access", async route => { await gate; await route.fulfill({ json: { mode: "PUBLIC" } }); });
  await page.goto(target);
  await expect.poll(() => calls.filter(call => call.url.endsWith("/tree") || call.url.endsWith("/sessions/entry")).length).toBe(2);
  expect(calls.filter(call => call.url.includes("/v2/public/")).every(call => !call.authorization)).toBe(true);
  await expect(page.getByRole("gridcell", { name: "42,500", exact: true })).toHaveCount(0);
  release();
  await expect(page.getByRole("gridcell", { name: "42,500", exact: true })).toBeVisible();
  expect(calls.filter(call => call.url.endsWith("/sessions/entry"))).toHaveLength(1);
});
