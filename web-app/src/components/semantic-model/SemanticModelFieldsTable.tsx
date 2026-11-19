import { useLayoutEffect, useRef } from "react";
import { Ellipsis, Pencil, Plus, Trash2 } from "lucide-react";
import type { SemanticModelResponse } from "@/api/semantic-model";
import type { Metric, SimpleMetricTypeParams } from "@/lib/types/metric";
import { SEMANTIC_LINK_CLASS } from "@/components/resource-detail/semantic-links";
import { Link } from "@/lib/navigation";
import { webPaths } from "@/lib/web-paths";
import type { MeasureAggregation, NonAdditiveWindowChoice, TimeGranularity, TimeKind } from "@/lib/types/semantic-model";
import { TIME_GRANULARITIES, TIME_GRANULARITY_LABELS, TIME_KINDS, TIME_KIND_LABELS } from "@/lib/types/semantic-model";
import { Button } from "@/components/ui/Button";
import { Dialog } from "@/components/ui/Dialog";
import { IconButton } from "@/components/ui/IconButton";
import { FIELD_LABEL_CLASS, Input, NativeSelect, TextArea } from "@/components/ui/Input";
import { Menu, MenuContent, MenuItem, MenuTrigger } from "@/components/ui/Menu";
import {
  AGGREGATION_LABELS,
  FIELD_KIND_LABELS,
  changeFieldKind,
  changeFieldLabel,
  keyedModels,
  type FieldDraft,
  type FieldKind,
} from "@/components/semantic-model/semantic-model-fields";

type FieldUpdate = (field: FieldDraft) => FieldDraft;

const HEADER_CELL = "px-2 py-2 text-left type-group text-label first:pl-4 last:pr-4";
const BODY_CELL = "px-2 py-2 align-top first:pl-4 last:pr-4";

export function fieldDisplayName(field: FieldDraft): string {
  return field.label || field.name;
}

/** Identifiers wrap only at underscores, so a long column name never breaks inside a word. */
export function fieldTextWithBreaks(value: string) {
  return value.split("_").flatMap((part, index) => index === 0 ? [part] : ["_", <wbr key={index} />, part]);
}

export const IDENTIFIER_TEXT_CLASS = "overflow-hidden [text-overflow:ellipsis] [overflow-wrap:normal] [word-break:normal]";

// A long identifier ends in an ellipsis while the field is at rest.
const INLINE_IDENTIFIER_CLASS = "font-mono [text-overflow:ellipsis]";

/** Messages for values edited in the details dialog. */
function detailProblems(path: string, errors: Record<string, string>): string[] {
  const named: Array<[string, string]> = [
    ["label", "Label"],
    ["description", "Description"],
    ["aggregationTimeDimension", "Metric time"],
    ["nonAdditiveDimension", "One value per period"],
  ];
  return named.flatMap(([key, subject]) => {
    const message = errors[`${path}.${key}`];
    return message ? [`${subject}: ${message.charAt(0).toLowerCase()}${message.slice(1)}`] : [];
  });
}

export interface FieldsTableProps {
  fields: FieldDraft[];
  // The filter text only words the empty result; which rows hide comes from hiddenIds.
  filterText?: string;
  paths: Map<string, string>;
  errors: Record<string, string>;
  models?: SemanticModelResponse[];
  currentModelId?: string;
  modelName?: string;
  metrics?: Metric[] | null;
  workspaceId?: string;
  // The model's Metric time; a Time row selects it and an aggregate that repeats it shows no override.
  modelTimeDimension?: string;
  onModelTimeDimensionChange?: (name: string) => void;
  onModelTimeDimensionClear?: () => void;
  // Rows the filter or In use view hides. Every row keeps its number, so "Field 12" names the same row either way.
  hiddenIds?: ReadonlySet<string>;
  onChange: (id: string, update: FieldUpdate) => void;
  onRemove: (id: string) => void;
  onOpenDetails: (id: string) => void;
  onDuplicate?: (id: string) => void;
}

export function metricsForField(metrics: readonly Metric[], modelName: string, fieldName: string): Metric[] {
  return metrics.filter((metric) => metric.type === "simple" &&
    (metric.typeParams as SimpleMetricTypeParams).semanticModelName === modelName &&
    (metric.typeParams as SimpleMetricTypeParams).measure === fieldName);
}

