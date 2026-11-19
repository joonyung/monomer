import { z } from "zod";
import type { DashboardDefinition, DashboardJsonObject } from "@/api/dashboard";
import type { MetricDisplay } from "@/lib/metric-display";

export const VIEW_CONTRACT = 1;
export const VIEW_CONTRACT_META_KEY = "cc.monomer/viewContract";
export const READ_TOOL_NAME = "monomer_read";

const UUID = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
const UUID_V7 = "[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}";
const DASHBOARD = `workspace/dashboards/(${UUID_V7})`;
const DRAFT_PATH = new RegExp(`^${DASHBOARD}/draft$`);
const SESSION_PATH = new RegExp(`^${DASHBOARD}/sessions/${UUID_V7}$`);
const DRAFT_RUN_PATH = new RegExp(`^${DASHBOARD}/draft/query-runs/${UUID_V7}$`);
const SESSION_RUN_PATH = new RegExp(`^${DASHBOARD}/sessions/${UUID_V7}/query-runs/${UUID_V7}$`);

const Uuid = z.string().regex(new RegExp(`^${UUID}$`, "i"));
const RevisionString = z.string().max(19).regex(/^(?:0|[1-9][0-9]{0,18})$/);
const RequestId = z.string().min(1).max(200).regex(/^[A-Za-z0-9._:-]+$/);
const Name = z.string().max(128).regex(/^[A-Za-z_][A-Za-z0-9_]*$/).refine((name) => name !== "__proto__");
const DisplayLabel = z.string().min(1).max(120).regex(/^[^\s](?:[^\r\n]*[^\s])?$/);
const DateTime = z.string().min(1);
const DraftRunPath = z.string().regex(DRAFT_RUN_PATH);
const SessionRunPath = z.string().regex(SESSION_RUN_PATH);
const RunState = z.enum(["RUNNING", "SUCCEEDED", "FAILED", "CANCELLED"]);
const QueryType = z.enum(["SEMANTIC", "SQL"]);
const ValueType = z.enum(["TEXT", "NUMBER", "BOOLEAN", "DATE", "TIMESTAMP", "TEXT_SET", "NUMBER_SET", "DATE_SET", "TIMESTAMP_SET", "DATE_RANGE", "TIMESTAMP_RANGE"]);

function uniqueStrings<T extends z.ZodType<string>>(item: T) {
  return z.array(item).refine((values) => new Set(values).size === values.length);
}

const DashboardValue = z.union([
  z.null(),
  z.string(),
  z.boolean(),
  uniqueStrings(z.string()).refine((values) => values.length > 0),
  z.strictObject({ start: z.string(), end: z.string() }),
]);
const DashboardValueMap = z.record(Name, DashboardValue);

const Control = z.strictObject({
  options: z.array(z.strictObject({ value: z.union([z.string(), z.boolean()]), label: z.string().min(1).nullable().optional() })).min(1).max(500).optional(),
  placeholder: z.string().min(1).optional(),
  min: z.string().min(1).optional(),
  max: z.string().min(1).optional(),
  emptyLabel: DisplayLabel.optional(),
  startPlaceholder: z.string().min(1).optional(),
  endPlaceholder: z.string().min(1).optional(),
}).refine((control) => Object.keys(control).length > 0);

