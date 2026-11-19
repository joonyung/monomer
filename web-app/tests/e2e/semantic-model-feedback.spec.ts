import { expect, test } from "./support/test";
import type { Page } from "@playwright/test";
import { completeOAuthCallback } from "./oauth-callback-correlation";
import { mockSignedInWorkspace } from "./support/session";
import { evidencePath } from "./evidence";

const workspaceId = "019e8000-0000-7000-8000-000000000b25";
const principalId = "019e8000-0000-7000-8000-000000000b26";
const json = (value: unknown) => ({ status: 200, contentType: "application/json", body: JSON.stringify(value) });

async function openEditor(page: Page, connectorState: "empty" | "loading" | "error" = "empty") {
  await mockSignedInWorkspace(page, {
    workspaceId,
    workspaceName: "Semantic feedback",
    organizationId: "organization-1",
    role: "EDITOR",
    user: { id: principalId, email: "semantic@example.test", displayName: "Semantic editor" },
  });
  await page.route("**/v2/workspaces/*/connectors", async (route) => {
    if (connectorState === "loading") return;
    if (connectorState === "error") return route.fulfill({ status: 503, contentType: "application/json", body: JSON.stringify({ message: "Connectors unavailable" }) });
    return route.fulfill(json({ connectors: [] }));
  });
  await page.route("**/v2/workspaces/*/semantic/**", (route) => route.fulfill(json([])));
  await page.route("**/v2/workspaces/*/semantic/check", (route) => route.fulfill(json({ partitionFilters: [], ok: false, runsOn: [], preview: null, problems: [] })));
  await completeOAuthCallback(page, { new_user: "false", auth_provider: "google" }, `/workspaces/${workspaceId}/semantic-models/new`);
  await expect(page.getByRole("heading", { name: "New model", exact: true })).toBeVisible();
}

for (const width of [1440, 390]) {
  test(`separates save errors and explains the connector prerequisite at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    await openEditor(page);
    await expect(page.getByRole("button", { name: "Choose source table" })).toHaveCount(0);
    await expect(page.getByText("Add a connector before choosing a source table.")).toBeVisible();
    await expect(page.getByRole("link", { name: "Go to Connectors" })).toHaveAttribute("href", `/workspaces/${workspaceId}/connectors`);
    await expect(page.getByRole("button", { name: "Add calculated field", exact: true })).toBeVisible();
    await expect(page.getByText(/authoring address|sent as SQL strings|snake_case/)).toHaveCount(0);
    await page.getByRole("link", { name: "Go to Connectors" }).scrollIntoViewIfNeeded();
    await page.screenshot({ path: evidencePath(`../screenshots/joo-25-connector-guidance-${width}.png`) });
    await page.getByRole("button", { name: "Save", exact: true }).click();
    const errors = page.getByRole("alert", { name: "Model validation errors" });
    await expect(errors).toBeFocused();
    await expect(errors.getByRole("listitem")).toHaveText([
      "Name: enter a name.",
      "Source: add a source table.",
      "Fields: define exactly one Row key.",
    ]);
    await expect(page.getByRole("textbox", { name: "Name", exact: false }).first()).toHaveAttribute("aria-invalid", "true");
    await page.screenshot({ path: evidencePath(`../screenshots/joo-25-editor-errors-${width}.png`) });
    await expect(page.getByText("Enter a name.", { exact: true })).toBeVisible();
    await page.getByPlaceholder("e.g. orders", { exact: true }).fill("Orders");
    await expect(page.getByText("Use lowercase letters, numbers, and underscores, starting with a letter (e.g. orders).", { exact: true })).toBeVisible();
    await page.getByPlaceholder("e.g. orders", { exact: true }).fill("orders");
    await expect(errors.getByRole("listitem")).toHaveCount(2);
    await expect(page.getByPlaceholder("e.g. orders", { exact: true })).toHaveAttribute("aria-invalid", "false");
  });
}

for (const connectorState of ["loading", "error"] as const) {
  test(`does not report missing connectors while the request is ${connectorState}`, async ({ page, consoleErrors }) => {
    if (connectorState === "error") consoleErrors.allow({ status: 503, url: /\/connectors$/, reason: "The fixture makes the connector list unavailable." });
    await openEditor(page, connectorState);
    await expect(page.getByText(connectorState === "loading" ? "Loading connectors…" : "Connectors could not be loaded. Try again.")).toBeVisible({ timeout: 15000 });
    await expect(page.getByText("Add a connector before choosing a source table.")).toHaveCount(0);
  });
}
