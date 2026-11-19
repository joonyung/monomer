import { useMemo, useState } from "react";
import { BarChart3, ChevronDown, Plus, Search } from "lucide-react";
import type { SemanticModelResponse } from "@/api/semantic-model";
import type { RelationDetail } from "@/lib/types/catalog";
import type { Metric } from "@/lib/types/metric";
import { TIME_GRANULARITY_LABELS, TIME_KIND_LABELS } from "@/lib/types/semantic-model";
import { buttonClassName } from "@/components/ui/Button";
import { Input } from "@/components/ui/Input";
import { StateBlock } from "@/components/ui/StateBlock";
import { DetailPanel } from "@/components/resource-detail/DetailPanel";
import { SEMANTIC_LINK_CLASS } from "@/components/resource-detail/semantic-links";
import { fieldTextWithBreaks, IDENTIFIER_TEXT_CLASS, metricsForField } from "@/components/semantic-model/SemanticModelFieldsTable";
import { FIELD_KIND_LABELS, fieldsFromDefinition, fieldsWithSourceColumns, keyedModels, type FieldDraft, type FieldKind } from "@/components/semantic-model/semantic-model-fields";
import { aggregateFormula } from "@/components/semantics/semantic-insights";
import { Link } from "@/lib/navigation";
import { webPaths } from "@/lib/web-paths";

export interface SemanticModelFieldsProps {
  workspaceId: string;
  model: SemanticModelResponse;
  models?: SemanticModelResponse[];
  relation?: RelationDetail | null;
  metrics: Metric[] | null;
  canCreateMetric: boolean;
}

/** Read order: what identifies a row, what it joins, then what to slice by and what it adds up. A name carries its stored expression below it, so the table needs only Name and Details. */
const COLUMN_GROUPS: Array<{ title: string; kinds: FieldKind[] }> = [
  { title: "Keys", kinds: ["primary", "unique", "natural"] },
  { title: "Links to models", kinds: ["foreign"] },
  { title: "Time", kinds: ["time"] },
  { title: "Categories", kinds: ["categorical"] },
  { title: "Aggregates", kinds: ["measure"] },
];

const CELL = "px-2 py-2.5 align-top first:pl-4 last:pr-4";

export function SemanticModelFields({ workspaceId, model, models = [], relation = null, metrics, canCreateMetric }: SemanticModelFieldsProps) {
  const [filter, setFilter] = useState("");
  const [unusedOpen, setUnusedOpen] = useState(false);
  const fields = useMemo(() => {
    const saved = fieldsFromDefinition(model.entities, model.dimensions, model.measures);
    return relation ? fieldsWithSourceColumns(saved, relation, models, model.id).map((field) => ({ ...field, recommendation: undefined })) : saved;
  }, [model, models, relation]);
  const normalized = filter.trim().toLowerCase();
  const visible = fields.filter((field) => [field.name, field.label, field.expression, field.description].some((value) => value?.toLowerCase().includes(normalized)));
  const unused = visible.filter((field) => field.kind === "unused");
  const showUnused = unusedOpen || normalized.length > 0;
  const usedCount = fields.filter((field) => field.kind !== "unused").length;
  const groups = COLUMN_GROUPS.map((group) => ({ ...group, rows: visible.filter((field) => group.kinds.includes(field.kind)) })).filter((group) => group.rows.length > 0);
  const summary = normalized ? `${visible.length} of ${fields.length} model fields` : usedCount === fields.length ? `${fields.length} ${fields.length === 1 ? "model field" : "model fields"}` : `${usedCount} of ${fields.length} model fields in use`;
  return <DetailPanel id="model-columns-heading" title="Model fields" description={<span className="tabular-nums">{summary}</span>} action={canCreateMetric ? <Link href={webPaths.newMetric(workspaceId, { model: model.name })} className={buttonClassName("secondary", "sm", "")}><Plus className="h-4 w-4" />Add aggregate metric</Link> : undefined} bodyClassName="">
    <div className="border-b border-border-light px-4 py-3"><div className="relative max-w-sm"><Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-foreground-secondary" aria-hidden="true" /><Input type="search" aria-label="Filter columns" placeholder="Filter columns" value={filter} onChange={(event) => setFilter(event.target.value)} className="pl-9" /></div></div>
    {visible.length === 0 ? <div className="p-4"><StateBlock kind="empty" layout="inline" title={normalized ? `No column matches “${filter.trim()}”.` : "No fields defined."} /></div> : <div className="overflow-x-auto">
      <table className="w-full min-w-[560px] table-fixed border-collapse" aria-label="Model columns">
        <colgroup><col className="w-[64%]" /><col className="w-[36%]" /></colgroup>
        <thead><tr className="border-b border-border">{["Name", "Details"].map((heading) => <th key={heading} scope="col" className="px-2 py-2 text-left type-group text-label first:pl-4 last:pr-4">{heading}</th>)}</tr></thead>
        {groups.map((group) => <tbody key={group.title}>
          <GroupHeading title={group.title} count={group.rows.length} />
          {group.rows.map((field) => <ColumnRow key={field.id} field={field} model={model} models={models} metrics={metrics} workspaceId={workspaceId} />)}
        </tbody>)}
        {unused.length > 0 && <tbody>
          <tr className="border-b border-border-light bg-surface">
            <th colSpan={2} scope="rowgroup" className="px-4 py-1.5 text-left">
              {normalized ? <span className="type-group text-label">Not used <span className="tabular-nums">{unused.length}</span></span> : <button type="button" aria-expanded={showUnused} onClick={() => setUnusedOpen(!unusedOpen)} className="type-group inline-flex items-center gap-1.5 text-label hover:text-foreground">
                <ChevronDown className={`h-3.5 w-3.5 transition-transform duration-150 motion-reduce:transition-none ${showUnused ? "" : "-rotate-90"}`} aria-hidden="true" />
                Not used <span className="tabular-nums">{unused.length}</span>
              </button>}
            </th>
          </tr>
          {showUnused && unused.map((field) => <ColumnRow key={field.id} field={field} model={model} models={models} metrics={metrics} workspaceId={workspaceId} />)}
        </tbody>}
      </table>
    </div>}
  </DetailPanel>;
}