const ValueDefinition = z.strictObject({
  type: ValueType,
  nullable: z.boolean(),
  label: z.string().min(1).nullable().optional(),
  description: z.string().min(1).nullable().optional(),
  control: Control.optional(),
});
const VariableDefinition = z.strictObject({
  type: ValueType,
  nullable: z.boolean(),
  label: z.string().min(1).nullable().optional(),
  description: z.string().min(1).nullable().optional(),
  initial: z.unknown(),
});
const Query = z.discriminatedUnion("type", [
  z.strictObject({ type: z.literal("SEMANTIC"), connectorId: Uuid, query: z.record(z.string(), z.unknown()) }),
  z.strictObject({
    type: z.literal("SQL"),
    connectorId: Uuid,
    sql: z.string().min(1),
    bindings: z.record(Name, z.unknown()),
    columns: uniqueStrings(z.string().min(1).max(504)).refine((columns) => columns.length > 0),
    rowLimit: z.number().int().min(1).max(100_000),
  }),
]);
const ModuleSource = z.string().min(1).max(262_144);
const ResultField = z.string().min(1);
const Header = z.string().min(1).nullable().optional();
const TableColumn = z.strictObject({ field: ResultField, header: Header, width: z.number().int().min(80).max(640).optional() });
const TableShape = {
  type: z.literal("TABLE"),
  title: DisplayLabel.optional(),
  queryName: Name,
  rowHeight: z.number().int().min(32).max(160).optional(),
  module: z.strictObject({
    inputs: z.strictObject({ queries: uniqueStrings(Name), parameters: uniqueStrings(Name), variables: uniqueStrings(Name) }),
    source: ModuleSource,
  }).optional(),
};
const TablePivot = z.strictObject({
  rows: z.array(TableColumn).max(8),
  columns: z.array(z.strictObject({ field: ResultField, header: Header })).min(1).max(3),
  values: z.array(TableColumn).min(1).max(20),
  valuesAxis: z.enum(["COLUMNS", "ROWS"]),
  totals: uniqueStrings(Name).refine((names) => names.length > 0 && names.length <= 3).optional(),
});
// A discriminated union cannot hold two TABLE shapes; each strict shape rejects a TABLE with both or neither of columns and pivot.
const Widget = z.union([
  z.strictObject({ type: z.literal("INPUT"), variableName: Name, control: Control.optional() }),
  z.strictObject({ ...TableShape, columns: z.array(TableColumn).min(1) }),
  z.strictObject({ ...TableShape, pivot: TablePivot }),
  z.strictObject({ type: z.literal("TEXT"), content: z.string().min(1) }),
  z.strictObject({
    type: z.literal("CHART"),
    title: DisplayLabel.optional(),
    queryNames: uniqueStrings(Name).refine((names) => names.length > 0),
    module: z.strictObject({
      inputs: z.strictObject({ parameters: uniqueStrings(Name), variables: uniqueStrings(Name) }),
      source: ModuleSource,
    }),
  }),
]);
type LayoutNodeShape =
  | { type: "WIDGET"; id: string; widgetId: string }
  | { type: "SPACER"; id: string }
  | { type: "STACK"; id: string; direction: "ROW" | "COLUMN"; separation?: "GAP" | "DIVIDER"; children: { weight?: number; node: LayoutNodeShape }[] };
const LayoutNode: z.ZodType<LayoutNodeShape> = z.lazy(() => z.discriminatedUnion("type", [
  z.strictObject({ type: z.literal("WIDGET"), id: Name, widgetId: Name }),
  z.strictObject({ type: z.literal("SPACER"), id: Name }),
  z.strictObject({
    type: z.literal("STACK"),
    id: Name,
    direction: z.enum(["ROW", "COLUMN"]),
    separation: z.enum(["GAP", "DIVIDER"]).optional(),
    children: z.array(z.strictObject({ weight: z.number().int().min(1).optional(), node: LayoutNode })).min(2),
  }),
]));
const DashboardDefinitionSchema = z.strictObject({
  schemaVersion: z.literal(1),
  meta: z.strictObject({ title: z.string().min(1), description: z.string().min(1).nullable().optional() }),
  parameters: z.record(Name, ValueDefinition),
  variables: z.record(Name, VariableDefinition),
  queries: z.record(Name, Query),
  widgets: z.record(Name, Widget),
  layout: z.strictObject({
    sections: z.array(z.strictObject({
      id: Name,
      height: z.discriminatedUnion("mode", [
        z.strictObject({ mode: z.literal("CONTENT") }),
        z.strictObject({ mode: z.literal("SCALED"), scale: z.number().min(0.3).max(10) }),
      ]),
      root: LayoutNode,
    })),
  }),
  schedules: z.record(Name, z.unknown()).optional(),
});

// The Zod mirror checks every field the layout renders; generated types additionally narrow
// opaque query, control and initial-value unions that this read-only view never interprets.
const Definition = DashboardDefinitionSchema.transform((definition) => definition as unknown as DashboardDefinition);
const ValueMap = DashboardValueMap.transform((values) => values as DashboardJsonObject);

export const AgentActor = z.strictObject({ id: z.string().min(1), displayName: z.string().nullable() });
export const AgentApp = z.strictObject({ clientId: z.string().min(1).max(2048), name: z.string(), verifiedHost: z.string().nullable() });
const CredentialSurface = z.enum(["WEB", "AGENT"]);

