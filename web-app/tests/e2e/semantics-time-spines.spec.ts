import { expect, test } from "./support/test";
import { evidencePath } from "./evidence";
import { completeOAuthCallback } from "./oauth-callback-correlation";
import { json, mockSignedInWorkspace } from "./support/session";
import type { TimeSpineDefinition } from "../../src/lib/types/time-spine";

const READY_TIME_SPINES =
  "/dev/semantics-demo?scene=ready&view=setup&connectorId=71000000-0000-0000-0000-000000000301";

test("inspects Connector-owned Calendar tables before entering edit on desktop", async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  await page.goto(READY_TIME_SPINES);

  await expect(page.getByRole("tab", { name: "Setup" })).toHaveAttribute("aria-selected", "true");
  await expect(page.getByRole("region", { name: "Connectors and Calendar tables" })).toContainText(
    "Warehouse production",
  );

  const inspector = page.getByRole("complementary", { name: "Calendar table details" });
  await expect(inspector).toContainText("Configured Calendar tables");
  await expect(inspector).toContainText("analytics.shared.dim_date");
  await expect(inspector).toContainText("Primary column · date_day");
  await expect(inspector.getByRole("textbox", { name: "Primary column" })).toHaveCount(0);

  await expect(inspector).toContainText("This metric needs a time spine.");

  await inspector.getByRole("button", { name: "Configure Calendar tables for Warehouse production" }).click();
  // The fixture has no Catalog session, so a configured table falls back to typed names.
  await expect(inspector.getByText("Catalog has no columns for this table.", { exact: false }).first()).toBeVisible();
  await expect(inspector.getByRole("textbox", { name: "Table" }).first()).toHaveValue("dim_date");
  await expect(inspector.getByRole("textbox", { name: "Primary column" }).first()).toHaveValue("date_day");
  await expect(inspector.getByRole("combobox", { name: "Grain", exact: true }).first()).toHaveValue("day");
  await expect(inspector.getByRole("button", { name: "Save" })).toBeVisible();

  await page.screenshot({ path: evidencePath("../screenshots/semantics-time-spines-desktop.png"), fullPage: false });
});

test("keeps area navigation and search available while Calendar tables load", async ({ page }) => {
  await page.goto("/dev/semantics-demo?scene=loading&view=setup");

  await expect(page.getByLabel("Calendar tables loading")).toBeVisible();
  await expect(page.getByRole("tab", { name: "Metrics" })).toBeVisible();
  await expect(page.getByRole("searchbox", { name: "Search calendar tables" })).toBeVisible();
  await page.getByRole("link", { name: "ready" }).click();
  await expect(page.getByRole("button", { name: /Warehouse production/ })).toBeVisible();
});

test("uses a read-only drawer then explicit edit on narrow screens", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto("/dev/semantics-demo?scene=ready&view=setup");

  await page.getByRole("button", { name: /Lakehouse/ }).click();
  const drawer = page.getByRole("dialog", { name: "Calendar table details" });
  await expect(drawer).toBeVisible();
  await expect(drawer).toContainText("No Calendar table is configured for this connector.");
  await drawer.getByRole("button", { name: "Configure Calendar tables", exact: true }).click();
  await expect(drawer.getByText("No calendar tables in this draft.", { exact: false })).toBeVisible();
  await drawer.getByRole("button", { name: "Add Calendar table" }).click();
  const row = drawer.locator("fieldset").filter({ hasText: "Calendar table 1" });
  await expect(row).toBeVisible();
  await expect(row.getByRole("button", { name: "Choose table for Calendar table 1" })).toBeVisible();
  await expect(row.getByRole("combobox", { name: "Primary column" })).toBeDisabled();
  await row.getByRole("button", { name: "Type the table name" }).click();
  await expect(row.getByRole("textbox", { name: "Table" })).toBeVisible();

  await page.screenshot({ path: evidencePath("../screenshots/semantics-time-spines-mobile.png"), fullPage: false });
});

test("keeps scoped Calendar table navigation visible when loading fails", async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  await page.goto("/dev/semantics-demo?scene=error&view=setup");

  await expect(page.getByRole("tab", { name: "Setup" })).toHaveAttribute("aria-selected", "true");
  await expect(page.getByRole("searchbox", { name: "Search calendar tables" })).toBeVisible();
  const alert = page.getByRole("alert");
  await expect(alert).toContainText("Unable to load Calendar tables");
  await expect(alert).toContainText("Fixture service unavailable");
  await expect(page.getByRole("region", { name: "Connectors and Calendar tables" })).toHaveCount(0);

  await page.screenshot({ path: evidencePath("../screenshots/qa-product-chain/semantic-explore/SM-error-time-spines.png"), fullPage: false });
});