function GroupHeading({ title, count }: { title: string; count: number }) {
  return <tr className="border-b border-border-light bg-surface"><th colSpan={2} scope="rowgroup" className="px-4 py-1.5 text-left type-group text-label">{title} <span className="tabular-nums">{count}</span></th></tr>;
}

function ColumnRow({ field, model, models, metrics, workspaceId }: { field: FieldDraft; model: SemanticModelResponse; models: SemanticModelResponse[]; metrics: Metric[] | null; workspaceId: string }) {
  const named = field.label && field.label !== field.name;
  const expression = field.kind === "measure" ? aggregateFormula(field.aggregation ?? "sum", field.expression) : field.expression !== field.name ? field.expression : null;
  return <tr className="border-b border-border-light last:border-b-0">
    <td className={CELL}>
      <div className="flex min-w-0 flex-wrap items-baseline gap-x-2">
        <span data-field-identifier title={named ? field.label : field.name} className={`type-body min-w-0 ${field.kind === "unused" ? "text-foreground-secondary" : "text-foreground"} ${IDENTIFIER_TEXT_CLASS}`}>{named ? field.label : fieldTextWithBreaks(field.name)}</span>
        {field.nativeType && <span className="type-status shrink-0 text-foreground-secondary">{field.nativeType}</span>}
      </div>
      {named && <code data-field-identifier title={field.name} className={`type-mono block text-foreground-secondary ${IDENTIFIER_TEXT_CLASS}`}>{fieldTextWithBreaks(field.name)}</code>}
      {expression !== null && <code title={expression} className="type-mono mt-1 block whitespace-pre-wrap text-foreground-secondary [overflow-wrap:anywhere]">{expression}</code>}
      {field.partitionColumn && <p className="mt-1 type-meta text-foreground-secondary">Partition column{field.requiresPartitionFilter === true ? " · Filter required" : ""}</p>}
      {field.description && <p className="type-meta mt-1 line-clamp-2 text-foreground-secondary" title={field.description}>{field.description}</p>}
    </td>
    <td className={CELL}><ColumnDetails field={field} model={model} models={models} metrics={metrics} workspaceId={workspaceId} /></td>
  </tr>;
}

function ColumnDetails({ field, model, models, metrics, workspaceId }: { field: FieldDraft; model: SemanticModelResponse; models: SemanticModelResponse[]; metrics: Metric[] | null; workspaceId: string }) {
  if (field.kind === "primary" || field.kind === "unique" || field.kind === "natural") return <span className="type-body text-foreground">{FIELD_KIND_LABELS[field.kind]}</span>;
  if (field.kind === "time") return <span className="type-body text-foreground">{field.isPartition && <span className="type-meta block text-foreground-secondary">Snapshot partition</span>}{TIME_GRANULARITY_LABELS[field.granularity ?? "day"]}{field.timeKind && <span className="type-meta text-foreground-secondary"> · {TIME_KIND_LABELS[field.timeKind]}</span>}{model.aggregationTimeDimension === field.name && <span className="type-meta text-foreground-secondary"> · Metric time</span>}</span>;
  if (field.kind === "categorical" && field.isPartition) return <span className="type-meta text-foreground-secondary">Snapshot partition</span>;
  if (field.kind === "foreign") {
    const targets = keyedModels(models, model.id).filter(({ key }) => key.name === field.name);
    return targets.length ? <div className="space-y-1">{targets.map(({ model: target }) => <Link key={target.id} href={webPaths.semanticModel(workspaceId, target.id)} className={`type-body block [overflow-wrap:anywhere] ${SEMANTIC_LINK_CLASS}`}>{target.label || target.name}</Link>)}</div> : <span className="type-meta text-foreground-secondary">No model is keyed by {field.name}</span>;
  }
  if (field.kind !== "measure") return null;
  const fieldMetrics = metricsForField(metrics ?? [], model.name, field.name);
  const rule = field.nonAdditiveDimension;
  return <div className="space-y-1">
    {fieldMetrics.map((metric) => <Link key={metric.id} href={webPaths.metric(workspaceId, metric.id)} className="type-body flex items-baseline gap-1.5 text-foreground"><BarChart3 className="h-3.5 w-3.5 shrink-0 translate-y-0.5 text-label" aria-hidden="true" /><span className={`min-w-0 [overflow-wrap:anywhere] ${SEMANTIC_LINK_CLASS}`}>{metric.label || metric.name}</span></Link>)}
    {metrics !== null && fieldMetrics.length === 0 && <span className="type-meta text-foreground-secondary">No metric yet</span>}
    {field.aggregationTimeDimension && field.aggregationTimeDimension !== model.aggregationTimeDimension && <p className="type-meta text-foreground-secondary">Metric time: <code className="type-mono">{field.aggregationTimeDimension}</code></p>}
    {rule && <p className="type-meta text-foreground-secondary">{rule.windowChoice === "max" ? "Latest" : "Earliest"} value on {rule.name}{rule.windowGroupings?.length ? ` by ${rule.windowGroupings.join(", ")}` : ""}</p>}
  </div>;
}
