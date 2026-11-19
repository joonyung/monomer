import { afterEach, describe, expect, it, vi } from "vitest";
import type { DashboardListItem } from "@/api/dashboard";
import type { ExploreRun } from "@/api/explore";
import type { SemanticModelResponse } from "@/api/semantic-model";
import type { CatalogConnectorSummary } from "@/lib/types/catalog";
import type { Connector } from "@/lib/types/connector";
import type { Metric } from "@/lib/types/metric";
import {
  IDLE_POLL_INTERVAL_MS, ACTIVE_POLL_INTERVAL_MS, activityPollInterval, askOwnersLabel, attentionItems, currentStep, generatedNames,
  getStartedSteps, hasUsedAgentConnection, questionFailureCause, readGetStartedHidden, writeGetStartedHidden,
} from "./overview-model";

const now = Date.parse("2026-10-02T12:00:00Z");
const connector = (id: string, name: string) => ({ id, name, workspaceId: "w", type: "POSTGRESQL", properties: {}, createdAtEpochMs: 1, updatedAtEpochMs: 1 }) as unknown as Connector;
const summary = (connectorId: string, tableCount: number, lastRefresh: CatalogConnectorSummary["lastRefresh"] = null): CatalogConnectorSummary => ({
  connectorId, selectedCatalogCount: 0, selectedSchemaCount: 1, tableCount, lastRefresh, lastSuccessfulRefreshAt: null,
});
const failedRefresh = (state: "COMPLETED_WITH_ERRORS" | "RUNNING", entryFailureCode: string | null): CatalogConnectorSummary["lastRefresh"] => ({
  jobId: "job", state, catalogName: null, requestedAt: "2026-10-01T18:00:00Z", finishedAt: state === "RUNNING" ? null : "2026-10-01T18:40:00Z", errorCount: 1, entryFailureCode,
});
const run = (runId: string, state: ExploreRun["state"], updatedAt: string, failureCode: string | null = null) => ({
  runId, kind: "SQL", workspaceId: "w", connectorId: "c1", requestDigest: "d", state, failureCode, failureDetail: null,
  createdAt: updatedAt, updatedAt, resultMetadata: null, result: null, resultState: null, sql: "select 1", credentialSurface: "WEB", agentApp: null,
}) as ExploreRun;
const dashboard = (dashboardId: string, title: string, updatedAt: string, draftHasChanges: boolean) => ({
  dashboardId, workspaceId: "w", title, description: null, updatedAt, updatedBy: null, credentialSurface: null, agentApp: null, latestResultsAt: null, schedules: [], draftHasChanges,
  draftLastEditedBy: draftHasChanges ? { id: "u-sam", displayName: "Sam Kim" } : null, draftLastEditedAt: draftHasChanges ? updatedAt : null,
  draftLastEditSurface: draftHasChanges ? "AGENT" : null, draftLastEditAgentApp: null, pagePlacementCount: 0,
}) as DashboardListItem;

describe("Needs attention", () => {
  it("lists failed connector refreshes, draft changes, and the viewer's failures from the last day only", () => {
    const items = attentionItems({
      connectors: [connector("c1", "Snowflake — Finance"), connector("c2", "Production Postgres"), connector("c3", "Running")],
      summaries: [summary("c1", 0, failedRefresh("COMPLETED_WITH_ERRORS", "AUTHENTICATION_FAILED")), summary("c2", 3, failedRefresh("COMPLETED_WITH_ERRORS", null)), summary("c3", 0, failedRefresh("RUNNING", "AUTHENTICATION_FAILED"))],
      dashboards: [dashboard("d1", "Pipeline health", "2026-10-01T00:00:00Z", true), dashboard("d2", "Revenue", "2026-10-01T00:00:00Z", false)],
      myRuns: [run("r1", "FAILED", "2026-10-02T10:00:00Z", "PERMISSION_DENIED"), run("r2", "FAILED", "2026-09-30T10:00:00Z"), run("r3", "SUCCEEDED", "2026-10-02T11:00:00Z")],
      now,
    });
    expect(items.map((item) => item.key)).toEqual(["connector:c1", "draft:d1", "question:r1"]);
    expect(items[1]).toMatchObject({ kind: "draft", editedBy: { displayName: "Sam Kim" }, editSurface: "AGENT" });
    expect(items[0]).toMatchObject({ connectorName: "Snowflake — Finance", cause: "Sign-in rejected", at: "2026-10-01T18:40:00Z" });
    expect(items[2]).toMatchObject({ cause: "Access denied" });
  });

  it("is empty when nothing needs attention", () => {
    expect(attentionItems({ connectors: [], summaries: [], dashboards: [], myRuns: [], now })).toEqual([]);
  });
});

