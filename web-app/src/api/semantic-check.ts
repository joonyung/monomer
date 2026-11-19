import { apiFetch, ApiError } from "@/lib/api/client";
import { semanticBffUrl } from "@/api/workspace-bff";
import { buildMetricDsl, type WriteMetricRequest } from "@/api/metric";
import { buildSemanticModelDsl, type CreateSemanticModelRequest } from "@/api/semantic-model";
import type { components } from "@/api/control/generated";
import { z } from "zod";

export type SemanticCheckResult = components["schemas"]["SemanticCheckView"];

const granularity = z.enum(["NANOSECOND", "MICROSECOND", "MILLISECOND", "SECOND", "MINUTE", "HOUR", "DAY", "WEEK", "MONTH", "QUARTER", "YEAR"]);
export const semanticCheckResultSchema: z.ZodType<SemanticCheckResult> = z.object({
  ok: z.boolean(),
  runsOn: z.array(z.string()),
  partitionFilters: z.array(z.discriminatedUnion("kind", [
    z.object({ kind: z.literal("SEMANTIC_MODEL"), connectorId: z.string(), relation: z.object({ catalog: z.string().nullable(), schema: z.string(), name: z.string() }), partitionKeys: z.array(z.string()), semanticModelName: z.string(), dimensions: z.array(z.string()), timeRangeApplies: z.boolean() }),
    z.object({ kind: z.literal("TIME_SPINE"), connectorId: z.string(), relation: z.object({ catalog: z.string().nullable(), schema: z.string(), name: z.string() }), partitionKeys: z.array(z.string()) }),
  ])),
  problems: z.array(z.object({
    code: z.enum(["NAME_CONFLICT", "NAME_IMMUTABLE", "CONNECTOR_NOT_FOUND", "METRIC_MODEL_NOT_FOUND", "METRIC_DEPENDENCY_NOT_FOUND", "SOURCE_RELATION_NOT_FOUND", "CATALOG_RELATION_NOT_FOUND", "METRICFLOW_MANIFEST_INVALID", "QUERY_UNRENDERABLE", "TIME_SPINE_UNAVAILABLE", "RELATIONSHIP_JOIN_UNSUPPORTED", "UNSUPPORTED_DIALECT", "PARTITION_COLUMN_NOT_MODELED", "TIME_KIND_UNDECLARED", "TIME_KIND_MISMATCH", "TIME_SPINE_COLUMN_TYPE_INVALID"]),
    connectorId: z.string().nullable(),
    field: z.string().nullable(),
    definition: z.object({ kind: z.enum(["SEMANTIC_MODEL", "METRIC"]), name: z.string() }).nullable(),
    message: z.string(),
  })),
  preview: z.object({
    connectorId: z.string(),
    dialect: z.enum(["POSTGRESQL", "BIGQUERY", "SNOWFLAKE", "DATABRICKS", "REDSHIFT", "TRINO", "DUCKDB"]),
    sql: z.string(),
    groupBys: z.array(z.discriminatedUnion("kind", [
      z.object({ kind: z.literal("DIMENSION"), semanticModelName: z.string(), name: z.string(), granularity: granularity.optional() }),
      z.object({ kind: z.literal("ENTITY"), semanticModelName: z.string(), name: z.string() }),
      z.object({ kind: z.literal("METRIC_TIME"), granularity }),
    ])),
  }).nullable(),
});

export type SemanticCheckProblem = SemanticCheckResult["problems"][number];

/** Problems that still let the definition run: it renders, but possibly not as intended. */
const WARNING_PROBLEM_CODES: ReadonlySet<SemanticCheckProblem["code"]> = new Set(["TIME_KIND_UNDECLARED"]);

export function isSemanticCheckWarning(problem: Pick<SemanticCheckProblem, "code">): boolean {
  return WARNING_PROBLEM_CODES.has(problem.code);
}

/**
 * The sentence shown for a problem. The server's message stays when it names the specifics (the declared
 * timeKind and the column's type, or the spine column); a bare undeclared timeKind also says why it matters.
 */
export function semanticCheckProblemMessage(problem: Pick<SemanticCheckProblem, "code" | "message">): string {
  if (problem.code === "TIME_KIND_UNDECLARED") return "Declare timeKind for this time dimension so times convert to the Reporting time zone correctly.";
  return problem.message;
}

export type SemanticCheckDraft =
  | { kind: "SEMANTIC_MODEL"; semanticModelId: string | null; request: CreateSemanticModelRequest }
  | { kind: "METRIC"; metricId: string | null; request: WriteMetricRequest };

// Resolves an unsaved definition against the saved ones without saving it or contacting a warehouse.
export async function checkSemanticDefinition(
  workspaceId: string,
  draft: SemanticCheckDraft,
  signal?: AbortSignal,
): Promise<SemanticCheckResult> {
  const body = draft.kind === "SEMANTIC_MODEL"
    ? { kind: draft.kind, ...(draft.semanticModelId ? { semanticModelId: draft.semanticModelId } : {}), definition: buildSemanticModelDsl(draft.request) }
    : { kind: draft.kind, ...(draft.metricId ? { metricId: draft.metricId } : {}), definition: buildMetricDsl(draft.request) };
  const response = await apiFetch(semanticBffUrl(workspaceId, "/check"), {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
    signal,
  });
  try {
    return semanticCheckResultSchema.parse(await response.json());
  } catch (cause) {
    if (signal?.aborted) throw cause;
    throw new Error("The check is unavailable. Try again.", { cause });
  }
}

export interface SemanticSaveFailure {
  message: string;
  // Definition paths such as `name` or `measures[2].expression`, mapped to the server's message.
  fieldErrors: Record<string, string>;
}

const DECLARED_SAVE_PROBLEMS = new Set([
  "SEMANTIC_DEFINITION_INVALID",
  "SEMANTIC_NAME_CONFLICT",
  "IDEMPOTENCY_CONFLICT",
  "INVALID_REQUEST",
  "AUTHENTICATION_REQUIRED",
  "FORBIDDEN",
  "NOT_FOUND",
  "SERVICE_UNAVAILABLE",
]);
const SCOPE_FIELDS = new Set(["definition", "body"]);

/** What a failed Semantic save tells the editor: a safe message and any field-level repairs. */
export function semanticSaveFailure(cause: unknown): SemanticSaveFailure {
  if (cause instanceof ApiError && cause.problem && DECLARED_SAVE_PROBLEMS.has(cause.problem.code)) {
    const fieldErrors: Record<string, string> = {};
    for (const violation of cause.problem.violations ?? []) {
      if (!SCOPE_FIELDS.has(violation.field)) fieldErrors[violation.field] ??= violation.message;
    }
    return { message: cause.problem.detail, fieldErrors };
  }
  const status = cause instanceof ApiError ? ` (status ${cause.status})` : "";
  return { message: `The definition couldn't be saved. Try again.${status}`, fieldErrors: {} };
}