const AGGREGATE_OPTIONS: MeasureAggregation[] = ["sum", "count", "count_distinct", "average", "min", "max"];

function kindChoiceLabel(kind: FieldKind, aggregation: MeasureAggregation | undefined): string {
  return kind === "measure" ? `${AGGREGATION_LABELS[aggregation ?? "sum"]} → metric` : FIELD_KIND_LABELS[kind];
}

export function FieldsTable({ fields, filterText = "", hiddenIds, paths, errors, models = [], currentModelId, modelName = "", metrics = null, workspaceId = "", modelTimeDimension = "", onModelTimeDimensionChange, onModelTimeDimensionClear, onChange, onRemove, onOpenDetails, onDuplicate }: FieldsTableProps) {
  const targets = keyedModels(models, currentModelId);
  const targetKeyNames = [...new Set(targets.map(({ key }) => key.name))];
  const needle = filterText.trim();
  const matches = (field: FieldDraft) => !hiddenIds?.has(field.id);
  return (
    <div className="overflow-x-auto">
      {!fields.some(matches) && <p className="px-4 py-3 type-body text-foreground-secondary">{needle ? `No column matches “${needle}”.` : "No columns are in use yet."}</p>}
      <table className="w-full min-w-[720px] table-fixed border-collapse">
        <thead>
          <tr className="border-b border-border">
            <th scope="col" className={`${HEADER_CELL} w-[26%]`}>Column</th>
            <th scope="col" className={`${HEADER_CELL} w-[76px]`}>Type</th>
            <th scope="col" className={`${HEADER_CELL} w-[23%]`}>Name</th>
            <th scope="col" className={`${HEADER_CELL} w-[25%]`}>Use as</th>
            <th scope="col" className={`${HEADER_CELL} w-[18%]`}>Details</th>
            <th scope="col" className={`${HEADER_CELL} sticky right-0 w-11 bg-background`}><span className="sr-only">Options</span></th>
          </tr>
        </thead>
        <tbody>
          {fields.map((field, index) => {
            if (!matches(field)) return null;
            const path = paths.get(field.id) ?? "";
            const controlId = `semantic-field-${field.id}`;
            const problems = detailProblems(path, errors);
            const fieldMetrics = metricsForField(metrics ?? [], modelName, field.name);
            const fieldTargets = targets.filter(({ key }) => key.name === field.name);
            const useAs = field.kind === "measure" ? `aggregate:${field.aggregation ?? "sum"}` : field.kind;
            // A source column that is not used is not saved, so it reads as a listing until a use is chosen.
            const unusedColumn = field.kind === "unused" && field.nativeType !== undefined;
            const change = (update: FieldUpdate) => onChange(field.id, update);
            return (
              <tr key={field.id} className="border-b border-border-light last:border-b-0">
                {field.nativeType === undefined
                  ? <td colSpan={2} className={BODY_CELL}><ExpressionTextArea id={`${controlId}-expression`} aria-label={`Field ${index + 1} expression`} value={field.expression} error={errors[`${path}.expression`]} onChange={(event) => change((current) => ({ ...current, expression: event.target.value }))} /></td>
                  : <>
                    <td className={BODY_CELL}>{unusedColumn
                      ? <span className="flex min-h-8 items-center"><code data-field-identifier title={field.expression} className={`type-mono min-w-0 text-foreground-secondary ${IDENTIFIER_TEXT_CLASS}`}>{fieldTextWithBreaks(field.expression)}</code></span>
                      : <Input inline id={`${controlId}-expression`} aria-label={`Field ${index + 1} expression`} title={field.expression} value={field.expression} error={errors[`${path}.expression`]} onChange={(event) => change((current) => ({ ...current, expression: event.target.value }))} className={INLINE_IDENTIFIER_CLASS} placeholder="Column or SQL" />}</td>
                    <td className={BODY_CELL}><span className="flex h-8 items-center"><span className="type-status min-w-0 truncate text-foreground-secondary" title={field.nativeType}>{field.nativeType}</span></span></td>
                  </>}
                <td className={BODY_CELL}>
                  {!unusedColumn && <Input inline id={`${controlId}-name`} aria-label={`Field ${index + 1} name`} title={field.name} value={field.name} error={errors[`${path}.name`]} onChange={(event) => change((current) => ({ ...current, name: event.target.value, nameFollowsLabel: false }))} className={INLINE_IDENTIFIER_CLASS} placeholder="e.g. revenue" />}
                  {!unusedColumn && field.label && field.label !== field.name && <span className="type-status mt-0.5 block truncate text-foreground-secondary" title={field.label}>{field.label}</span>}
                  {field.partitionColumn && <p className="mt-1 type-status text-foreground-secondary">Partition column{field.requiresPartitionFilter === true ? " · Filter required" : ""}</p>}
                  {problems.map((problem) => <p key={problem} className="mt-1 type-meta text-error">{problem} <button type="button" className="underline" onClick={() => onOpenDetails(field.id)}>Fix in field details</button></p>)}
                </td>
                <td className={BODY_CELL}>
                  <NativeSelect id={`${controlId}-kind`} aria-label={`Field ${index + 1} use as`} className={field.kind === "unused" ? "text-foreground-secondary" : ""} value={useAs} onChange={(event) => {
                    const value = event.target.value;
                    change((current) => value.startsWith("aggregate:") ? { ...changeFieldKind(current, "measure"), aggregation: value.slice(10) as MeasureAggregation } : changeFieldKind(current, value as FieldKind));
                  }}>
                    <option value="unused">Not used</option>
                    <option value="primary">Row key</option>
                    <option value="foreign">Links to Model</option>
                    <option value="time">Time</option>
                    <option value="categorical">Category</option>
                    {AGGREGATE_OPTIONS.map((aggregation) => <option key={aggregation} value={`aggregate:${aggregation}`}>{AGGREGATION_LABELS[aggregation]} → metric</option>)}
                    {field.kind === "measure" && !AGGREGATE_OPTIONS.includes(field.aggregation ?? "sum") && <option value={useAs}>{AGGREGATION_LABELS[field.aggregation ?? "sum"]} → metric</option>}
                    {(field.kind === "unique" || field.kind === "natural") && <option value={field.kind}>{FIELD_KIND_LABELS[field.kind]}</option>}
                  </NativeSelect>
                  {field.recommendation && <p className="mt-1 type-status text-foreground-secondary">Suggested: {kindChoiceLabel(field.recommendation.kind, field.recommendation.aggregation)}</p>}
                  {field.kind === "measure" && metrics !== null && <div className="mt-1 space-y-0.5">
                    {fieldMetrics.length ? fieldMetrics.map((metric) => <Link key={metric.id} href={webPaths.metric(workspaceId, metric.id)} className={`type-meta block [overflow-wrap:anywhere] ${SEMANTIC_LINK_CLASS}`}>{metric.label || metric.name}</Link>)
                      : <span className="type-meta text-foreground-secondary">Creates {field.label || field.name || "a metric"}</span>}
                  </div>}
                </td>
                <td className={BODY_CELL}>
                  {field.kind === "foreign" ? <NativeSelect aria-label={`Field ${index + 1} links to`} value={fieldTargets.length ? field.name : ""} onChange={(event) => {
                      const keyName = event.target.value;
                      if (keyName) change((current) => ({ ...current, name: keyName, nameFollowsLabel: false }));
                    }}>
                      <option value="">{targetKeyNames.length ? "Choose a model" : "No keyed model yet"}</option>
                      {targetKeyNames.map((keyName) => <option key={keyName} value={keyName}>{targets.filter(({ key }) => key.name === keyName).map(({ model }) => model.label || model.name).join(", ")} · {keyName}</option>)}
                    </NativeSelect>
                    : field.kind === "time" ? <div className="space-y-1.5">
                      <NativeSelect id={`${controlId}-granularity`} aria-label={`Field ${index + 1} grain`} value={field.granularity ?? ""} error={errors[`${path}.granularity`]} onChange={(event) => change((current) => ({ ...current, granularity: (event.target.value || undefined) as TimeGranularity | undefined }))}>
                        <option value="">Select grain</option>
                        {TIME_GRANULARITIES.map((granularity) => <option key={granularity} value={granularity}>{TIME_GRANULARITY_LABELS[granularity]}</option>)}
                      </NativeSelect>
                      <NativeSelect id={`${controlId}-timeKind`} aria-label={`Field ${index + 1} time kind`} value={field.timeKind ?? ""} error={errors[`${path}.timeKind`]} helperText="Instant converts to the Reporting time zone. Local time is shown as stored." onChange={(event) => change((current) => ({ ...current, timeKind: (event.target.value || undefined) as TimeKind | undefined }))}>
                        <option value="">Auto (from warehouse type)</option>
                        {TIME_KINDS.map((kind) => <option key={kind} value={kind}>{TIME_KIND_LABELS[kind]}</option>)}
                      </NativeSelect>
                      {onModelTimeDimensionChange && field.name && <div className="flex flex-wrap items-center gap-x-2 gap-y-0.5 type-meta text-foreground-secondary">
                        <label className="flex items-center gap-1.5 whitespace-nowrap">
                          <input type="radio" name="semantic-model-metric-time" aria-label={`Use ${field.name} as Metric time`} checked={modelTimeDimension === field.name} onChange={() => onModelTimeDimensionChange(field.name)} className="h-3.5 w-3.5 accent-[var(--accent)]" />
                          <span aria-hidden="true">Metric time</span>
                        </label>
                        {modelTimeDimension === field.name && onModelTimeDimensionClear && <button type="button" aria-label="Clear Metric time" onClick={onModelTimeDimensionClear} className="underline underline-offset-4 hover:text-foreground">Clear</button>}
                      </div>}
                    </div>
                    : field.kind === "measure" ? <MeasureRules field={field} modelTimeDimension={modelTimeDimension} />
                    : field.kind === "unique" || field.kind === "natural" ? <span className="type-meta text-foreground-secondary">{FIELD_KIND_LABELS[field.kind]}</span> : null}
                  {(field.kind === "time" || field.kind === "categorical") && <div className="mt-2">
                    <label className="flex items-center gap-1.5 type-meta text-foreground"><input type="checkbox" aria-label={`Field ${index + 1} Snapshot partition`} checked={field.isPartition ?? false} onChange={(event) => change((current) => ({ ...current, isPartition: event.target.checked }))} className="h-3.5 w-3.5 accent-[var(--accent)]" />Snapshot partition</label>
                    <p className="mt-1 type-status text-foreground-secondary">Joins on this dimension too, for tables that repeat rows per date.</p>
                    {errors[`${path}.isPartition`] && <p className="mt-1 type-meta text-error">{errors[`${path}.isPartition`]}</p>}
                  </div>}
                </td>
                <td className={`${BODY_CELL} sticky right-0 bg-background text-right`}><FieldOptions field={field} rowNumber={index + 1} onOpenDetails={() => onOpenDetails(field.id)} onRemove={() => onRemove(field.id)} onChange={change} onDuplicate={onDuplicate ? () => onDuplicate(field.id) : undefined} /></td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}

/** A calculated field's SQL, as tall as its wrapped text so the whole expression stays readable in the row. */
function ExpressionTextArea({ error, ...props }: { id: string; "aria-label": string; value: string; error?: string; onChange: (event: React.ChangeEvent<HTMLTextAreaElement>) => void }) {
  const ref = useRef<HTMLTextAreaElement>(null);
  useLayoutEffect(() => {
    const element = ref.current;
    if (!element) return;
    const fit = () => { element.style.height = "auto"; element.style.height = `${element.scrollHeight + 2}px`; };
    fit();
    // The monospace web font wraps differently from its fallback, so refit once it has loaded.
    void document.fonts?.ready.then(fit);
    // Wrapping changes only with width; refitting on height changes would feed the observer its own resize.
    let width = element.clientWidth;
    const observer = new ResizeObserver(() => {
      if (element.clientWidth === width) return;
      width = element.clientWidth;
      window.requestAnimationFrame(fit);
    });
    observer.observe(element);
    return () => observer.disconnect();
  }, [props.value]);
  return <TextArea ref={ref} inline rows={1} error={error} {...props} className="resize-none overflow-hidden font-mono" placeholder="SQL expression" />;
}

function MeasureRules({ field, modelTimeDimension }: { field: FieldDraft; modelTimeDimension: string }) {
  const rule = field.nonAdditiveDimension;
  const timeOverride = field.aggregationTimeDimension && field.aggregationTimeDimension !== modelTimeDimension ? field.aggregationTimeDimension : null;
  if (!rule && !timeOverride) return null;
  return <div className="space-y-0.5 pt-1.5 type-meta text-foreground-secondary">
    {timeOverride && <p>Metric time: <code className="type-mono">{timeOverride}</code></p>}
    {rule && <p>{rule.windowChoice === "max" ? "Latest" : "Earliest"} value on {rule.name}{rule.windowGroupings?.length ? ` by ${rule.windowGroupings.join(", ")}` : ""}</p>}
  </div>;
}

function FieldOptions({ field, rowNumber, onOpenDetails, onRemove, onChange, onDuplicate }: { field: FieldDraft; rowNumber: number; onOpenDetails: () => void; onRemove: () => void; onChange: (update: FieldUpdate) => void; onDuplicate?: () => void }) {
  const triggerRef = useRef<HTMLButtonElement>(null);
  const detailsRequested = useRef(false);
  return <Menu modal={false}><MenuTrigger asChild><IconButton ref={triggerRef} label={`Field ${rowNumber} options`} icon={Ellipsis} /></MenuTrigger><MenuContent align="end" className="min-w-[220px]" onCloseAutoFocus={(event) => {
    if (!detailsRequested.current) return;
    detailsRequested.current = false;
    event.preventDefault();
    triggerRef.current?.focus();
    onOpenDetails();
  }}>
    <MenuItem icon={Pencil} onSelect={() => { detailsRequested.current = true; }}>{field.kind === "measure" ? "Name, description, and options" : "Name and description"}</MenuItem>
    {onDuplicate && <MenuItem icon={Plus} onSelect={onDuplicate}>Add another use</MenuItem>}
    <MenuItem onSelect={() => onChange((current) => changeFieldKind(current, "unique"))}>Use as unique key</MenuItem>
    <MenuItem onSelect={() => onChange((current) => changeFieldKind(current, "natural"))}>Use as natural key</MenuItem>
    <MenuItem icon={Trash2} onSelect={onRemove}>Remove field</MenuItem>
  </MenuContent></Menu>;
}

export interface FieldDetailsFormProps {
  field: FieldDraft;
  path: string;
  errors: Record<string, string>;
  timeFieldNames: string[];
  entityNames: string[];
  onChange: (update: FieldUpdate) => void;
}

/** A select whose current value stays visible even when it no longer names a declared field. */
function referenceOptions(names: string[], current: string | undefined) {
  const options = current && !names.includes(current) ? [current, ...names] : names;
  return options.map((name) => <option key={name} value={name}>{name}</option>);
}

export function FieldDetailsForm({ field, path, errors, timeFieldNames, entityNames, onChange }: FieldDetailsFormProps) {
  const controlId = `semantic-field-${field.id}-details`;
  const nonAdditive = field.nonAdditiveDimension;
  return (
    <div className="space-y-4">
      <Input
        id={`${controlId}-name`}
        label="Name"
        required
        value={field.name}
        error={errors[`${path}.name`]}
        helperText="Metrics and queries refer to the field by this name."
        onChange={(event) => onChange((current) => ({ ...current, name: event.target.value, nameFollowsLabel: false }))}
        placeholder="e.g. revenue"
        className="font-mono"
      />
      <Input
        id={`${controlId}-label`}
        label="Label (optional)"
        value={field.label ?? ""}
        error={errors[`${path}.label`]}
        onChange={(event) => onChange((current) => changeFieldLabel(current, event.target.value))}
        placeholder="e.g. Revenue"
      />
      <TextArea
        id={`${controlId}-description`}
        label="Description (optional)"
        value={field.description ?? ""}
        error={errors[`${path}.description`]}
        rows={3}
        onChange={(event) => onChange((current) => ({ ...current, description: event.target.value || undefined }))}
      />
      {field.kind === "measure" ? (
        <fieldset className="space-y-4 border-t border-border pt-4">
          <legend className="type-group pr-2 text-label">Advanced options</legend>
          <NativeSelect
            id={`${controlId}-aggregation`}
            label="Aggregation"
            value={field.aggregation ?? "sum"}
            onChange={(event) => onChange((current) => ({ ...current, aggregation: event.target.value as MeasureAggregation }))}
          >
            {(Object.keys(AGGREGATION_LABELS) as MeasureAggregation[]).map((aggregation) => <option key={aggregation} value={aggregation}>{AGGREGATION_LABELS[aggregation]}</option>)}
          </NativeSelect>
          <NativeSelect
            id={`${controlId}-time-axis`}
            label="Metric time"
            helperText="Uses this Time field instead of the model's Metric time."
            value={field.aggregationTimeDimension ?? ""}
            error={errors[`${path}.aggregationTimeDimension`]}
            onChange={(event) => onChange((current) => ({ ...current, aggregationTimeDimension: event.target.value || undefined }))}
          >
            <option value="">Model default</option>
            {referenceOptions(timeFieldNames, field.aggregationTimeDimension)}
          </NativeSelect>
          <NativeSelect
            id={`${controlId}-non-additive`}
            label="One value per period"
            helperText="For balances and snapshots: take one value per period along this Time field instead of summing across it."
            value={nonAdditive?.name ?? ""}
            error={errors[`${path}.nonAdditiveDimension`]}
            onChange={(event) => onChange((current) => ({
              ...current,
              nonAdditiveDimension: event.target.value
                ? { name: event.target.value, windowChoice: current.nonAdditiveDimension?.windowChoice ?? "max", ...(current.nonAdditiveDimension?.windowGroupings ? { windowGroupings: current.nonAdditiveDimension.windowGroupings } : {}) }
                : undefined,
            }))}
          >
            <option value="">None</option>
            {referenceOptions(timeFieldNames, nonAdditive?.name)}
          </NativeSelect>
          {nonAdditive ? (
            <>
              <NativeSelect
                id={`${controlId}-window-choice`}
                label="Value to take"
                value={nonAdditive.windowChoice}
                onChange={(event) => onChange((current) => current.nonAdditiveDimension ? ({ ...current, nonAdditiveDimension: { ...current.nonAdditiveDimension, windowChoice: event.target.value as NonAdditiveWindowChoice } }) : current)}
              >
                <option value="min">Earliest (min)</option>
                <option value="max">Latest (max)</option>
              </NativeSelect>
              <fieldset>
                <legend className={FIELD_LABEL_CLASS}>Group by keys (optional)</legend>
                {entityNames.length === 0 && (nonAdditive.windowGroupings ?? []).length === 0 ? (
                  <p className="type-meta text-foreground-secondary">Add a key field to group by it.</p>
                ) : (
                  <div className="space-y-1">
                    {[...new Set([...entityNames, ...(nonAdditive.windowGroupings ?? [])])].map((name) => (
                      <label key={name} className="flex items-center gap-2 type-control text-foreground">
                        <input
                          type="checkbox"
                          checked={(nonAdditive.windowGroupings ?? []).includes(name)}
                          onChange={(event) => onChange((current) => {
                            if (!current.nonAdditiveDimension) return current;
                            const groupings = (current.nonAdditiveDimension.windowGroupings ?? []).filter((grouping) => grouping !== name);
                            const nextGroupings = event.target.checked ? [...groupings, name] : groupings;
                            const { windowGroupings: _previous, ...rule } = current.nonAdditiveDimension;
                            return { ...current, nonAdditiveDimension: nextGroupings.length > 0 ? { ...rule, windowGroupings: nextGroupings } : rule };
                          })}
                          className="h-4 w-4 shrink-0 accent-[var(--accent)]"
                        />
                        <span className="font-mono">{name}</span>
                      </label>
                    ))}
                  </div>
                )}
              </fieldset>
            </>
          ) : null}
        </fieldset>
      ) : null}
    </div>
  );
}

export function FieldDetailsDialog({ field, rowNumber, onClose, ...formProps }: FieldDetailsFormProps & { rowNumber: number; onClose: () => void }) {
  return (
    <Dialog
      open
      onOpenChange={(open) => { if (!open) onClose(); }}
      size="sm"
      title={fieldDisplayName(field) || `Field ${rowNumber}`}
      description={FIELD_KIND_LABELS[field.kind]}
      closeLabel="Close field details"
      footer={<Button variant="primary" size="sm" onClick={onClose}>Done</Button>}
    >
      <FieldDetailsForm field={field} {...formProps} />
    </Dialog>
  );
}
