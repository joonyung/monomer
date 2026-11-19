import { z } from "zod";
import {
  SEMANTIC_QUERY_SCHEMA_VERSION,
  type ExploreRun,
  type SemanticFilter,
  type SemanticGroupBy,
  type SemanticMetricTimeRange,
  type SemanticQueryOrder,
  type SemanticQuerySpec,
} from "@/api/explore";
import type { ConnectorType } from "@/lib/types/connector";

export type ExploreMode = "SEMANTIC" | "SQL";

export const DEFAULT_SEMANTIC_ROW_LIMIT = 1_000;

export interface SemanticDraft {
  metrics: string[];
  groupBys: SemanticGroupBy[];
  metricTimeRange: SemanticMetricTimeRange | null;
  filters: SemanticFilter[];
  orderBy: SemanticQueryOrder[];
  rowLimit: number;
  /** The connector the person chose when several can run the selected metrics. */
  connectorId: string | null;
}

export interface SqlDraft {
  connectorId: string | null;
  text: string;
}

export interface ExploreDraft {
  mode: ExploreMode;
  semantic: SemanticDraft;
  sql: SqlDraft;
}

export function emptyExploreDraft(): ExploreDraft {
  return {
    mode: "SEMANTIC",
    semantic: {
      metrics: [],
      groupBys: [],
      metricTimeRange: null,
      filters: [],
      orderBy: [],
      rowLimit: DEFAULT_SEMANTIC_ROW_LIMIT,
      connectorId: null,
    },
    sql: { connectorId: null, text: "" },
  };
}

export function semanticQueryOf(draft: SemanticDraft): SemanticQuerySpec {
  return {
    schemaVersion: SEMANTIC_QUERY_SCHEMA_VERSION,
    metrics: draft.metrics,
    groupBys: draft.groupBys,
    metricTimeRange: draft.metricTimeRange,
    filters: draft.filters,
    orderBy: draft.orderBy,
    rowLimit: draft.rowLimit,
  };
}

const STORAGE_PREFIX = "monomer.explore.draft.";

const storedDraftSchema = z.object({
  mode: z.enum(["SEMANTIC", "SQL"]),
  semantic: z.object({
    metrics: z.array(z.string()),
    groupBys: z.array(z.unknown()),
    metricTimeRange: z.unknown().nullable(),
    filters: z.array(z.unknown()),
    orderBy: z.array(z.unknown()),
    rowLimit: z.number().int().min(1).max(100_000),
    connectorId: z.string().nullable(),
  }),
  sql: z.object({ connectorId: z.string().nullable(), text: z.string() }),
});

/** Browser-local draft for one workspace. Storage may be unavailable; the page then starts empty. */
export function readStoredDraft(workspaceId: string): ExploreDraft | null {
  try {
    const raw = window.localStorage.getItem(`${STORAGE_PREFIX}${workspaceId}`);
    if (!raw) return null;
    const parsed = storedDraftSchema.safeParse(JSON.parse(raw));
    if (!parsed.success) return null;
    return parsed.data as ExploreDraft;
  } catch {
    return null;
  }
}

export function writeStoredDraft(workspaceId: string, draft: ExploreDraft): void {
  try {
    window.localStorage.setItem(`${STORAGE_PREFIX}${workspaceId}`, JSON.stringify(draft));
  } catch {
    // Drafts are a convenience; a blocked or full storage leaves the in-memory draft intact.
  }
}

/** Puts a run's question back into the composer and switches to its mode. */
export function draftFromRun(current: ExploreDraft, run: ExploreRun): ExploreDraft {
  if (run.kind === "SQL") {
    return { ...current, mode: "SQL", sql: { connectorId: run.connectorId, text: run.sql } };
  }
  if (!run.query) return { ...current, mode: "SEMANTIC" };
  return {
    ...current,
    mode: "SEMANTIC",
    semantic: {
      metrics: run.query.metrics,
      groupBys: run.query.groupBys,
      metricTimeRange: run.query.metricTimeRange,
      filters: run.query.filters,
      orderBy: run.query.orderBy,
      rowLimit: run.query.rowLimit,
      connectorId: run.connectorId,
    },
  };
}

