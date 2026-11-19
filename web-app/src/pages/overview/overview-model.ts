import type { DashboardListItem } from "@/api/dashboard";
import type { ExploreRun } from "@/api/explore";
import type { SemanticModelResponse } from "@/api/semantic-model";
import { failureCause } from "@/components/catalog/catalogCopy";
import type { AgentConnection } from "@/lib/auth/agent-oauth";
import type { WorkspaceRole } from "@/lib/auth/types";
import type { CatalogConnectorSummary } from "@/lib/types/catalog";
import type { Connector } from "@/lib/types/connector";
import type { Metric } from "@/lib/types/metric";
import type { AgentApp, CredentialSurface, RecordedActor } from "@/lib/types/provenance";

// Everything on Overview is computed from current owner state. Nothing here is stored: there are no
// read marks, dismissals, or completed-step records.

const FAILED_QUESTION_WINDOW_MS = 24 * 60 * 60 * 1_000;
const FAILED_QUESTION_LIMIT = 3;
export const ACTIVE_POLL_INTERVAL_MS = 2_000;
export const IDLE_POLL_INTERVAL_MS = 15_000;

export type AttentionItem =
  | { kind: "connector"; key: string; connectorId: string; connectorName: string; cause: string; at: string }
  | { kind: "draft"; key: string; dashboardId: string; title: string; editedBy: RecordedActor | null; editSurface: CredentialSurface | null; editAgentApp: AgentApp | null; editedAt: string | null }
  | { kind: "question"; key: string; run: ExploreRun; cause: string; at: string };

/** A short cause for a failed Explore question, matching the activity feed's fixed wording. */
export function questionFailureCause(run: Pick<ExploreRun, "failureCode">): string {
  switch (run.failureCode) {
    case "PERMISSION_DENIED": return "Access denied";
    case "CONNECTION_FAILED": return "Warehouse unreachable";
    case "AUTHENTICATION_FAILED": return "Sign-in rejected";
    case "EXECUTION_ORPHANED": return "Stopped reporting progress";
    default: return "Warehouse error";
  }
}

/**
 * H9 Needs attention: connectors whose last refresh failed, dashboards whose shared Draft differs from the
 * applied definition, and the viewer's own questions that failed in the last 24 hours.
 */
export function attentionItems(input: {
  connectors: Connector[];
  summaries: CatalogConnectorSummary[];
  dashboards: DashboardListItem[];
  myRuns: ExploreRun[];
  now: number;
}): AttentionItem[] {
  const names = new Map(input.connectors.map((connector) => [connector.id, connector.name]));
  const connectors = input.summaries.flatMap((summary): AttentionItem[] => {
    const refresh = summary.lastRefresh;
    const name = names.get(summary.connectorId);
    if (!refresh || !refresh.entryFailureCode || !name) return [];
    if (refresh.state === "RUNNING" || refresh.state === "CANCEL_REQUESTED") return [];
    return [{
      kind: "connector", key: `connector:${summary.connectorId}`, connectorId: summary.connectorId, connectorName: name,
      cause: failureCause(refresh.entryFailureCode), at: refresh.finishedAt ?? refresh.requestedAt,
    }];
  });
  const drafts = input.dashboards.filter((dashboard) => dashboard.draftHasChanges)
    .map((dashboard): AttentionItem => ({
      kind: "draft", key: `draft:${dashboard.dashboardId}`, dashboardId: dashboard.dashboardId, title: dashboard.title,
      editedBy: dashboard.draftLastEditedBy, editSurface: dashboard.draftLastEditSurface, editAgentApp: dashboard.draftLastEditAgentApp, editedAt: dashboard.draftLastEditedAt,
    }));
  const questions = input.myRuns
    .filter((run) => run.state === "FAILED" && input.now - Date.parse(run.updatedAt) <= FAILED_QUESTION_WINDOW_MS)
    .slice(0, FAILED_QUESTION_LIMIT)
    .map((run): AttentionItem => ({ kind: "question", key: `question:${run.runId}`, run, cause: questionFailureCause(run), at: run.updatedAt }));
  return [...connectors, ...drafts, ...questions];
}

export type GetStartedStepKey = "connect" | "tables" | "model" | "metric" | "question" | "agent";

export interface GetStartedStep {
  key: GetStartedStepKey;
  title: string;
  done: boolean;
  /** Evidence that a finished step was done, such as "142 tables"; never a dashboard of counts. */
  evidence: string | null;
  /** The lowest role that can do this step. */
  role: WorkspaceRole;
}

