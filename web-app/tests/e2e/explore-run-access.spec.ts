import { mkdirSync } from "node:fs";
import { expect, test } from "./support/test";
import type { Page, Route } from "@playwright/test";
import { completeOAuthCallback } from "./oauth-callback-correlation";
import { evidencePath } from "./evidence";
import { mockSignedInWorkspace } from "./support/session";

const workspaceId = "019e8000-0000-7000-8000-000000000b01";
const connectorId = "019e8000-0000-7000-8000-000000000c01";
const runId = "019e8000-0000-7000-8000-000000000d01";
const alternateRunId = "019e8000-0000-7000-8000-000000000d02";
const runUrl = `/workspaces/${workspaceId}/explore?run=${runId}`;

const runView = {
  kind: "SQL",
  runId,
  workspaceId,
  connectorId,
  requestDigest: "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
  state: "SUCCEEDED",
  revision: 1,
  resultAvailable: true,
  failureCode: null,
  failureDetail: null,
  createdAt: "2026-09-07T00:00:00Z",
  updatedAt: "2026-09-07T00:00:04Z",
  sql: "SELECT 12.50 AS revenue",
  resultMetadata: {
    byteSize: "128",
    mediaType: "application/vnd.apache.arrow.file",
    rowCount: "1",
    columnCount: 1,
    expiresAt: "2026-09-14T00:00:00Z",
  },
  resultState: "AVAILABLE",
  result: {
    columns: [{ ordinal: 1, name: "revenue", logicalType: "DECIMAL", nullable: false }],
    rows: [["12.50"]],
    returnedRowCount: 1,
    stopReason: "ROW_LIMIT",
  },
};

const alternateRunView = {
  ...runView,
  runId: alternateRunId,
  sql: "SELECT 1 AS revenue",
  resultAvailable: false,
  resultMetadata: null,
  resultState: "NONE",
  result: null,
};

function json(body: unknown, status = 200) {
  return {
    status,
    contentType: status >= 400 ? "application/problem+json" : "application/json",
    body: JSON.stringify(body),
  };
}

async function authenticateExploreMember(page: Page): Promise<void> {
  await mockSignedInWorkspace(page, {
    workspaceId,
    workspaceName: "Explore QA Workspace",
    organizationId: "organization-1",
    role: "EXPLORER",
    user: { id: "explore-qa-user", email: "explore-qa@example.test", displayName: "Explore QA" },
  });
  await completeOAuthCallback(page, { new_user: "false", auth_provider: "google" }, runUrl);
}

test.beforeAll(() => {
  mkdirSync(evidencePath("screenshots"), { recursive: true });
});

test("removes a cached Explore result when the selected run read is denied", async ({ page, consoleErrors }) => {
  consoleErrors.allow({ status: 403, url: /\/explore\/runs\/[^/]+$/, reason: "The fixture denies the second run read." });
  let runReads = 0;

  await page.addInitScript(() => {
    const nativeNow = Date.now;
    (window as typeof window & { advanceQueryClock: () => void }).advanceQueryClock = () => {
      Object.defineProperty(Date, "now", { value: () => nativeNow() + 31_000 });
    };
  });

  await page.route("**/v2/workspaces/**", async (route: Route) => {
    const requestUrl = new URL(route.request().url());
    const path = requestUrl.pathname;
    if (path.endsWith("/connectors")) {
      await route.fulfill(json({
        connectors: [{
          id: connectorId,
          workspaceId,
          name: "Explore QA Connector",
          type: "POSTGRESQL",
          properties: { host: "fixture.internal", port: 5432, database: "warehouse" },
          createdAtEpochMs: 0,
          updatedAtEpochMs: 0,
        }],
      }));
      return;
    }
    if (path.endsWith("/semantic/metrics")) {
      await route.fulfill(json([{ sourcePartitionInfo: [],
        metricId: "metric-1",
        workspaceId,
        definition: {
          schemaVersion: 3,
          name: "revenue",
          label: "Revenue",
          type: "simple",
          semanticModelName: "orders",
          measure: "revenue",
        },
        createdAt: "2026-09-07T00:00:00Z",
        updatedAt: "2026-09-07T00:00:00Z",
      }]));
      return;
    }
    if (path.endsWith("/semantic/semantic-models")) {
      await route.fulfill(json([{
        id: "model-1",
        name: "orders",
        sourceRelations: [],
        entities: [],
        dimensions: [],
        measures: [],
        createdAt: "2026-09-07T00:00:00Z",
        updatedAt: "2026-09-07T00:00:00Z",
      }]));
      return;
    }
    if (path.endsWith("/explore/runs")) {
      await route.fulfill(json({ runs: [{ ...runView, result: null }, alternateRunView] }));
      return;
    }
    if (path.endsWith(`/explore/runs/${alternateRunId}`)) {
      await route.fulfill(json(alternateRunView));
      return;
    }
    if (path.endsWith(`/explore/runs/${runId}`)) {
      runReads += 1;
      if (runReads === 1) {
        await route.fulfill(json(runView));
        return;
      }
      await route.fulfill(json({
        type: "https://monomer.cc/problems/explore-access-denied",
        title: "Explore result access denied",
        status: 403,
        detail: "Explore result is not authorized.",
        instance: path,
        code: "ACCESS_DENIED",
        requestId: "explore-qa-request",
        traceId: "trace-explore-qa-read",
        retryable: false,
      }, 403));
      return;
    }
    if (path.endsWith("/semantic/query/group-bys")) {
      await route.fulfill(json({ groupBys: [], excludedSemanticModels: [], dimensionFilterTypes: [] }));
      return;
    }
    await route.fulfill(json({}));
  });

  await authenticateExploreMember(page);
  await expect(page).toHaveURL(new RegExp(`/workspaces/${workspaceId}/explore\\?run=${runId}`));
  const viewed = page.getByRole("region", { name: "SELECT 12.50 AS revenue" });
  await expect(viewed).toBeVisible();
  await expect(page.getByRole("dialog")).toHaveCount(0);
  await expect(page.getByTestId("run-sample-grid")).toContainText("12.50");
  await page.evaluate(() => (window as typeof window & { advanceQueryClock: () => void }).advanceQueryClock());

  const rail = page.getByRole("complementary", { name: "Explore runs" });
  await rail.getByRole("button", { name: /SELECT 1 AS revenue/ }).click();
  await expect(page).toHaveURL(new RegExp(`/workspaces/${workspaceId}/explore\\?run=${alternateRunId}`));
  const noResult = page.getByRole("region", { name: "SELECT 1 AS revenue" });
  await expect(noResult.getByText("This run has no result to preview. Run it again to see one.")).toBeVisible();
  await expect(noResult.getByRole("tablist", { name: "Result view" }).getByRole("tab")).toHaveText(["Table", "SQL"]);
  await expect(page.getByTestId("run-sample-grid")).toHaveCount(0);
  await rail.getByRole("button", { name: /SELECT 12\.50 AS revenue/ }).click();

  await expect.poll(() => runReads).toBe(2);
  await expect(page.getByRole("alert").filter({ hasText: "Explore result is not authorized." })).toBeVisible();
  await expect(page.getByRole("region", { name: "SELECT 12.50 AS revenue" })).toHaveCount(0);
  await expect(page.getByTestId("run-sample-grid")).toHaveCount(0);
  expect(runReads).toBeGreaterThanOrEqual(2);
  await page.screenshot({ path: evidencePath("../screenshots/qa-product-chain/semantic-explore/EXP-PROD-RESULT-ACL.png"), fullPage: true });
});
