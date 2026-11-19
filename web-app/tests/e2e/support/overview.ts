import type { Page } from "@playwright/test";
import { json, problem } from "./session";
import type { ConsoleErrors } from "./test";

// Overview computes Needs attention and Get started from these owner reads. Specs that open
// Overview answer them here and override only what their scenario needs.
export interface OverviewSourcesFixture {
  connectors?: unknown[];
  connectorSummaries?: unknown[];
  exploreRuns?: unknown[];
  dashboards?: unknown[];
  semanticModels?: unknown[];
  metrics?: unknown[];
  // The viewer's own agent connections, which decide the personal Get started agent step.
  agentConnections?: unknown[];
}

export async function mockAbsentSampleWorkspace(page: Page, organizationId: string, consoleErrors: ConsoleErrors): Promise<void> {
  let observed = false;
  await page.route(`**/v2/organizations/${organizationId}/sample-workspace`, (route) => {
    if (!observed) {
      observed = true;
      consoleErrors.allow({ status: 404, url: /\/sample-workspace$/, reason: "Sample discovery returns the declared 404 when this fixture Organization has no sample Workspace." });
    }
    return problem(route, 404, "NOT_FOUND", "No sample workspace exists.", "Not Found");
  });
}

export async function mockOverviewSources(page: Page, workspaceId: string, fixture: OverviewSourcesFixture = {}): Promise<void> {
  const workspace = `**/v2/workspaces/${workspaceId}`;
  await page.route(`${workspace}/connectors`, (route) => json(route, { connectors: fixture.connectors ?? [] }));
  await page.route(`${workspace}/catalog/connector-summaries`, (route) => json(route, { connectors: fixture.connectorSummaries ?? [] }));
  await page.route(`${workspace}/explore/runs`, (route) => json(route, { runs: fixture.exploreRuns ?? [] }));
  await page.route(`${workspace}/dashboards`, (route) => json(route, { items: fixture.dashboards ?? [] }));
  await page.route(`${workspace}/semantic/semantic-models`, (route) => json(route, fixture.semanticModels ?? []));
  await page.route(`${workspace}/semantic/metrics`, (route) => json(route, fixture.metrics ?? []));
  await page.route("**/oauth/connections?**", (route) => json(route, { connections: fixture.agentConnections ?? [], nextCursor: null }));
}