describe("Get started", () => {
  const empty = { connectors: [], summaries: [], models: [], metrics: [], myRuns: [], agentUsed: false };

  it("computes every step from current state and starts at the first unfinished one", () => {
    const steps = getStartedSteps({
      ...empty,
      connectors: [connector("c1", "Production Postgres")],
      summaries: [summary("c1", 142)],
    });
    expect(steps.map((step) => [step.key, step.done])).toEqual([
      ["connect", true], ["tables", true], ["model", false], ["metric", false], ["question", false], ["agent", false],
    ]);
    expect(steps[0].evidence).toBe("Production Postgres");
    expect(steps[1].evidence).toBe("142 tables");
    expect(currentStep(steps)?.key).toBe("model");
    expect(steps.map((step) => step.role)).toEqual(["OWNER", "EDITOR", "EDITOR", "EDITOR", "EXPLORER", "VIEWER"]);
    expect(steps[5].title).toBe("Connect an agent");
  });

  it("finishes when all six are done, with the agent step done once this Workspace has agent activity", () => {
    const done = {
      connectors: [connector("c1", "Warehouse")], summaries: [summary("c1", 1)],
      models: [{ id: "m", name: "orders", label: "Orders" } as SemanticModelResponse],
      metrics: [{ id: "x", name: "revenue" } as Metric],
      myRuns: [run("r1", "SUCCEEDED", "2026-10-02T10:00:00Z")],
    };
    expect(currentStep(getStartedSteps({ ...empty, ...done }))?.key).toBe("agent");
    expect(currentStep(getStartedSteps({ ...empty, ...done, agentUsed: true }))).toBeNull();
  });

  it("completes the agent step once the viewer has used a connection that is still active", () => {
    expect(hasUsedAgentConnection([])).toBe(false);
    expect(hasUsedAgentConnection([{ revokedAt: null, lastUsedAt: null }])).toBe(false);
    expect(hasUsedAgentConnection([{ revokedAt: "2026-10-01T00:00:00Z", lastUsedAt: "2026-09-30T00:00:00Z" }])).toBe(false);
    expect(hasUsedAgentConnection([{ revokedAt: null, lastUsedAt: null }, { revokedAt: null, lastUsedAt: "2026-10-02T09:00:00Z" }])).toBe(true);
  });

  it("names the owners to ask", () => {
    expect(askOwnersLabel(null)).toBe("Ask a workspace owner");
    expect(askOwnersLabel([{ displayName: "Mina Park" }])).toBe("Ask Mina Park");
    expect(askOwnersLabel([{ displayName: "Mina Park" }, { displayName: "Sam Kim" }])).toBe("Ask Mina Park or Sam Kim");
    expect(askOwnersLabel([{ displayName: "Mina Park" }, { displayName: "Sam Kim" }, { displayName: "Jo Lee" }])).toBe("Ask Mina Park or another owner");
  });

  it("recognizes the names created at sign-up", () => {
    expect(generatedNames("My Workspace", "Sam Kim's organization")).toEqual({ workspace: true, organization: true });
    expect(generatedNames("Revenue Analytics", "Northstar Labs")).toEqual({ workspace: false, organization: false });
  });
});

describe("Hide is browser-local", () => {
  afterEach(() => vi.unstubAllGlobals());

  it("remembers the choice and survives unavailable storage", () => {
    const store = new Map<string, string>();
    vi.stubGlobal("localStorage", { getItem: (key: string) => store.get(key) ?? null, setItem: (key: string, value: string) => store.set(key, value) });
    expect(readGetStartedHidden("key")).toBe(false);
    writeGetStartedHidden("key");
    expect(readGetStartedHidden("key")).toBe(true);
    vi.stubGlobal("localStorage", { getItem: () => { throw new Error("blocked"); }, setItem: () => { throw new Error("blocked"); } });
    expect(readGetStartedHidden("key")).toBe(false);
    expect(() => writeGetStartedHidden("key")).not.toThrow();
  });
});

describe("polling", () => {
  it("polls slowly only while an idle workspace has no activity", () => {
    expect(activityPollInterval({ loaded: true, liveCount: 0, recentCount: 0 })).toBe(IDLE_POLL_INTERVAL_MS);
    expect(activityPollInterval({ loaded: true, liveCount: 1, recentCount: 0 })).toBe(ACTIVE_POLL_INTERVAL_MS);
    expect(activityPollInterval({ loaded: true, liveCount: 0, recentCount: 3 })).toBe(ACTIVE_POLL_INTERVAL_MS);
    expect(activityPollInterval({ loaded: false, liveCount: 0, recentCount: 0 })).toBe(ACTIVE_POLL_INTERVAL_MS);
  });
});

describe("questionFailureCause", () => {
  it("names the warehouse cause with the activity feed's wording", () => {
    expect(questionFailureCause({ failureCode: "CONNECTION_FAILED" })).toBe("Warehouse unreachable");
    expect(questionFailureCause({ failureCode: "AUTHENTICATION_FAILED" })).toBe("Sign-in rejected");
    expect(questionFailureCause({ failureCode: "PERMISSION_DENIED" })).toBe("Access denied");
    expect(questionFailureCause({ failureCode: "EXECUTION_FAILED" })).toBe("Warehouse error");
  });
});
