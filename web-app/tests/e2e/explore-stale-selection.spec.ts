import { mkdirSync } from "node:fs";
import { resolve } from "node:path";
import { expect, test } from "./support/test";
import type { Page, Route } from "@playwright/test";
import { completeOAuthCallback } from "./oauth-callback-correlation";
import { evidencePath } from "./evidence";
import { mockSignedInWorkspace } from "./support/session";

const workspaceId = "019e8000-0000-7000-8000-000000000b21";
const connectorId = "019e8000-0000-7000-8000-000000000c21";
const runAId = "019e8000-0000-7000-8000-000000000d21";
const runBId = "019e8000-0000-7000-8000-000000000d22";
const pollingRunId = "019e8000-0000-7000-8000-000000000d23";
const evidenceDirectory = resolve(process.cwd(), "../testkit/product-chain-qa/remaining/explore-stale");
const screenshotDirectory = evidencePath(resolve(evidenceDirectory, "screenshots"));

function json(body: unknown, status = 200) {
  return {
    status,
    contentType: status >= 400 ? "application/problem+json" : "application/json",
    body: JSON.stringify(body),
  };
}

function runView(runId: string, state: "RUNNING" | "SUCCEEDED", resultValue: string | null) {
  return {
    kind: "SQL",
    runId,
    workspaceId,
    connectorId,
    requestDigest: `sha256:${runId.slice(-4).padStart(64, "a")}`,
    state,
    revision: state === "RUNNING" ? 1 : 2,
    resultAvailable: state === "SUCCEEDED",
    failureCode: null,
    failureDetail: null,
    createdAt: "2026-09-08T00:00:00Z",
    updatedAt: state === "RUNNING" ? "2026-09-08T00:00:01Z" : "2026-09-08T00:00:03Z",
    sql: `SELECT run_${runId.slice(-2)}`,
    resultMetadata: resultValue == null ? null : {
      byteSize: "32",
      mediaType: "application/vnd.apache.arrow.file",
      rowCount: "1",
      columnCount: 1,
      expiresAt: "2026-09-15T00:00:00Z",
    },
    result: resultValue == null ? null : {
      columns: [{ ordinal: 1, name: "value", logicalType: "DECIMAL", nullable: false }],
      rows: [[resultValue]],
      returnedRowCount: 1,
      stopReason: "ROW_LIMIT",
    },
  };
}

async function authenticateExploreMember(page: Page, returnTarget: string): Promise<void> {
  await mockSignedInWorkspace(page, {
    workspaceId,
    workspaceName: "Explore timing QA",
    organizationId: "organization-qa",
    role: "EXPLORER",
    user: { id: "explore-timing-user", email: "explore-timing@example.test", displayName: "Explore timing QA" },
  });
  await completeOAuthCallback(page, { new_user: "false", auth_provider: "google" }, returnTarget);
}

async function fulfillExploreBootstrap(route: Route, path: string): Promise<boolean> {
  if (path.endsWith("/connectors")) {
    await route.fulfill(json({
      connectors: [{
        id: connectorId,
        workspaceId,
        name: "Explore timing connector",
        type: "POSTGRESQL",
        properties: { host: "fixture.internal", port: 5432, database: "warehouse" },
        createdAtEpochMs: 0,
        updatedAtEpochMs: 0,
      }],
    }));
    return true;
  }
  if (path.endsWith("/semantic/metrics")) {
    await route.fulfill(json([]));
    return true;
  }
  if (path.endsWith("/semantic/semantic-models")) {
    await route.fulfill(json([]));
    return true;
  }
  if (path.endsWith("/semantic/query/group-bys")) {
    await route.fulfill(json({ groupBys: [], excludedSemanticModels: [], dimensionFilterTypes: [] }));
    return true;
  }
  return false;
}

function accessDenied(path: string) {
  return json({
    type: "https://monomer.cc/problems/explore-access-denied",
    title: "Explore result access denied",
    status: 403,
    detail: "The stale run response was denied.",
    instance: path,
    code: "ACCESS_DENIED",
    requestId: "explore-timing-request",
    traceId: "trace-explore-timing-stale",
    retryable: false,
  }, 403);
}

test.beforeAll(() => {
  mkdirSync(screenshotDirectory, { recursive: true });
});