export const DraftReadyResult = z.strictObject({
  authoringRevision: RevisionString,
  parameterBindings: ValueMap,
  variables: ValueMap,
  queryRuns: z.record(Name, DraftRunPath).refine((runs) => Object.keys(runs).length > 0),
});
export type DraftReadyResult = z.infer<typeof DraftReadyResult>;

export const DraftBody = z.strictObject({
  definition: Definition,
  parameterBindings: ValueMap,
  variables: ValueMap,
  targetQueryRuns: z.record(Name, DraftRunPath),
  readyResults: z.record(Name, DraftReadyResult),
  createdAt: DateTime,
  updatedAt: DateTime,
  lastEditedBy: AgentActor.optional(),
  lastEditedAt: DateTime.optional(),
  lastEditSurface: CredentialSurface.optional(),
  lastEditAgentApp: AgentApp.optional(),
});
export type DraftBody = z.infer<typeof DraftBody>;

export const DraftStatusBody = z.strictObject({
  draftRevision: RevisionString,
  targetQueryRuns: z.record(Name, z.strictObject({ path: DraftRunPath, state: RunState })),
  readyResults: z.record(Name, DraftReadyResult),
});
export type DraftStatusBody = z.infer<typeof DraftStatusBody>;

export const SessionBody = z.strictObject({
  definition: Definition,
  parameterBindings: ValueMap,
  initialVariables: ValueMap,
  runningQueryRuns: z.array(z.strictObject({ queryName: z.string().min(1).max(256), path: SessionRunPath, state: RunState })),
});
export type SessionBody = z.infer<typeof SessionBody>;

const RunFields = {
  queryName: z.string().min(1).max(256),
  queryType: QueryType,
  connectorId: Uuid,
  outputColumnNames: uniqueStrings(z.string().min(1)),
  createdAt: DateTime,
  updatedAt: DateTime,
  credentialSurface: CredentialSurface.optional(),
  agentApp: AgentApp.optional(),
};
export const QueryRunBody = z.union([
  z.strictObject({ ...RunFields, state: z.enum(["RUNNING", "SUCCEEDED", "CANCELLED"]) }),
  z.strictObject({ ...RunFields, state: z.literal("FAILED"), failureCode: z.string().min(1).max(128), failureDetail: z.string().min(1).max(4096).optional() }),
]);
export type QueryRunBody = z.infer<typeof QueryRunBody>;

const Currency = z.string().regex(/^[A-Z]{3}$/);
const Decimals = z.number().int().min(0).max(6);
const MetricDisplaySchema = z.discriminatedUnion("style", [
  z.strictObject({ style: z.literal("NUMBER"), decimals: Decimals, unit: z.enum(["percent", "second", "millisecond", "minute", "hour", "day", "byte", "kilobyte", "megabyte", "gigabyte"]).optional() }),
  z.strictObject({ style: z.literal("CURRENCY"), decimals: Decimals, currency: Currency }),
  z.strictObject({ style: z.literal("PERCENT"), decimals: Decimals }),
]).transform((display) => display as MetricDisplay);

export const QueryResultBody = z.strictObject({
  queryType: QueryType,
  columns: z.array(z.strictObject({ name: z.string().min(1), logicalType: z.string().min(1).max(256), nullable: z.boolean(), display: MetricDisplaySchema.nullable() })),
  rows: z.array(z.array(z.union([z.string(), z.boolean(), z.null()]))),
  totalRowCount: z.string().regex(/^(?:0|[1-9][0-9]*)$/),
  returnedRowCount: z.number().int().min(0),
  truncated: z.boolean(),
  reportingTimeZone: z.string().min(1).nullable(),
});
export type QueryResultBody = z.infer<typeof QueryResultBody>;

export function resourceEnvelope<Body extends z.ZodType>(kind: string, body: Body) {
  return z.strictObject({
    requestId: RequestId,
    kind: z.literal(kind),
    path: z.string(),
    revision: RevisionString.optional(),
    body,
    availableOperations: z.array(z.unknown()).max(100).optional(),
  });
}