function namedEvidence(names: string[]): string | null {
  if (names.length === 0) return null;
  return names.length === 1 ? names[0] : `${names[0]} and ${names.length - 1} more`;
}

function countLabel(count: number, singular: string, plural: string): string {
  return `${count.toLocaleString("en-US")} ${count === 1 ? singular : plural}`;
}

/**
 * The agent step is personal: it is done once the viewer has used one of their own connections. A connection
 * counts while it is not revoked and Monomer has recorded its use (lastUsedAt).
 */
export function hasUsedAgentConnection(connections: ReadonlyArray<Pick<AgentConnection, "revokedAt" | "lastUsedAt">>): boolean {
  return connections.some((connection) => connection.revokedAt === null && connection.lastUsedAt !== null);
}

/** H10 Get started: six steps whose completion comes from what the workspace and the viewer have now. */
export function getStartedSteps(input: {
  connectors: Connector[];
  summaries: CatalogConnectorSummary[];
  models: SemanticModelResponse[];
  metrics: Metric[];
  myRuns: ExploreRun[];
  agentUsed: boolean;
}): GetStartedStep[] {
  const tables = input.summaries.reduce((total, summary) => total + summary.tableCount, 0);
  return [
    { key: "connect", title: "Connect a data source", role: "OWNER", done: input.connectors.length > 0, evidence: namedEvidence(input.connectors.map((connector) => connector.name)) },
    { key: "tables", title: "Choose tables to load", role: "EDITOR", done: tables > 0, evidence: tables > 0 ? countLabel(tables, "table", "tables") : null },
    { key: "model", title: "Describe a table as a semantic model", role: "EDITOR", done: input.models.length > 0, evidence: namedEvidence(input.models.map((model) => model.label || model.name)) },
    { key: "metric", title: "Turn a measure into a metric", role: "EDITOR", done: input.metrics.length > 0, evidence: namedEvidence(input.metrics.map((metric) => metric.label || metric.name)) },
    { key: "question", title: "Ask a question in Explore", role: "EXPLORER", done: input.myRuns.length > 0, evidence: null },
    // Connecting an agent is personal, so every role can take this step and nobody is told to ask an owner.
    { key: "agent", title: "Connect an agent", role: "VIEWER", done: input.agentUsed, evidence: null },
  ];
}

export function currentStep(steps: GetStartedStep[]): GetStartedStep | null {
  return steps.find((step) => !step.done) ?? null;
}

/** "Ask Mina Park", "Ask Mina Park or Sam Kim", or a generic owner when no names are known. */
export function askOwnersLabel(owners: ReadonlyArray<{ displayName: string }> | null): string {
  const names = (owners ?? []).map((owner) => owner.displayName).filter(Boolean);
  if (names.length === 0) return "Ask a workspace owner";
  if (names.length <= 2) return `Ask ${names.join(" or ")}`;
  return `Ask ${names[0]} or another owner`;
}

export const GENERATED_WORKSPACE_NAME = "My Workspace";
const GENERATED_ORGANIZATION_SUFFIX = "'s organization";

/** Whether the names created at sign-up are still in place, so Overview can offer to rename them. */
export function generatedNames(workspaceName: string, organizationName: string | null): { workspace: boolean; organization: boolean } {
  return {
    workspace: workspaceName === GENERATED_WORKSPACE_NAME,
    organization: organizationName !== null && organizationName.endsWith(GENERATED_ORGANIZATION_SUFFIX),
  };
}

/** Two-second polling while anything is live or the workspace shows activity; slow polling in an idle, empty workspace. */
export function activityPollInterval(input: { liveCount: number; recentCount: number; loaded: boolean }): number {
  if (!input.loaded || input.liveCount > 0 || input.recentCount > 0) return ACTIVE_POLL_INTERVAL_MS;
  return IDLE_POLL_INTERVAL_MS;
}

export function getStartedHiddenKey(workspaceId: string, principalId: string): string {
  return `monomer.overview.get-started-hidden.${workspaceId}.${principalId}`;
}

export function readGetStartedHidden(key: string): boolean {
  try {
    return localStorage.getItem(key) === "1";
  } catch {
    return false;
  }
}

export function writeGetStartedHidden(key: string): void {
  try {
    localStorage.setItem(key, "1");
  } catch {
    // A browser without storage hides the section for this visit only.
  }
}