/**
 * Whether the composer still asks the question the viewed run answered: that run's query or SQL text
 * and connector, whichever mode is showing. Opening or starting a run writes its connector into the
 * draft, so only a person's edit makes the two differ.
 */
export function draftMatchesRun(draft: ExploreDraft, run: ExploreRun): boolean {
  if (run.kind === "SQL") {
    return draft.sql.connectorId === run.connectorId && draft.sql.text.trim() === run.sql.trim();
  }
  if (!run.query) return false;
  return draft.semantic.connectorId === run.connectorId
    && canonicalJson(semanticQueryOf(draft.semantic)) === canonicalJson(run.query);
}

/** Whether two drafts ask the same question in the part a run of this kind restores. */
function sameQuestionPart(left: ExploreDraft, right: ExploreDraft, kind: ExploreRun["kind"]): boolean {
  if (kind === "SQL") return left.sql.connectorId === right.sql.connectorId && left.sql.text.trim() === right.sql.text.trim();
  return canonicalJson(semanticQueryOf(left.semantic)) === canonicalJson(semanticQueryOf(right.semantic));
}

/**
 * Opening a run replaces the composer's question of that kind. The replaced question is worth offering
 * back only when it asked something that neither the opened run nor the last question that ran asks.
 */
export function replacedQuestionWorthKeeping(current: ExploreDraft, run: ExploreRun, lastRunQuestion: ExploreDraft | null): boolean {
  const empty = run.kind === "SQL" ? current.sql.text.trim().length === 0 : current.semantic.metrics.length === 0;
  if (empty || sameQuestionPart(current, draftFromRun(current, run), run.kind)) return false;
  return !(lastRunQuestion && sameQuestionPart(current, lastRunQuestion, run.kind));
}

export interface UnavailableSelections {
  groupBys: SemanticGroupBy[];
  filters: SemanticFilter[];
}

export const NO_UNAVAILABLE_SELECTIONS: UnavailableSelections = { groupBys: [], filters: [] };

/**
 * Group-bys and filters in the question that discovery doesn't offer for its metrics on this connector.
 * They stay in the question, marked, until the person removes them; Run waits for that.
 */
export function unavailableSelections(draft: SemanticDraft, available: SemanticGroupBy[]): UnavailableSelections {
  const groupBys = draft.groupBys.filter((candidate) => !available.some((option) => JSON.stringify(option) === JSON.stringify(candidate)));
  const filters = draft.filters.filter((filter) => !available.some((option) => option.kind === "DIMENSION"
    && option.semanticModelName === filter.dimension.semanticModelName
    && option.name === filter.dimension.name));
  return groupBys.length === 0 && filters.length === 0 ? NO_UNAVAILABLE_SELECTIONS : { groupBys, filters };
}

function canonicalJson(value: unknown): string {
  return JSON.stringify(value, (_key, nested: unknown) => {
    if (nested && typeof nested === "object" && !Array.isArray(nested)) {
      return Object.fromEntries(Object.entries(nested as Record<string, unknown>)
        .filter(([, field]) => field !== undefined)
        .sort(([left], [right]) => left.localeCompare(right)));
    }
    return nested;
  });
}

export interface CatalogTableAddress {
  catalog: string | null;
  schema: string;
  table: string;
}

/** BigQuery and Databricks delimit identifiers with backticks; the other dialects use double quotes. */
export function quoteIdentifier(connectorType: ConnectorType, name: string): string {
  if (connectorType === "BIGQUERY" || connectorType === "DATABRICKS") return `\`${name.replaceAll("`", "``")}\``;
  return `"${name.replaceAll("\"", "\"\"")}"`;
}

export function tablePreviewSql(connectorType: ConnectorType, address: CatalogTableAddress): string {
  const path = [address.catalog, address.schema, address.table]
    .filter((part): part is string => Boolean(part))
    .map((part) => quoteIdentifier(connectorType, part))
    .join(".");
  return `SELECT * FROM ${path} LIMIT 100`;
}
