import type { CatalogIngestTask, RelationKind } from "@/lib/types/catalog";

const RELATION_KIND_LABELS: Record<RelationKind, string> = {
  TABLE: "Table",
  VIEW: "View",
  MATERIALIZED_VIEW: "Materialized view",
  EXTERNAL: "External table",
  FOREIGN: "Foreign table",
  DYNAMIC: "Dynamic table",
  SNAPSHOT: "Snapshot",
  UNKNOWN: "Unknown",
};

export function relationKindLabel(kind: RelationKind): string {
  return RELATION_KIND_LABELS[kind];
}

export function isViewKind(kind: RelationKind): boolean {
  return kind === "VIEW" || kind === "MATERIALIZED_VIEW";
}

// Execution drivers classify warehouse failures into these codes; other codes keep their safe message.
const FAILURE_REASONS: Record<string, string> = {
  PERMISSION_DENIED: "permission denied",
  AUTHENTICATION_FAILED: "authentication failed",
  CREDENTIAL_UNAVAILABLE: "credentials unavailable",
  CONNECTION_FAILED: "connection failed",
};

export type RefreshFailure = Pick<CatalogIngestTask, "failureCode" | "safeMessage">;

export function refreshFailureSummary(failure: RefreshFailure): string {
  const reason = (failure.failureCode ? FAILURE_REASONS[failure.failureCode] : undefined) ?? failure.safeMessage;
  return reason ? `Last refresh failed: ${reason}` : "Last refresh failed";
}

export function lastRefreshedLabel(formatted: string | null): string {
  return formatted ? `Last refreshed ${formatted}` : "Not refreshed yet";
}

export function formatElapsed(totalSeconds: number): string {
  const hours = Math.floor(totalSeconds / 3_600);
  const minutes = Math.floor((totalSeconds % 3_600) / 60);
  const seconds = String(totalSeconds % 60).padStart(2, "0");
  return hours > 0 ? `${hours}:${String(minutes).padStart(2, "0")}:${seconds}` : `${minutes}:${seconds}`;
}

export function countLabel(count: number, singular: string, plural: string): string {
  return `${count.toLocaleString("en-US")} ${count === 1 ? singular : plural}`;
}

export type FailureFix = "credentials" | "settings" | "access" | null;

export interface FailureExplanation {
  title: string;
  detail: string | null;
  fix: FailureFix;
}

// Explains a warehouse failure in human words; `warehouseLabel` is the connector type, such as "Snowflake".
export function failureExplanation(failureCode: string | null, safeMessage: string | null, warehouseLabel: string): FailureExplanation {
  switch (failureCode) {
    case "AUTHENTICATION_FAILED":
      return { title: `Monomer couldn't sign in to ${warehouseLabel}.`, detail: "The warehouse rejected the username or key.", fix: "credentials" };
    case "CREDENTIAL_UNAVAILABLE":
      return { title: "Monomer couldn't read this connector's saved credentials.", detail: "Replace the credentials to connect again.", fix: "credentials" };
    case "CONNECTION_FAILED":
      return { title: `Monomer couldn't reach ${warehouseLabel}.`, detail: "Check the host, port, and network settings.", fix: "settings" };
    case "PERMISSION_DENIED":
      return { title: `${warehouseLabel} denied access.`, detail: "Grant read access to the connector's account, then try again.", fix: "access" };
    default:
      return { title: "Monomer couldn't read this from the warehouse.", detail: safeMessage, fix: null };
  }
}

// A short cause for dense rows, such as "Failed · Sign-in rejected" in the Connectors list.
export function failureCause(failureCode: string | null): string {
  switch (failureCode) {
    case "AUTHENTICATION_FAILED": return "Sign-in rejected";
    case "CREDENTIAL_UNAVAILABLE": return "Credentials unavailable";
    case "CONNECTION_FAILED": return "Warehouse unreachable";
    case "PERMISSION_DENIED": return "Access denied";
    default: return "Refresh failed";
  }
}

// The person who started a refresh; an agent run is marked separately with AgentMarker.
export function requesterLabel(requester: { displayName: string | null }): string {
  return requester.displayName ?? "Unknown member";
}

export function selectionCountLabel(selection: { selectedSchemas: { relations: { kind: "ALL_RELATIONS" } | { kind: "SELECTED_RELATIONS"; relationNames: string[] } }[] }): string {
  const wholeSchemas = selection.selectedSchemas.filter((schema) => schema.relations.kind === "ALL_RELATIONS").length;
  const tables = selection.selectedSchemas.reduce((total, schema) => total + (schema.relations.kind === "SELECTED_RELATIONS" ? schema.relations.relationNames.length : 0), 0);
  if (wholeSchemas === 0 && tables === 0) return "Nothing selected";
  const parts = [
    wholeSchemas > 0 ? countLabel(wholeSchemas, "schema", "schemas") : null,
    tables > 0 ? countLabel(tables, "table", "tables") : null,
  ].filter(Boolean);
  return `${parts.join(" · ")} selected`;
}

function sameDay(left: Date, right: Date): boolean {
  return left.toDateString() === right.toDateString();
}

// "Today, 8:12 AM", "Yesterday, 6:40 PM", or "Sep 30, 6:40 PM" for refresh times in dense rows.
export function formatRefreshTime(iso: string, now: Date = new Date()): string {
  const date = new Date(iso);
  if (Number.isNaN(date.valueOf())) return iso;
  const time = date.toLocaleTimeString("en-US", { hour: "numeric", minute: "2-digit" });
  if (sameDay(date, now)) return `Today, ${time}`;
  const yesterday = new Date(now);
  yesterday.setDate(now.getDate() - 1);
  if (sameDay(date, yesterday)) return `Yesterday, ${time}`;
  return `${date.toLocaleDateString("en-US", { month: "short", day: "numeric" })}, ${time}`;
}

export function formatShortDate(iso: string, now: Date = new Date()): string {
  const date = new Date(iso);
  if (Number.isNaN(date.valueOf())) return iso;
  if (sameDay(date, now)) return "today";
  return date.toLocaleDateString("en-US", { month: "short", day: "numeric", ...(date.getFullYear() === now.getFullYear() ? {} : { year: "numeric" }) });
}