for (const width of [1440, 390]) {
  test(`preserves a rejected Calendar table draft and shows its exact field at ${width}px`, async ({ page, consoleErrors }) => {
    const workspaceId = "019e8000-0000-7000-8000-000000000d25";
    const connectorId = "019e8000-0000-7000-8000-000000000d26";
    const semanticPath = `/v2/workspaces/${workspaceId}/semantic`;
    let definition: TimeSpineDefinition = {
      schemaVersion: 1,
      timeSpines: ["day", "week"].map((granularity) => ({
        relation: { schema: "analytics", relation: "calendar" },
        primaryColumn: { name: "day", granularity: granularity as "day" | "week" },
      })),
    };
    let writes = 0;
    await mockSignedInWorkspace(page, {
      workspaceId, workspaceName: "Calendar table validation", organizationId: "organization-time-spine-validation",
      role: "EDITOR", user: { id: "time-spine-editor", email: "spine@example.test", displayName: "Spine editor" },
    });
    consoleErrors.allow({ status: 400, url: /\/semantic\/time-spines\//, reason: "The fixture rejects a noncanonical schema with its known field." });
    await page.route("**/v2/workspaces/**", async (route) => {
      const path = new URL(route.request().url()).pathname;
      if (path === `/v2/workspaces/${workspaceId}/connectors`) return json(route, { connectors: [{
        id: connectorId, workspaceId, name: "Warehouse", type: "POSTGRESQL",
        properties: { host: "warehouse.internal", port: 5432, database: "warehouse" },
        createdAtEpochMs: 0, updatedAtEpochMs: 0,
      }] });
      if (path === `${semanticPath}/semantic-models` || path === `${semanticPath}/metrics`) return json(route, []);
      if (path === `${semanticPath}/time-spines`) return json(route, [{ connectorId, definition }]);
      if (path.includes("/catalog/")) return json(route, { requiresPartitionFilter: null,
        name: "calendar", schemaName: "analytics", catalogName: null, kind: "TABLE", columns: [],
        partitionKeys: [], clusteringKeys: [], foreignKeys: [],
      });
      if (path === `${semanticPath}/time-spines/${connectorId}` && route.request().method() === "PUT") {
        writes += 1;
        const draft = route.request().postDataJSON() as TimeSpineDefinition;
        const invalid = draft.timeSpines.findIndex((spine) => spine.relation.schema.trim() !== spine.relation.schema);
        if (invalid >= 0) return route.fulfill({
          status: 400, contentType: "application/problem+json", body: JSON.stringify({
            type: "https://monomer.cc/problems/semantic-definition-invalid", title: "Bad Request", status: 400,
            code: "SEMANTIC_DEFINITION_INVALID", detail: "Semantic time spine schema must be a canonical identifier",
            instance: path, requestId: "time-spine-invalid-schema", traceId: "time-spine-invalid-schema", retryable: false,
            violations: [{ field: `timeSpines[${invalid}].relation.schema`, code: "SEMANTIC_DEFINITION_INVALID", message: "Semantic time spine schema must be a canonical identifier" }],
          }),
        });
        definition = draft;
        return json(route, { connectorId, definition });
      }
      throw new Error(`Unexpected Calendar table fixture request: ${route.request().method()} ${path}`);
    });
    await page.setViewportSize({ width, height: 900 });
    await completeOAuthCallback(page, { new_user: "false", auth_provider: "google" }, `/workspaces/${workspaceId}/semantics?view=setup&connectorId=${connectorId}`);
    if (width < 1280) {
      await page.getByRole("region", { name: "Connectors and Calendar tables" })
        .getByRole("button", { name: "Warehouse PostgreSQL 2 configured", exact: true }).click();
    }
    const inspector = width >= 1280
      ? page.getByRole("complementary", { name: "Calendar table details" })
      : page.getByRole("dialog", { name: "Calendar table details" });
    await inspector.getByRole("button", { name: "Configure Calendar tables for Warehouse" }).click();
    let rows = inspector.locator("fieldset");
    await rows.nth(1).getByRole("button", { name: "Type the table name" }).click();
    const schema = rows.nth(1).getByRole("textbox", { name: "Schema", exact: true });
    await schema.fill(" analytics");
    await inspector.getByRole("button", { name: "Save", exact: true }).click();
    await expect(schema).toHaveValue(" analytics");
    await expect(schema).toHaveAttribute("aria-invalid", "true");
    await expect(schema).toHaveAccessibleDescription("Semantic time spine schema must be a canonical identifier");
    await expect(rows.nth(0).getByText("Semantic time spine schema must be a canonical identifier")).toHaveCount(0);
    expect(writes).toBe(1);
    expect(definition.timeSpines[1].relation.schema).toBe("analytics");
    await page.screenshot({ path: evidencePath(`../screenshots/time-spine-field-error-${width}.png`) });
    await inspector.getByRole("button", { name: "Back to Calendar table details" }).click();
    await expect(inspector).toContainText("analytics.calendar");
    await expect(inspector).not.toContainText("Semantic time spine schema must be a canonical identifier");
    await inspector.getByRole("button", { name: "Configure Calendar tables for Warehouse" }).click();
    rows = inspector.locator("fieldset");
    await rows.nth(1).getByRole("button", { name: "Type the table name" }).click();
    await rows.nth(1).getByRole("textbox", { name: "Schema", exact: true }).fill(" analytics");
    await inspector.getByRole("button", { name: "Save", exact: true }).click();
    await expect(rows.nth(1).getByRole("textbox", { name: "Schema", exact: true })).toHaveAttribute("aria-invalid", "true");
    await rows.nth(0).getByRole("button", { name: "Remove Calendar table 1" }).click();
    await expect(inspector).not.toContainText("Semantic time spine schema must be a canonical identifier");
    await expect(rows.nth(0).getByRole("textbox", { name: "Schema", exact: true })).toHaveValue(" analytics");
    await rows.nth(0).getByRole("textbox", { name: "Schema", exact: true }).fill("analytics");
    await inspector.getByRole("button", { name: "Save", exact: true }).click();
    await expect(inspector).toContainText("Configured Calendar tables");
    expect(definition.timeSpines).toHaveLength(1);
    expect(definition.timeSpines[0].primaryColumn.granularity).toBe("week");
    expect(writes).toBe(3);
  });
}
