import { expect, test } from "./support/test";
import type { Page, Route } from "@playwright/test";
import { completeOAuthCallback } from "./oauth-callback-correlation";
import { evidencePath } from "./evidence";
import { mockSignedInWorkspace } from "./support/session";

const organizationId = "019e8000-0000-7000-8000-000000000a01";
const workspaceId = "019e8000-0000-7000-8000-000000000b01";

function json(body: unknown, status = 200) {
  return {
    status,
    contentType: "application/json",
    body: JSON.stringify(body),
  };
}

async function authenticateWorkspace(page: Page, canMutate = true): Promise<void> {
  await mockSignedInWorkspace(page, {
    workspaceId,
    workspaceName: "E2E Workspace",
    organizationId,
    role: canMutate ? "OWNER" : "EXPLORER",
    user: { id: "e2e-user", email: "e2e@example.test", displayName: "E2E User" },
  });
  await page.route("**/v2/workspaces/*/catalog/connector-summaries", (route: Route) => route.fulfill(json({ connectors: [] })));
  await completeOAuthCallback(page, { new_user: "false", auth_provider: "google" }, "/");
  await page.waitForURL((url) => url.pathname === "/");
}

test.describe("connector management", () => {
  test("lists connectors and creates through the current POST contract", async ({ page }) => {
    const posted: { url: string; idempotencyKey: string | null; body: unknown }[] = [];
    await page.route("**/v2/workspaces/*/connectors", async (route: Route) => {
      if (route.request().method() === "POST") {
        const body = route.request().postDataJSON() as Record<string, unknown>;
        posted.push({
          url: route.request().url(),
          idempotencyKey: route.request().headers()["idempotency-key"] ?? null,
          body,
        });
        await route.fulfill(json({
          connectorId: "019e8000-0000-7000-8000-000000000c01",
          workspaceId,
          type: "POSTGRESQL",
          displayName: "Lab Postgres",
          properties: { host: "127.0.0.1", port: 5432, database: "warehouse" },
          createdAt: "2026-08-16T00:00:00Z",
          updatedAt: "2026-08-16T00:00:00Z",
        }, 201));
        return;
      }
      await route.fulfill(json({
        connectors: posted.length === 0
          ? []
          : [{
              id: "019e8000-0000-7000-8000-000000000c01",
              workspaceId,
              name: "Lab Postgres",
              type: "POSTGRESQL",
              properties: { host: "127.0.0.1", port: 5432, database: "warehouse" },
              createdAtEpochMs: 1_755_321_600_000,
              updatedAtEpochMs: 1_755_321_600_000,
            }],
      }));
    });

    let schemasListed = false;
    const discoveryJob = {
      jobId: "019e8000-0000-7000-8000-000000000d01", parentKind: "CONNECTOR", childKind: "SCHEMA", catalogName: null, schemaName: null,
      state: "COMPLETED", requestedAt: "2026-08-16T00:00:00Z", terminalAt: "2026-08-16T00:00:01Z", failureCode: null, safeMessage: null,
    };
    await page.route("**/v2/workspaces/*/catalog/**", async (route: Route) => {
      const path = new URL(route.request().url()).pathname;
      if (path.endsWith("/selection/list-refresh-jobs") && route.request().method() === "POST") {
        schemasListed = true;
        await route.fulfill(json(discoveryJob, 202));
        return;
      }
      if (path.endsWith("/selection/options")) {
        await route.fulfill(json({
          parentKind: "CONNECTOR", childKind: "SCHEMA", catalogName: null, schemaName: null,
          fetchedAt: schemasListed ? "2026-08-16T00:00:01Z" : null,
          options: schemasListed ? [{ name: "public", relationKind: null, available: true }, { name: "sales", relationKind: null, available: true }] : [],
          totalCount: schemasListed ? 2 : 0, page: 1, pageSize: 50,
          latestJob: schemasListed ? discoveryJob : null,
        }));
        return;
      }
      if (path.endsWith("/ingests/active")) {
        await route.fulfill({ status: 204 });
        return;
      }
      if (path.endsWith("/ingests")) {
        await route.fulfill(json({ jobs: [], totalCount: 0, page: 1, pageSize: 1 }));
        return;
      }
      if (path.endsWith("/schemas")) {
        await route.fulfill(json({ schemas: [], totalCount: 0, page: 1, pageSize: 50 }));
        return;
      }
      await route.fulfill(json({ selectedSchemas: [], committedAt: "2026-08-16T00:00:00Z" }));
    });

    await authenticateWorkspace(page);
    await page.goto(`/workspaces/${workspaceId}/connectors`);
    await expect(page.getByRole("heading", { name: "Connectors", exact: true })).toBeVisible();
    await expect(page.getByText("Connect this workspace’s first data source to browse its tables in Catalog.")).toBeVisible();
    await expect(page.getByRole("navigation", { name: "Workspace navigation" }).getByText("Connectors", { exact: true })).toBeVisible();
    await page.keyboard.press("Meta+k");
    await expect(page.getByRole("dialog", { name: "Search" }).getByText("Connectors", { exact: true })).toBeVisible();
    await page.keyboard.press("Escape");

    await expect(page.getByRole("button", { name: "Create connector" })).toHaveCount(2);
    await page.screenshot({ path: evidencePath("screenshots/joo-23-connectors-empty.png") });
    await page.getByRole("button", { name: "Create connector" }).last().click();
    const dialog = page.getByTestId("connector-mutation-dialog");
    await expect(dialog).toBeVisible();
    const typeStepBounds = await dialog.boundingBox();
    expect(typeStepBounds?.width).toBe(840);
    expect(typeStepBounds?.height).toBeLessThan(600);
    await page.screenshot({ path: evidencePath("../screenshots/2026-08-20-connectors-create-type.png"), fullPage: true });
    const postgresql = dialog.getByRole("radio", { name: "PostgreSQL" });
    const redshift = dialog.getByRole("radio", { name: "Redshift" });
    const postgresqlMark = postgresql.locator("xpath=..").locator('[data-connector-type-mark="POSTGRESQL"]');
    await expect(postgresqlMark).toHaveCSS("background-color", "rgba(0, 0, 0, 0)");
    await expect(postgresqlMark).toHaveCSS("width", "36px");
    await expect(postgresqlMark.locator("svg")).toHaveCSS("width", "23px");
    await page.screenshot({ path: evidencePath("../screenshots/2026-08-21-connectors-create-icon-only.png"), fullPage: true });
    await postgresql.focus();
    await page.keyboard.press("End");
    await expect(redshift).toBeChecked();
    await expect(redshift).toBeFocused();
    await page.keyboard.press("Home");
    await expect(postgresql).toBeChecked();
    await expect(postgresql).toBeFocused();
    await expect(postgresql.locator("xpath=..")).toHaveCSS("outline-width", "2px");
    await dialog.getByRole("button", { name: "Next" }).click();
    const configureStepBounds = await dialog.boundingBox();
    expect(configureStepBounds?.width).toBe(840);
    expect(configureStepBounds?.height).toBeGreaterThan(typeStepBounds?.height ?? 0);
    await page.screenshot({ path: evidencePath("../screenshots/2026-08-20-connectors-configure.png"), fullPage: true });
    await dialog.getByLabel("Display name").focus();
    await expect(dialog.getByLabel("Display name")).toBeFocused();
    await dialog.getByLabel("Display name").fill("Lab Postgres");
    await dialog.getByLabel("Host").fill("127.0.0.1");
    await expect(dialog.getByLabel("Port")).toHaveValue("5432");
    await dialog.getByLabel("Database").fill("warehouse");
    await dialog.getByLabel("Username").fill("dw_query");
    await dialog.getByLabel("Password").fill("secret");
    await dialog.getByRole("button", { name: "Create" }).click();

    await expect(dialog).toBeHidden();
    expect(posted).toHaveLength(1);
    expect(posted[0]?.idempotencyKey).toMatch(/^create-[0-9a-f-]+$/);
    expect(posted[0]?.body).toMatchObject({
      connectorType: "POSTGRESQL",
      displayName: "Lab Postgres",
      properties: { host: "127.0.0.1", port: 5432, database: "warehouse" },
      credential: { kind: "USERNAME_PASSWORD", username: "dw_query", password: "secret" },
    });
    expect(posted[0]?.body).not.toHaveProperty("executionPolicy");
    await page.waitForURL((url) => url.pathname === `/workspaces/${workspaceId}/catalog` && url.searchParams.get("connector") === "019e8000-0000-7000-8000-000000000c01" && url.searchParams.get("choose") === "1");
    const chooser = page.getByTestId("catalog-selection-drawer");
    await expect(chooser.getByRole("heading", { name: "Choose tables to load" })).toBeVisible();
    await expect(chooser.getByRole("status").filter({ hasText: "Connected. Found 2 schemas in warehouse just now." })).toBeVisible();
    await expect(chooser.getByRole("button", { name: "Save and refresh" })).toBeDisabled();
    await page.screenshot({ path: evidencePath("screenshots/joo-105-connector-created-chooser.png") });
    await chooser.getByRole("button", { name: "Close table selection" }).click();
    await expect(page).not.toHaveURL(/choose=1/);
    await expect(page.getByRole("status").filter({ hasText: "Lab Postgres saved. Choose the tables to load — listing the schemas is the first connection to the warehouse." })).toBeVisible();
    await expect(page.getByRole("heading", { name: "Choose what to load from Lab Postgres" })).toBeVisible();
    await page.screenshot({ path: evidencePath("screenshots/joo-91-connector-created-catalog.png") });
    await page.goto(`/workspaces/${workspaceId}/connectors`);
    const connectorRow = page.getByRole("button", { name: "View Lab Postgres connector" });
    await expect(connectorRow).toBeVisible();
    const connectorRowMark = connectorRow.locator('[data-connector-type-mark="POSTGRESQL"]');
    await expect(connectorRowMark).toHaveCSS("background-color", "rgba(0, 0, 0, 0)");
    await expect(connectorRowMark).toHaveCSS("width", "28px");
    await expect(connectorRowMark.locator("svg")).toHaveCSS("width", "20px");
    await connectorRow.focus();
    await page.keyboard.press("Shift+Tab");
    await page.keyboard.press("Tab");
    await expect(connectorRow).toBeFocused();
    await expect(connectorRow).toHaveCSS("outline-width", "2px");
    await page.screenshot({ path: evidencePath("../screenshots/2026-08-20-connectors-list.png"), fullPage: true });
    await connectorRow.click();
    await expect(dialog.getByRole("heading", { name: "Lab Postgres" })).toBeVisible();
    await expect(page).toHaveURL(/\?connector=019e8000-0000-7000-8000-000000000c01$/);
    await expect(dialog.getByText("127.0.0.1", { exact: true })).toBeVisible();
    await expect(dialog.getByRole("link", { name: "Browse in Catalog" })).toHaveAttribute("href", `/workspaces/${workspaceId}/catalog?connector=019e8000-0000-7000-8000-000000000c01`);
    await dialog.getByRole("button", { name: "Edit settings" }).click();
    await expect(dialog.getByRole("heading", { name: "Edit Lab Postgres" })).toBeVisible();
    await expect(dialog.getByLabel("Display name")).toHaveValue("Lab Postgres");
    await dialog.getByRole("button", { name: "Cancel" }).click();
    await expect(dialog.getByRole("heading", { name: "Lab Postgres" })).toBeVisible();
  });

  test("renders loading, failure, and read-only states without duplicate actions", async ({ page, consoleErrors }) => {
    consoleErrors.allow({ status: 503, url: /\/connectors$/, reason: "The fixture makes the connector list unavailable to show the failure state." });
    let releaseLoading: (() => void) | undefined;
    const loadingGate = new Promise<void>((resolve) => {
      releaseLoading = resolve;
    });
    await page.route("**/v2/workspaces/*/connectors", async (route: Route) => {
      await loadingGate;
      await route.fulfill(json({
        connectors: [{
          id: "019e8000-0000-7000-8000-000000000c01",
          workspaceId,
          name: "Northstar warehouse",
          type: "POSTGRESQL",
          properties: { host: "127.0.0.1", port: 5432, database: "warehouse" },
          createdAtEpochMs: 1_755_321_600_000,
          updatedAtEpochMs: 1_755_321_600_000,
        }],
      }));
    });

    await authenticateWorkspace(page, false);
    await page.goto(`/workspaces/${workspaceId}/connectors`);
    await expect(page.getByLabel("Connectors loading")).toBeVisible();
    await page.screenshot({ path: evidencePath("../screenshots/2026-08-20-connectors-loading.png"), fullPage: true });
    releaseLoading?.();

    await expect(page.getByText("Northstar warehouse", { exact: true })).toBeVisible();
    await expect(page.getByRole("button", { name: "Create connector" })).toHaveCount(0);
    const readOnlyRow = page.getByRole("button", { name: "View Northstar warehouse connector" });
    await expect(readOnlyRow).toBeVisible();
    await readOnlyRow.click();
    const readOnlyDialog = page.getByTestId("connector-mutation-dialog");
    await expect(readOnlyDialog.getByRole("button", { name: "Edit settings" })).toHaveCount(0);
    await expect(readOnlyDialog.getByRole("button", { name: "Replace credentials" })).toHaveCount(0);
    await expect(readOnlyDialog.getByRole("button", { name: "Delete connector" })).toHaveCount(0);
    await expect(readOnlyDialog.getByRole("link", { name: "Browse in Catalog" })).toBeVisible();
    await page.screenshot({ path: evidencePath("../screenshots/2026-08-20-connectors-read-only.png"), fullPage: true });
    await readOnlyDialog.getByRole("button", { name: "Close connector details" }).click();
    await expect(page).toHaveURL(new RegExp(`/workspaces/${workspaceId}/connectors$`));

    await page.goto(`/workspaces/${workspaceId}/connectors?connector=019e8000-0000-7000-8000-000000000c01`);
    await expect(page.getByRole("dialog", { name: "Northstar warehouse" })).toBeVisible();

    await page.unroute("**/v2/workspaces/*/connectors");
    await page.route("**/v2/workspaces/*/connectors", async (route: Route) => {
      await route.fulfill(json({ connectors: [] }));
    });
    await page.reload();
    await expect(page.getByRole("heading", { name: "No connectors yet" })).toBeVisible();
    await expect(page.getByText("Only workspace owners can add connectors. Ask an owner to connect a data source.")).toBeVisible();
    await expect(page.getByRole("button", { name: "Create connector" })).toHaveCount(0);
    await page.screenshot({ path: evidencePath("screenshots/joo-23-connectors-empty-read-only.png") });

    await page.unroute("**/v2/workspaces/*/connectors");
    await page.route("**/v2/workspaces/*/connectors", async (route: Route) => {
      await route.fulfill(json({
        type: "about:blank",
        title: "Connector list unavailable",
        status: 503,
        detail: "Try again shortly.",
      }, 503));
    });
    await page.reload();
    await expect(page.getByRole("heading", { name: "Connectors could not be loaded" })).toBeVisible();
    await expect(page.getByRole("button", { name: "Retry connector list" })).toBeVisible();
    await page.screenshot({ path: evidencePath("../screenshots/2026-08-20-connectors-error.png"), fullPage: true });
  });

  test("owner deletes a connector after previewing semantic model impact", async ({ page, consoleErrors }) => {
    consoleErrors.allow({ status: 409, url: /\/connectors\/019e8000-0000-7000-8000-000000000c01$/, reason: "The fixture keeps a Catalog refresh running for the first delete." });
    const connectorId = "019e8000-0000-7000-8000-000000000c01";
    let deleted = false;
    const deleteRequests: string[] = [];
    await page.route("**/v2/workspaces/*/connectors", async (route: Route) => {
      await route.fulfill(json({
        connectors: deleted ? [] : [{
          id: connectorId,
          workspaceId,
          name: "Northstar warehouse",
          type: "POSTGRESQL",
          properties: { host: "127.0.0.1", port: 5432, database: "warehouse" },
          createdAtEpochMs: 1_755_321_600_000,
          updatedAtEpochMs: 1_755_321_600_000,
        }],
      }));
    });
    await page.route(`**/v2/workspaces/*/connectors/${connectorId}`, async (route: Route) => {
      if (route.request().method() !== "DELETE") {
        await route.fallback();
        return;
      }
      deleteRequests.push(route.request().url());
      if (deleteRequests.length === 1) {
        await route.fulfill({ status: 409, contentType: "application/problem+json", body: JSON.stringify({
          type: "https://monomer.cc/problems/connector-refresh-active",
          title: "Conflict",
          status: 409,
          detail: "A Catalog refresh is running for this connector. Cancel it in Catalog, then delete the connector.",
          instance: `/v2/workspaces/${workspaceId}/connectors/${connectorId}`,
          code: "CONNECTOR_REFRESH_ACTIVE",
          requestId: "request-connector-delete",
          traceId: "trace-connector-delete",
          retryable: false,
        }) });
        return;
      }
      deleted = true;
      await route.fulfill({ status: 204, body: "" });
    });
    const definition = (name: string, sourceConnectorId: string) => ({
      schemaVersion: 3, name, label: name.charAt(0).toUpperCase() + name.slice(1),
      sourceRelations: [{ connectorId: sourceConnectorId, schema: "public", relation: name }],
      entities: [], dimensions: [], measures: [],
    });
    await page.route("**/v2/workspaces/*/semantic/semantic-models", async (route: Route) => {
      await route.fulfill(json([
        { sourcePartitionInfo: [], semanticModelId: "019e8000-0000-7000-8000-000000000e01", workspaceId, definition: definition("orders", connectorId), createdAt: "2026-10-01T00:00:00Z", updatedAt: "2026-10-01T00:00:00Z" },
        { sourcePartitionInfo: [], semanticModelId: "019e8000-0000-7000-8000-000000000e02", workspaceId, definition: definition("visits", "019e8000-0000-7000-8000-000000000c02"), createdAt: "2026-10-01T00:00:00Z", updatedAt: "2026-10-01T00:00:00Z" },
      ]));
    });

    await authenticateWorkspace(page);
    await page.goto(`/workspaces/${workspaceId}/connectors?connector=${connectorId}`);
    const detail = page.getByRole("dialog", { name: "Northstar warehouse" });
    await detail.getByRole("button", { name: "Delete connector" }).click();

    const confirm = page.getByRole("alertdialog", { name: "Delete Northstar warehouse?" });
    await expect(confirm).toBeVisible();
    await expect(confirm).toContainText("Its tables leave Catalog, and its saved credentials are destroyed.");
    await expect(confirm).toContainText("Used by 1 semantic model — it won’t run until it uses another connector.");
    await expect(confirm.getByRole("link", { name: "Orders" })).toHaveAttribute("href", `/workspaces/${workspaceId}/semantic-models/019e8000-0000-7000-8000-000000000e01`);
    await expect(confirm.getByRole("link", { name: "Visits" })).toHaveCount(0);
    const submit = confirm.getByRole("button", { name: "Delete connector", exact: true });
    await expect(submit).toBeDisabled();
    await confirm.getByLabel("Type “Northstar warehouse” to confirm").fill("Northstar");
    await expect(submit).toBeDisabled();
    await confirm.getByLabel("Type “Northstar warehouse” to confirm").fill("Northstar warehouse");
    await submit.click();
    await expect(confirm.getByRole("alert")).toContainText("A refresh is running. Cancel it in Catalog, then delete.");
    await expect(confirm.getByRole("link", { name: "Open Catalog" })).toHaveAttribute("href", `/workspaces/${workspaceId}/catalog?connector=${connectorId}`);
    await page.screenshot({ path: evidencePath("screenshots/connector-delete-refresh-active.png") });

    await submit.click();
    await expect(confirm).toHaveCount(0);
    await expect(page.getByRole("status").filter({ hasText: "Northstar warehouse was deleted." })).toBeVisible();
    await expect(page.getByRole("heading", { name: "No connectors yet" })).toBeVisible();
    await expect(page).toHaveURL(new RegExp(`/workspaces/${workspaceId}/connectors$`));
    expect(deleteRequests).toHaveLength(2);
  });

  test("uses a full-screen dialog and scrolling form on mobile", async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    await page.goto("/dev/connectors-demo");
    await page.getByRole("button", { name: "Create connector" }).first().click();
    const dialog = page.getByTestId("connector-mutation-dialog");
    await expect(dialog).toBeVisible();
    await dialog.getByText("Snowflake", { exact: true }).click();
    await expect(dialog.getByRole("radio", { name: "Snowflake" })).toBeChecked();
    await dialog.getByRole("button", { name: "Next" }).click();

    const bounds = await dialog.boundingBox();
    expect(bounds).not.toBeNull();
    expect(bounds?.x).toBe(0);
    expect(bounds?.y).toBe(0);
    expect(bounds?.width).toBe(390);
    expect(bounds?.height).toBe(844);
    const scrollArea = dialog.locator(".transient-scrollbar");
    expect(await scrollArea.evaluate((element) => element.scrollHeight > element.clientHeight)).toBe(true);
    await expect(dialog.getByRole("button", { name: "Create" })).toBeVisible();
    await page.screenshot({ path: evidencePath("../screenshots/2026-08-20-connectors-mobile.png"), fullPage: true });
  });

  test("keeps an open create form when the server rejects a stale role", async ({ page, consoleErrors }) => {
    consoleErrors.allow({ status: 403, url: /\/connectors$/, reason: "The fixture rejects the create for a stale Owner role." });
    let mutationCount = 0;
    await page.route("**/v2/workspaces/*/connectors", async (route: Route) => {
      if (route.request().method() === "POST") {
        mutationCount += 1;
        await route.fulfill({
          status: 403,
          contentType: "application/problem+json",
          body: JSON.stringify({
            type: "https://monomer.cc/problems/connector-manage-required",
            title: "Forbidden",
            status: 403,
            detail: "Owner role is required.",
            instance: "/v2/workspaces/fixture/connectors",
            code: "CONNECTOR_MANAGE_REQUIRED",
            requestId: "request-connector-role",
            traceId: "trace-connector-role",
            retryable: false,
          }),
        });
        return;
      }
      await route.fulfill(json({ connectors: [] }));
    });

    await authenticateWorkspace(page);
    await page.goto(`/workspaces/${workspaceId}/connectors`);
    await page.getByRole("button", { name: "Create connector" }).first().click();
    const dialog = page.getByTestId("connector-mutation-dialog");
    await dialog.getByRole("button", { name: "Next" }).click();
    await dialog.getByLabel("Display name").fill("Rejected warehouse");
    await dialog.getByLabel("Host").fill("fixture.internal");
    await dialog.getByLabel("Database").fill("warehouse");
    await dialog.getByLabel("Username").fill("fixture-reader");
    await dialog.getByLabel("Password").fill("fixture-only");
    await dialog.getByRole("button", { name: "Create" }).click();

    await expect(dialog).toBeVisible();
    await expect(dialog.getByRole("alert")).toContainText("Connector was not created.");
    await expect(dialog.getByRole("alert")).toContainText("Owner role is required.");
    await expect(page.getByText("No connectors yet", { exact: true })).toBeVisible();
    expect(mutationCount).toBe(1);
    await page.screenshot({ path: evidencePath("screenshots/qa-product-chain/con-cat/con-12-server-rejection.png"), fullPage: true });
  });
});