for (const lateRunAResponse of ["DENIED", "SUCCEEDED"] as const) {
  const scenarioLabel = lateRunAResponse.toLowerCase();

  test(`keeps run B selected when a held run A read returns late ${scenarioLabel}, including reload recovery`, async ({ page, consoleErrors }) => {
    if (lateRunAResponse === "DENIED") consoleErrors.allow({ status: 403, url: /\/explore\/runs\/[^/]+$/, reason: "The fixture denies the held run A read." });
    await page.setViewportSize({ width: 1440, height: 900 });
    let releaseRunA!: () => void;
    const runAReleased = new Promise<void>((resolveRelease) => { releaseRunA = resolveRelease; });
    let runAReadStarted = false;
    let runAReadCount = 0;
    let runBReadCount = 0;

    await page.route("**/v2/workspaces/**", async (route: Route) => {
      const path = new URL(route.request().url()).pathname;
      if (await fulfillExploreBootstrap(route, path)) return;
      if (path.endsWith("/explore/runs")) {
        await route.fulfill(json({ runs: [runView(runAId, "SUCCEEDED", "12.50"), runView(runBId, "SUCCEEDED", "98.75")] }));
        return;
      }
      if (path.endsWith(`/explore/runs/${runAId}`)) {
        runAReadCount += 1;
        if (runAReadCount === 1) {
          runAReadStarted = true;
          await runAReleased;
        }
        if (lateRunAResponse === "DENIED") {
          await route.fulfill(accessDenied(path));
        } else {
          await route.fulfill(json(runView(runAId, "SUCCEEDED", "12.50")));
        }
        return;
      }
      if (path.endsWith(`/explore/runs/${runBId}`)) {
        runBReadCount += 1;
        await route.fulfill(json(runView(runBId, "SUCCEEDED", "98.75")));
        return;
      }
      throw new Error(`Unexpected Explore fixture route: ${path}`);
    });

    try {
      await authenticateExploreMember(page, `/workspaces/${workspaceId}/explore?run=${runAId}`);
      await expect.poll(() => runAReadStarted).toBe(true);

      const runs = page.getByRole("complementary", { name: "Explore runs" });
      await expect(runs).toBeVisible();
      await runs.getByRole("button", { name: /SELECT run_22/ }).click();
      await expect(page).toHaveURL(new RegExp(`/workspaces/${workspaceId}/explore\\?run=${runBId}$`));
      await expect(page.getByRole("region", { name: "SELECT run_22" })).toBeVisible();
      await expect(page.getByTestId("run-sample-grid")).toContainText("98.75");
      await expect(page.getByTestId("run-sample-grid")).not.toContainText("12.50");
      await expect.poll(() => runBReadCount).toBe(1);

      const staleRunAResponsePromise = page.waitForResponse((response) => {
        const responsePath = new URL(response.url()).pathname;
        return responsePath.endsWith(`/explore/runs/${runAId}`)
          && response.status() === (lateRunAResponse === "DENIED" ? 403 : 200);
      });
      releaseRunA();
      const staleRunAResponse = await staleRunAResponsePromise;
      await staleRunAResponse.finished();
      const staleRunAPayload = await staleRunAResponse.json() as { runId?: string; code?: string; result?: { rows?: unknown[][] } | null };
      expect(staleRunAPayload.runId).toBe(lateRunAResponse === "DENIED" ? undefined : runAId);
      if (lateRunAResponse === "DENIED") {
        expect(staleRunAPayload.code).toBe("ACCESS_DENIED");
      } else {
        expect(staleRunAPayload.result?.rows?.[0]?.[0]).toBe("12.50");
      }
      await expect.poll(() => runAReadCount).toBe(1);
      await expect(page).toHaveURL(new RegExp(`/workspaces/${workspaceId}/explore\\?run=${runBId}$`));
      await expect(page.getByTestId("run-sample-grid")).toContainText("98.75");
      await expect(page.getByTestId("run-sample-grid")).not.toContainText("12.50");
      await expect(page.getByRole("alert")).toHaveCount(0);
      await expect(page.getByRole("region", { name: "SELECT run_22" })).toBeVisible();
      await page.screenshot({ path: resolve(screenshotDirectory, `EXP-16-selection-${scenarioLabel}-desktop.png`), fullPage: true });

      await page.setViewportSize({ width: 390, height: 844 });
      await page.reload();
      await expect(page).toHaveURL(new RegExp(`/workspaces/${workspaceId}/explore\\?run=${runBId}$`));
      await expect(page.getByRole("region", { name: "SELECT run_22" })).toBeVisible();
      await expect(page.getByTestId("run-sample-grid")).toContainText("98.75");
      await expect(page.getByTestId("run-sample-grid")).not.toContainText("12.50");
      expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBe(390);
      await page.screenshot({ path: resolve(screenshotDirectory, `EXP-16-selection-${scenarioLabel}-mobile.png`), fullPage: true });
    } finally {
      releaseRunA();
    }
  });
}

test("stops selected and recent run polling after a RUNNING run becomes terminal", async ({ page }) => {
  await page.clock.install();
  await page.setViewportSize({ width: 1440, height: 900 });
  let detailReads = 0;
  let recentReads = 0;
  let recentTerminalReads = 0;

  await page.route("**/v2/workspaces/**", async (route: Route) => {
    const path = new URL(route.request().url()).pathname;
    if (await fulfillExploreBootstrap(route, path)) return;
    if (path.endsWith("/explore/runs")) {
      recentReads += 1;
      const terminal = detailReads >= 2;
      if (terminal) recentTerminalReads += 1;
      await route.fulfill(json({ runs: [runView(pollingRunId, terminal ? "SUCCEEDED" : "RUNNING", terminal ? "17.25" : null)] }));
      return;
    }
    if (path.endsWith(`/explore/runs/${pollingRunId}`)) {
      detailReads += 1;
      await route.fulfill(json(runView(pollingRunId, detailReads >= 2 ? "SUCCEEDED" : "RUNNING", detailReads >= 2 ? "17.25" : null)));
      return;
    }
    throw new Error(`Unexpected Explore fixture route: ${path}`);
  });

  await authenticateExploreMember(page, `/workspaces/${workspaceId}/explore?run=${pollingRunId}`);
  await expect(page.getByRole("region", { name: "SELECT run_23" })).toBeVisible();
  await expect(page.getByRole("button", { name: "Cancel", exact: true })).toBeVisible();
  await expect.poll(() => detailReads).toBeGreaterThanOrEqual(1);
  await expect.poll(() => detailReads).toBe(2);
  await expect(page.getByTestId("run-sample-grid")).toContainText("17.25");
  await expect(page.getByRole("button", { name: "Cancel", exact: true })).toHaveCount(0);
  await expect.poll(() => recentTerminalReads).toBeGreaterThanOrEqual(1);

  const settledDetailReads = detailReads;
  const settledRecentReads = recentReads;
  await page.clock.fastForward(2_300);
  expect(detailReads).toBe(settledDetailReads);
  expect(recentReads).toBe(settledRecentReads);
  await page.screenshot({ path: resolve(screenshotDirectory, "EXP-16-terminal-polling-desktop.png"), fullPage: true });
});