const DraftTarget = z.strictObject({ kind: z.literal("DRAFT"), path: z.string().regex(DRAFT_PATH), revision: RevisionString });
const SessionTarget = z.strictObject({
  kind: z.literal("SESSION"),
  path: z.string().regex(SESSION_PATH),
  variables: ValueMap,
  queryRuns: z.record(Name, SessionRunPath),
});
const LOOPBACK_HOSTNAMES = new Set(["localhost", "127.0.0.1", "[::1]"]);

// Links open Monomer over https; a local Control serves its Web origin over loopback http.
export function isMonomerWebUrl(value: string): boolean {
  let url: URL;
  try {
    url = new URL(value);
  } catch {
    return false;
  }
  return url.protocol === "https:" || (url.protocol === "http:" && LOOPBACK_HOSTNAMES.has(url.hostname));
}
const WebUrl = z.string().refine(isMonomerWebUrl);

const ShowStructuredContent = z.strictObject({
  requestId: RequestId,
  viewContract: z.literal(VIEW_CONTRACT),
  workspaceId: Uuid,
  dashboardId: Uuid,
  title: z.string().min(1),
  target: z.discriminatedUnion("kind", [DraftTarget, SessionTarget]),
  webUrl: WebUrl,
  shownAt: z.number().int().min(0),
}).refine((show) => dashboardIdOf(show.target.path) === show.dashboardId.toLowerCase()
  && (show.target.kind === "DRAFT" || Object.values(show.target.queryRuns).every((path) => path.startsWith(`${show.target.path}/query-runs/`))));
export type ShowContext = z.infer<typeof ShowStructuredContent>;
export type ShowTarget = ShowContext["target"];

function dashboardIdOf(path: string): string | null {
  return /^workspace\/dashboards\/([^/]+)\//.exec(path)?.[1] ?? null;
}

export type ShowOutcome =
  | { kind: "accepted"; show: ShowContext }
  | { kind: "out-of-date"; webUrl: string | null }
  | { kind: "failed"; message: string; webUrl: string | null };

export const SELECTION_NOT_SHOWN_MESSAGE = "This dashboard selection couldn't be shown.";

function looseWebUrl(structuredContent: unknown): string | null {
  const candidate = z.object({ webUrl: WebUrl }).safeParse(structuredContent);
  return candidate.success ? candidate.data.webUrl : null;
}

export function parseShowResult(result: { isError?: boolean; content?: unknown; structuredContent?: unknown }): ShowOutcome {
  if (result.isError === true) {
    return { kind: "failed", message: problemFromContent(result.content)?.detail ?? "Monomer couldn't show this dashboard.", webUrl: null };
  }
  const version = z.object({ viewContract: z.unknown() }).safeParse(result.structuredContent);
  if (!version.success || version.data.viewContract !== VIEW_CONTRACT) {
    return { kind: "out-of-date", webUrl: looseWebUrl(result.structuredContent) };
  }
  const show = ShowStructuredContent.safeParse(result.structuredContent);
  return show.success
    ? { kind: "accepted", show: show.data }
    : { kind: "failed", message: SELECTION_NOT_SHOWN_MESSAGE, webUrl: looseWebUrl(result.structuredContent) };
}

const Problem = z.object({
  code: z.string().optional(),
  title: z.string().optional(),
  detail: z.string().optional(),
  status: z.number().int().optional(),
});
export type ReadProblem = { code: string | null; title: string | null; detail: string | null; status: number | null };

export function problemFromContent(content: unknown): ReadProblem | null {
  const items = z.array(z.object({ type: z.string(), text: z.string().optional() })).safeParse(content);
  if (!items.success) return null;
  for (const item of items.data) {
    if (item.type !== "text" || !item.text) continue;
    const line = item.text.split("\n").reverse().find((candidate) => candidate.trimStart().startsWith("{"));
    if (!line) continue;
    try {
      const problem = Problem.safeParse(JSON.parse(line));
      if (problem.success) {
        return { code: problem.data.code ?? null, title: problem.data.title ?? null, detail: problem.data.detail ?? null, status: problem.data.status ?? null };
      }
    } catch {
      continue;
    }
  }
  return null;
}

export function resultPathOf(runPath: string): string {
  return `${runPath}/result`;
}

export function draftStatusPathOf(draftPath: string): string {
  return `${draftPath}/status`;
}