test.describe("connector library", () => {
  test.use({ viewport: { width: 1440, height: 960 } });

  test("Connectors library uses a flat searchable resource list and opens Detail first", async ({ page }) => {
    await page.goto("/dev/connectors-demo");

    const search = page.getByRole("textbox", { name: "Search connectors" });
    await expect(search).toBeVisible();
    await expect(page.getByText("Plan usage: 4 of 10 connectors", { exact: true })).toBeVisible();
    await expect(page.getByRole("listitem").filter({ hasText: "Finance reporting" })).toContainText("Failed · Sign-in rejected");
    await expect(page.getByRole("listitem").filter({ hasText: "Marketing analytics" }).getByRole("link", { name: "Choose tables" })).toBeVisible();
    await expect(page.getByRole("button", { name: /^Edit / })).toHaveCount(0);

    await search.fill("Snowflake");
    await expect(page.getByText("1 matching · Plan usage: 4 of 10 connectors", { exact: true })).toBeVisible();
    await expect(page.getByRole("button", { name: "View Finance reporting connector" })).toBeVisible();
    await expect(page.getByRole("button", { name: "View Marketing analytics connector" })).toHaveCount(0);

    await search.fill("");
    await page.getByRole("button", { name: "View Northstar warehouse connector" }).click();
    const dialog = page.getByRole("dialog", { name: "Northstar warehouse" });
    await expect(dialog).toBeVisible();
    await expect(dialog.getByRole("button", { name: "Edit settings" })).toBeVisible();
    await expect(dialog.getByRole("button", { name: "Replace credentials" })).toBeVisible();
    await expect(dialog.getByRole("link", { name: "Browse in Catalog" })).toBeVisible();
    await dialog.getByRole("button", { name: "Close connector details" }).click();

    await page.screenshot({ path: evidencePath("screenshots/connectors-library-list-demo.png"), fullPage: false });
  });

  test("Connectors library keeps table and refresh status on narrow screens", async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    await page.goto("/dev/connectors-demo");

    const northstarRow = page.getByRole("listitem").filter({ hasText: "Northstar warehouse" });
    await expect(northstarRow.getByRole("button", { name: "View Northstar warehouse connector" })).toBeVisible();
    await expect(northstarRow.getByText("142 tables", { exact: true })).toBeVisible();

    await page.getByRole("button", { name: "View Finance reporting connector" }).click();
    const dialog = page.getByRole("dialog", { name: "Finance reporting" });
    const title = dialog.getByRole("heading", { name: "Finance reporting" });
    await expect(title).toBeVisible();
    expect(await title.evaluate((element) => element.scrollWidth <= element.clientWidth)).toBe(true);
    const narrowActions = dialog.getByTestId("connector-detail-actions-narrow");
    await expect(narrowActions.getByRole("button", { name: "Edit settings" })).toBeVisible();
    await expect(narrowActions.getByRole("button", { name: "Replace credentials" })).toBeVisible();
    await expect(narrowActions.getByRole("link", { name: "Browse in Catalog" })).toBeVisible();
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
    await page.screenshot({ path: evidencePath("screenshots/joo-91-connector-detail-mobile.png") });
  });

  test("settings and complete credential rotation are separate actions", async ({ page }) => {
    await page.goto("/dev/connectors-demo");
    await page.getByRole("button", { name: "View Northstar warehouse connector" }).click();
    await page.getByRole("button", { name: "Edit settings", exact: true }).click();
    await expect(page.getByRole("note")).toContainText("Explorers and their Agents can modify data");
    await page.screenshot({ path: evidencePath("screenshots/native-sql-connector-edit.png") });
    await expect(page.getByLabel("Database", { exact: true })).toBeDisabled();
    await expect(page.getByLabel("Password", { exact: true })).toHaveCount(0);
    await page.getByLabel("Display name").fill("Updated warehouse");
    await page.getByRole("button", { name: "Save changes" }).click();
    await expect(page.getByRole("dialog", { name: "Updated warehouse" })).toBeVisible();
    await page.getByRole("button", { name: "Replace credentials", exact: true }).click();
    await expect(page.getByRole("note")).toContainText("Monomer does not guarantee read-only access.");
    await expect(page.getByLabel("Display name")).toHaveCount(0);
    await expect(page.getByLabel("Username", { exact: true })).toHaveValue("");
    await expect(page.getByLabel("Password", { exact: true })).toHaveValue("");
    await page.getByRole("button", { name: "Replace credentials", exact: true }).click();
    await expect(page.getByLabel("Username", { exact: true })).toHaveAccessibleDescription("Username is required.");
    await expect(page.getByLabel("Password", { exact: true })).toHaveAccessibleDescription("Password is required.");
    await page.screenshot({ path: evidencePath("screenshots/connectors-rotation-validation.png") });
    await page.getByLabel("Username", { exact: true }).fill("fixture-reader");
    await page.getByLabel("Password", { exact: true }).fill("fixture-only");
    await page.getByRole("button", { name: "Replace credentials", exact: true }).click();
    await expect(page.getByRole("dialog", { name: "Updated warehouse" })).toBeVisible();
    await page.getByRole("button", { name: "Replace credentials", exact: true }).click();
    await expect(page.getByLabel("Username", { exact: true })).toHaveValue("");
    await expect(page.getByLabel("Password", { exact: true })).toHaveValue("");
    await page.setViewportSize({ width: 390, height: 844 });
    await expect(page.getByRole("note")).toBeInViewport();
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
    await page.screenshot({ path: evidencePath("screenshots/connectors-rotation-mobile.png") });
  });

  test("create displays every missing field and preserves unrelated errors while correcting", async ({ page }) => {
    await page.goto("/dev/connectors-demo");
    await page.getByRole("button", { name: "Create connector", exact: true }).click();
    await page.getByRole("button", { name: "Next", exact: true }).click();
    const dialog = page.getByRole("dialog");
    await expect(dialog.getByRole("note")).toContainText("Use a dedicated read-only warehouse account.");
    await page.screenshot({ path: evidencePath("screenshots/native-sql-connector-create.png") });
    await dialog.getByRole("button", { name: "Create", exact: true }).click();
    await expect(dialog.getByLabel("Port", { exact: true })).toHaveValue("5432");
    for (const label of ["Display name", "Host", "Database", "Username", "Password"]) {
      const field = dialog.getByLabel(label, { exact: true });
      await expect(field).toHaveAttribute("aria-invalid", "true");
      await expect(field).toHaveAccessibleDescription(`${label} is required.`);
    }
    await page.screenshot({ path: evidencePath("screenshots/joo-23-all-required-desktop.png") });
    await dialog.getByText("Password is required.", { exact: true }).scrollIntoViewIfNeeded();
    await page.screenshot({ path: evidencePath("screenshots/joo-23-all-required-desktop-bottom.png") });
    await dialog.getByLabel("Display name", { exact: true }).fill("Fixture warehouse");
    await expect(dialog.getByLabel("Display name", { exact: true })).toHaveAttribute("aria-invalid", "false");
    await expect(dialog.locator('[aria-invalid="true"]')).toHaveCount(4);
    await dialog.getByLabel("Port", { exact: true }).fill("65536");
    await dialog.getByRole("button", { name: "Create", exact: true }).click();
    await expect(dialog.getByLabel("Port", { exact: true })).toHaveAccessibleDescription("Port must be an integer from 1 to 65535.");
    await expect(dialog.getByLabel("Password", { exact: true })).toHaveAccessibleDescription("Password is required.");
    await page.setViewportSize({ width: 390, height: 844 });
    await dialog.getByLabel("Password", { exact: true }).scrollIntoViewIfNeeded();
    await expect(dialog.getByRole("button", { name: "Create", exact: true })).toBeInViewport();
    await page.screenshot({ path: evidencePath("screenshots/joo-23-all-required-mobile.png") });
    await dialog.getByRole("button", { name: "Back", exact: true }).click();
    await dialog.getByText("Trino", { exact: true }).click();
    await expect(dialog.getByRole("radio", { name: "Trino", exact: true })).toBeChecked();
    await dialog.getByRole("button", { name: "Next", exact: true }).click();
    await expect(dialog.locator('[aria-invalid="true"]')).toHaveCount(0);
    await expect(dialog.getByLabel("SSL", { exact: true }).locator("option")).toHaveText(["Select…", "Enabled", "Disabled"]);
    await dialog.getByLabel("SSL", { exact: true }).selectOption({ label: "Disabled" });
    await expect(dialog.getByLabel("SSL", { exact: true })).toHaveValue("false");
    await page.setViewportSize({ width: 1440, height: 960 });
    await page.screenshot({ path: evidencePath("screenshots/joo-23-trino-ssl.png") });
    await dialog.getByRole("button", { name: "Back", exact: true }).click();
    await dialog.getByText("Databricks", { exact: true }).click();
    await expect(dialog.getByRole("radio", { name: "Databricks", exact: true })).toBeChecked();
    await dialog.getByRole("button", { name: "Next", exact: true }).click();
    await expect(dialog.getByLabel("Host", { exact: true })).toHaveAttribute("placeholder", "dbc-….cloud.databricks.com");
    await page.screenshot({ path: evidencePath("screenshots/joo-23-databricks-host.png") });
    await dialog.getByRole("button", { name: "Create", exact: true }).click();
    await dialog.getByRole("combobox", { name: /^Method/ }).selectOption("OAUTH_CLIENT");
    await expect(dialog.getByLabel("Host", { exact: true })).toHaveAttribute("aria-invalid", "true");
    await expect(dialog.getByLabel("Access token", { exact: true })).toHaveCount(0);
    await dialog.getByRole("button", { name: "Create", exact: true }).click();
    await expect(dialog.getByLabel("OAuth client ID", { exact: true })).toHaveAccessibleDescription("OAuth client ID is required.");
    await expect(dialog.getByLabel("OAuth client secret", { exact: true })).toHaveAccessibleDescription("OAuth client secret is required.");
  });
});
