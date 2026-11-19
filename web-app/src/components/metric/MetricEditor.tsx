import { useEffect, useId, useRef, useState, type KeyboardEvent } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Check, ChevronDown, Network, Plus, Trash2 } from "lucide-react";
import { catalogAPI } from "@/api/catalog";
import { metricStorageDefinition } from "@/components/metric/metric-formula";
import { SemanticCalculationRail } from "@/components/semantics/SemanticCalculationRail";
import { AGGREGATION_LABELS } from "@/components/semantic-model-detail/semantic-vocabulary";
import { buildMetricDsl, metricAPI, type WriteMetricRequest } from "@/api/metric";
import { MetricDisplayFields } from "@/components/metric/MetricDisplayFields";
import type { MetricDisplay } from "@/lib/metric-display";
import { connectorsAPI } from "@/api/connectors";
import { buildSemanticModelDsl, semanticModelAPI, type SemanticModelResponse, type WriteSemanticModelRequest } from "@/api/semantic-model";
import { semanticSaveFailure } from "@/api/semantic-check";
import { Button, buttonClassName } from "@/components/ui/Button";
import { Card } from "@/components/ui/Card";
import { FIELD_LABEL_CLASS, Input, NativeSelect, TextArea } from "@/components/ui/Input";
import { StateBlock } from "@/components/ui/StateBlock";
import { EditorSection } from "@/components/editor/EditorPageFrame";
import { SemanticEditorFrame } from "@/components/semantics/SemanticPageLayout";
import { SemanticCheckPanel, useSemanticCheck } from "@/components/semantics/SemanticCheckPanel";
import { FILTER_OPERATOR_LABELS } from "@/components/metric-detail/filter-operator-labels";
import { usePrincipalId, useWorkspaceId } from "@/hooks/use-workspace";
import { refreshSemanticDefinitionReads } from "@/lib/query/semantic-definition-cache";
import { queryKeys } from "@/lib/query/keys";
import { stableSemanticMutationIntent, type SemanticMutationIntent } from "@/lib/semantic-mutation-intent";
import { webPaths } from "@/lib/web-paths";
import { Link, useRouter } from "@/lib/navigation";
import { suggestTechnicalName } from "@/lib/utils/semantic-model-utils";
import { problemSummary, problemsByField } from "@/components/semantic-model/semantic-model-validation";
import { sameEffectiveRef, validateMetricDraft } from "@/components/metric/metric-validation";
import { TIME_GRANULARITIES, TIME_GRANULARITY_LABELS, type MeasureAggregation } from "@/lib/types/semantic-model";
import type {
  ConstantProperty,
  ConversionMetricTypeParams,
  CumulativeMetricTypeParams,
  DerivedMetricTypeParams,
  FilterOperator,
  Metric,
  MetricFilter,
  MetricFilterPredicate,
  MetricRef,
  MetricType,
  MetricTypeParams,
  PeriodAggregation,
  RatioMetricTypeParams,
  SimpleMetricTypeParams,
  TimeGranularity,
} from "@/lib/types/metric";

const METRIC_TYPES: MetricType[] = ["simple", "ratio", "derived", "cumulative", "conversion"];
const METRIC_TYPE_CHOICE_LABEL: Record<MetricType, string> = {
  simple: "Add up a column",
  ratio: "Divide one metric by another",
  derived: "Write a formula",
  cumulative: "Running total or period to date",
  conversion: "Conversion between two events",
};
const CALCULATION_METHOD_DESCRIPTION: Record<MetricType, string> = {
  simple: "Choose a model column or expression and its aggregation.",
  ratio: "Choose the numerator and denominator, with their conditions.",
  derived: "Combine metric inputs, including comparisons with earlier periods.",
  cumulative: "Accumulate a metric over a window or a period to date.",
  conversion: "Connect a start event to its completion by a shared key.",
};
const WINDOW_UNITS = ["day", "week", "month", "quarter", "year"] as const;
const FILTER_OPERATORS: FilterOperator[] = ["eq", "ne", "in", "not_in", "contains", "starts_with", "like", "not_like", "gt", "gte", "lt", "lte", "between", "is_null", "is_not_null"];

// Definition paths the Check panel and save errors can point at, mapped to the control that edits them.
const FIELD_ELEMENT_IDS: Record<string, string> = {
  name: "metric-name",
  label: "metric-label",
  semanticModelName: "metric-semantic-model",
  measure: "metric-measure",
  aggregateExpression: "metric-aggregate-expression",
  expression: "metric-derived-expression",
  entity: "metric-conversion-entity",
  "numerator.metricName": "metric-input-numerator",
  "denominator.metricName": "metric-input-denominator",
  "inputMetric.metricName": "metric-input-input-metric",
  "baseMetric.metricName": "metric-input-base-metric",
  "conversionMetric.metricName": "metric-input-conversion-metric",
  display: "metric-display-style",
  "display.style": "metric-display-style",
  "display.decimals": "metric-display-decimals",
  "display.currency": "metric-display-currency",
  "display.unit": "metric-display-unit",
};

function fieldElementId(field: string): string | null {
  if (FIELD_ELEMENT_IDS[field]) return FIELD_ELEMENT_IDS[field];
  const input = /^inputMetrics\[(\d+)\]\.metricName$/.exec(field);
  if (input) return `metric-input-input-${Number(input[1]) + 1}`;
  const filter = /^filters\[(\d+)\]/.exec(field);
  if (filter) return `metric-filter-${filter[1]}-model`;
  return null;
}

function newRef(): MetricRef { return { metricName: "" }; }
let inputKeySequence = 0;
function newInputKey(): string { inputKeySequence += 1; return `metric-input-${inputKeySequence}`; }
function newPredicate(): MetricFilterPredicate { return { dimension: { semanticModelName: "", name: "" }, operator: "eq", values: [""] }; }

function typeChoiceKey(key: string, index: number): number | null {
  const count = METRIC_TYPES.length;
  if (key === "ArrowRight" || key === "ArrowDown") return (index + 1) % count;
  if (key === "ArrowLeft" || key === "ArrowUp") return (index - 1 + count) % count;
  if (key === "Home") return 0;
  if (key === "End") return count - 1;
  return null;
}

// "Invoices · Count of invoice_id", the way the measure aggregates.
function measureOptionLabel(measure: SemanticModelResponse["measures"][number]): string {
  const aggregation = measure.aggregation.replaceAll("_", " ");
  return `${measure.label || measure.name} · ${aggregation.charAt(0).toUpperCase()}${aggregation.slice(1)} of ${measure.expression}`;
}

function modelOptionLabel(model: Pick<SemanticModelResponse, "name" | "label">): string {
  return model.label ? `${model.label} · ${model.name}` : model.name;
}

export interface MetricEditorPrefill {
  // A semantic model name or id, and one of its measure names.
  model?: string;
  measure?: string;
}

/** "Create metric" on a measure opens a Simple metric over it, labelled and named after the measure. */
export function metricPrefill(
  models: readonly SemanticModelResponse[],
  prefill: MetricEditorPrefill,
): { params: SimpleMetricTypeParams; label: string; name: string } | null {
  const model = models.find((candidate) => candidate.name === prefill.model || candidate.id === prefill.model);
  if (!model) return null;
  const measure = model.measures.find((candidate) => candidate.name === prefill.measure);
  return {
    params: { semanticModelName: model.name, measure: measure?.name ?? "" },
    label: measure?.label ?? "",
    name: measure?.name ?? "",
  };
}

export interface MetricEditorProps { metricId?: string; prefill?: MetricEditorPrefill; onSaved?: (metric: Metric) => void; onCancel?: () => void }

export function metricCountedByOptions(type: MetricType, params: MetricTypeParams, models: SemanticModelResponse[], metrics: Metric[]): readonly TimeGranularity[] {
  const dayIndex = TIME_GRANULARITIES.indexOf("day");
  const sourceIndex = (metricType: MetricType, typeParams: MetricTypeParams, ancestry: Set<string>): number => {
    if (metricType === "simple") {
      const aggregate = typeParams as SimpleMetricTypeParams;
      const model = models.find((candidate) => candidate.name === aggregate.semanticModelName);
      const measure = model?.measures.find((candidate) => candidate.name === aggregate.measure);
      const timeName = measure?.aggregationTimeDimension || model?.aggregationTimeDimension;
      const timeFields = model?.dimensions.filter((dimension) => dimension.type === "time") ?? [];
      const timeField = timeName ? timeFields.find((dimension) => dimension.name === timeName) : timeFields.length === 1 ? timeFields[0] : undefined;
      return timeField?.granularity ? TIME_GRANULARITIES.indexOf(timeField.granularity) : dayIndex;
    }
    let inputs: MetricRef[];
    switch (metricType) {
      case "ratio": { const value = typeParams as RatioMetricTypeParams; inputs = [value.numerator, value.denominator]; break; }
      case "derived": inputs = (typeParams as DerivedMetricTypeParams).inputMetrics; break;
      case "cumulative": inputs = [(typeParams as CumulativeMetricTypeParams).inputMetric]; break;
      case "conversion": { const value = typeParams as ConversionMetricTypeParams; inputs = [value.baseMetric, value.conversionMetric]; break; }
    }
    return inputs.length ? Math.max(...inputs.map((input) => {
      const metric = metrics.find((candidate) => candidate.name === input.metricName);
      if (!metric || ancestry.has(metric.name)) return dayIndex;
      return sourceIndex(metric.type, metric.typeParams, new Set([...ancestry, metric.name]));
    })) : dayIndex;
  };
  return TIME_GRANULARITIES.slice(Math.min(dayIndex, sourceIndex(type, params, new Set())));
}

export function metricCountedByValue(value: TimeGranularity | "", options: readonly TimeGranularity[], savedValue: TimeGranularity | undefined): TimeGranularity | "" {
  return !value || options.includes(value) || value === savedValue ? value : "";
}

export function MetricEditor({ metricId, prefill = {}, onSaved, onCancel }: MetricEditorProps) {
  const workspaceId = useWorkspaceId();
  const principalId = usePrincipalId();
  const router = useRouter();
  const queryClient = useQueryClient();
  const isEdit = Boolean(metricId);
  const [name, setName] = useState("");
  const [label, setLabel] = useState("");
  const [description, setDescription] = useState("");
  const [type, setType] = useState<MetricType>("simple");
  const [methodOpen, setMethodOpen] = useState(!metricId);
  const [params, setParams] = useState<MetricTypeParams>({ semanticModelName: "", measure: "" });
  const [savingAggregate, setSavingAggregate] = useState(false);
  const [newAggregate, setNewAggregate] = useState(!metricId && !prefill.measure);
  const [aggregateExpression, setAggregateExpression] = useState("");
  const [aggregateAggregation, setAggregateAggregation] = useState<MeasureAggregation>("sum");
  const [filters, setFilters] = useState<MetricFilter[]>([]);
  const [timeGranularity, setTimeGranularity] = useState<TimeGranularity | "">("");
  const [display, setDisplay] = useState<MetricDisplay | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [serverFieldErrors, setServerFieldErrors] = useState<Record<string, string>>({});
  const [showValidation, setShowValidation] = useState(false);
  const [validationAttempt, setValidationAttempt] = useState(0);
  const validationSummaryRef = useRef<HTMLDivElement>(null);
  const typeChoiceRefs = useRef(new Map<MetricType, HTMLButtonElement>());
  const nameEditedRef = useRef(false);
  const labelEditedRef = useRef(false);
  const initializedScope = useRef<string | null>(null);
  const prefillApplied = useRef(false);
  const saveIntentRef = useRef<SemanticMutationIntent | null>(null);
  const dirtyRef = useRef(false);
  const markDirty = () => { dirtyRef.current = true; };

  const modelsQuery = useQuery({
    queryKey: queryKeys.semanticModels.all(workspaceId ?? "", principalId ?? ""),
    queryFn: () => semanticModelAPI.list(workspaceId),
    enabled: Boolean(workspaceId && principalId),
  });
  const metricsQuery = useQuery({
    queryKey: queryKeys.metrics.all(workspaceId ?? "", principalId ?? ""),
    queryFn: () => metricAPI.list(workspaceId),
    enabled: Boolean(workspaceId && principalId),
  });
  const metricQuery = useQuery({
    queryKey: queryKeys.metrics.detail(workspaceId ?? "", principalId ?? "", metricId ?? ""),
    queryFn: () => metricAPI.get(workspaceId, metricId as string),
    enabled: Boolean(workspaceId && principalId && metricId),
  });
  const connectorsQuery = useQuery({
    queryKey: queryKeys.connectors.list(workspaceId ?? "", principalId ?? ""),
    queryFn: () => connectorsAPI.list({ workspaceId }),
    enabled: Boolean(workspaceId && principalId),
  });
  const models: SemanticModelResponse[] = modelsQuery.data ?? [];
  const metrics = (metricsQuery.data ?? []).filter((candidate) => candidate.id !== metricId);
  const countedByOptions = metricCountedByOptions(type, params, models, metrics);
  const savedTimeGranularity = metricQuery.data?.timeGranularity;
  const timeMetadataReady = modelsQuery.data !== undefined && (type === "simple" || metricsQuery.data !== undefined);
  const countedByValue = timeMetadataReady ? metricCountedByValue(timeGranularity, countedByOptions, savedTimeGranularity) : timeGranularity;

  useEffect(() => {
    if (countedByValue !== timeGranularity) setTimeGranularity(countedByValue);
  }, [countedByValue, timeGranularity]);

  useEffect(() => {
    if (!metricQuery.data || !workspaceId || !principalId || !metricId) return;
    const scope = `${workspaceId}:${principalId}:${metricId}`;
    if (initializedScope.current === scope) return;
    initializedScope.current = scope;
    const metric = metricQuery.data;
    setName(metric.name);
    setLabel(metric.label ?? "");
    setDescription(metric.description ?? "");
    setType(metric.type);
    setParams(metric.typeParams);
    setFilters(metric.filters ?? []);
    setTimeGranularity(metric.timeGranularity ?? "");
    setDisplay(metric.display ?? null);
    dirtyRef.current = false;
  }, [metricId, metricQuery.data, principalId, workspaceId]);

  useEffect(() => {
    if (isEdit || prefillApplied.current || !prefill.model || !modelsQuery.data) return;
    prefillApplied.current = true;
    const prefilled = metricPrefill(modelsQuery.data, prefill);
    if (!prefilled) return;
    setType("simple");
    setParams(prefilled.params);
    if (prefilled.name) {
      setLabel(prefilled.label);
      setName(prefilled.name);
    }
  }, [isEdit, modelsQuery.data, prefill]);

  useEffect(() => {
    const handler = (event: BeforeUnloadEvent) => {
      if (!dirtyRef.current) return;
      event.preventDefault();
    };
    window.addEventListener("beforeunload", handler);
    return () => window.removeEventListener("beforeunload", handler);
  }, []);

  useEffect(() => {
    if (validationAttempt > 0) validationSummaryRef.current?.focus();
  }, [validationAttempt]);

  const selectType = (next: MetricType) => {
    if (next === type) return;
    dirtyRef.current = true;
    setShowValidation(false);
    setError(null);
    setType(next);
    setNewAggregate(next === "simple" && !isEdit);
    switch (next) {
      case "simple": setParams({ semanticModelName: "", measure: "" }); break;
      case "ratio": setParams({ numerator: newRef(), denominator: newRef() }); break;
      case "derived": setParams({ expression: "", inputMetrics: [newRef()] }); break;
      case "cumulative": setParams({ inputMetric: newRef() }); break;
      case "conversion": setParams({ entity: "", calculation: "conversion_rate", baseMetric: newRef(), conversionMetric: newRef() }); break;
    }
  };

  const saveMutation = useMutation({
    mutationFn: async (input: { request: WriteMetricRequest; idempotencyKey: string } | { modelId: string; request: WriteSemanticModelRequest; idempotencyKey: string }) => {
      if ("modelId" in input) {
        const saved = await semanticModelAPI.update(workspaceId, input.modelId, input.request, input.idempotencyKey);
        const metric = saved.aggregateMetrics[0];
        if (!metric) throw new Error("The saved aggregate metric was not returned.");
        return metric;
      }
      return isEdit && metricId ? metricAPI.update(workspaceId, metricId, input.request, input.idempotencyKey) : metricAPI.create(workspaceId, input.request, input.idempotencyKey);
    },
    onSuccess: async () => {
      if (!workspaceId || !principalId) return;
      await refreshSemanticDefinitionReads(queryClient, workspaceId, principalId);
    },
  });

  const storage = metricStorageDefinition(type, params);
  const aggregateParams = type === "simple" && newAggregate ? { ...(params as SimpleMetricTypeParams), measure: name.trim() } : params;
  const problems = validateMetricDraft({ name, type, params: aggregateParams, filters });
  if (type === "derived" && storage.type === "ratio") {
    const ratio = storage.typeParams as RatioMetricTypeParams;
    if (sameEffectiveRef(ratio.numerator, ratio.denominator)) problems.push({ field: "expression", subject: "Expression", message: "Numerator and denominator must differ in metric, conditions, or offset." });
  }
  if (type === "simple" && newAggregate && !aggregateExpression.trim()) problems.push({ field: "aggregateExpression", subject: "Column or expression", message: "Enter a column or expression." });
  const visibleProblems = showValidation ? problems : [];
  const fieldErrors = { ...serverFieldErrors, ...problemsByField(visibleProblems) };
  const request: WriteMetricRequest = {
    name: name.trim(),
    ...(label.trim() ? { label: label.trim() } : {}),
    ...(description.trim() ? { description: description.trim() } : {}),
    type: storage.type,
    typeParams: type === "simple" ? aggregateParams : storage.typeParams,
    ...(filters.length ? { filters } : {}),
    ...(countedByValue ? { timeGranularity: countedByValue } : {}),
    ...(display && !(type === "simple" && newAggregate) ? { display } : {}),
  };
  const savedMetric = metricQuery.data;
  const displayOnly = Boolean(savedMetric && JSON.stringify(savedMetric.display ?? null) !== JSON.stringify(request.display ?? null)
    && JSON.stringify(buildMetricDsl({ ...savedMetric, display: null })) === JSON.stringify(buildMetricDsl({ ...request, display: null })));
  const check = useSemanticCheck(problems.length === 0 && !(type === "simple" && newAggregate) ? { kind: "METRIC", metricId: metricId ?? null, request } : null);
  const goToField = (field: string) => {
    const element = document.getElementById(fieldElementId(field) ?? "");
    element?.scrollIntoView({ block: "center" });
    element?.focus();
  };

  const handleSave = async () => {
    check.checkNow();
    if (problems.length > 0) {
      setError(null);
      setShowValidation(true);
      setValidationAttempt((attempt) => attempt + 1);
      return;
    }
    setShowValidation(false);
    setError(null);
    setServerFieldErrors({});
    setSavingAggregate(type === "simple" && newAggregate);
    try {
      let input: { request: WriteMetricRequest; idempotencyKey: string } | { modelId: string; request: WriteSemanticModelRequest; idempotencyKey: string };
      if (type === "simple" && newAggregate) {
        const simple = aggregateParams as SimpleMetricTypeParams;
        const selectedModel = models.find((model) => model.name === simple.semanticModelName);
        if (!selectedModel) throw new Error("Select a model before saving.");
        const currentModel = await semanticModelAPI.getById(workspaceId, selectedModel.id);
        const existing = currentModel.measures.find((measure) => measure.name === simple.measure);
        if (existing && (existing.expression !== aggregateExpression.trim() || existing.aggregation !== aggregateAggregation || existing.nonAdditiveDimension || existing.aggregationTimeDimension)) {
          setError("This model already has an aggregate with this name. Choose it above, or enter a different metric name.");
          return;
        }
        const modelDefinition = { ...currentModel, measures: existing ? currentModel.measures : [...currentModel.measures, { name: simple.measure, expression: aggregateExpression.trim(), aggregation: aggregateAggregation, ...(label.trim() ? { label: label.trim() } : {}) }] };
        const modelRequest: WriteSemanticModelRequest = {
          definition: modelDefinition,
          aggregateMetrics: [{ measure: simple.measure, name: request.name, label: request.label, description: request.description, filters: request.filters, timeGranularity: request.timeGranularity }],
        };
        const intent = stableSemanticMutationIntent(saveIntentRef.current, JSON.stringify({ definition: buildSemanticModelDsl(modelDefinition), aggregateMetrics: modelRequest.aggregateMetrics }));
        saveIntentRef.current = intent;
        input = { modelId: currentModel.id, request: modelRequest, idempotencyKey: intent.idempotencyKey };
      } else {
        const intent = stableSemanticMutationIntent(saveIntentRef.current, JSON.stringify(request));
        saveIntentRef.current = intent;
        input = { request, idempotencyKey: intent.idempotencyKey };
      }
      const saved = await saveMutation.mutateAsync(input);
      saveIntentRef.current = null;
      dirtyRef.current = false;
      if (onSaved) onSaved(saved); else router.push(webPaths.metric(workspaceId, saved.id));
    } catch (cause) {
      const failure = semanticSaveFailure(cause);
      setError(failure.message);
      setServerFieldErrors(Object.fromEntries(Object.entries(failure.fieldErrors).map(([field, message]) => [field.replace(/^aggregateMetrics\[0\]\./, "").replace(/^measures\[\d+\]\.expression$/, "aggregateExpression").replace(/^measures\[\d+\]\.name$|^measure$/, "name"), message])));
    } finally { setSavingAggregate(false); }
  };

  if (metricQuery.isPending && isEdit) return <StateBlock kind="loading" layout="page" title="Loading metric" />;
  if (metricQuery.error && isEdit) return <StateBlock kind="error" layout="page" title={metricQuery.error.message} />;

  if (!isEdit && modelsQuery.isSuccess && models.length === 0) {
    return (
      <div className="flex h-full flex-col bg-background">
        <StateBlock
          kind="empty"
          layout="page"
          icon={Network}
          headingLevel={1}
          title="Create a model first"
          description="Metrics turn model columns into business numbers."
          action={<>
            <Link href={webPaths.metricCollection(workspaceId)} className={buttonClassName("secondary", "sm", "")}>Back to Semantics</Link>
            <Link href={webPaths.newSemanticModel(workspaceId)} className={buttonClassName("primary", "sm", "")}><Plus aria-hidden="true" />New model</Link>
          </>}
        />
      </div>
    );
  }

  const changeLabel = (nextLabel: string) => {
    markDirty();
    labelEditedRef.current = true;
    setLabel(nextLabel);
    if (!isEdit && !nameEditedRef.current) setName(suggestTechnicalName(nextLabel));
  };
  const onTypeKeyDown = (event: KeyboardEvent<HTMLButtonElement>, index: number) => {
    const nextIndex = typeChoiceKey(event.key, index);
    if (nextIndex === null) return;
    event.preventDefault();
    const next = METRIC_TYPES[nextIndex];
    selectType(next);
    typeChoiceRefs.current.get(next)?.focus();
  };
  const changeSimpleParams = (next: SimpleMetricTypeParams) => {
    markDirty();
    setParams(next);
    if (isEdit || labelEditedRef.current || nameEditedRef.current) return;
    const measure = models.find((model) => model.name === next.semanticModelName)?.measures.find((candidate) => candidate.name === next.measure);
    if (!measure) return;
    setLabel(measure.label ?? "");
    setName(measure.name);
  };

  const checkPanel = <SemanticCheckPanel mode="edit" state={check} connectors={connectorsQuery.data ?? []} incompleteMessage={newAggregate && type === "simple" && (params as SimpleMetricTypeParams).semanticModelName ? "The aggregate and metric are saved together. Check the saved metric after saving." : "Complete the required fields to check this metric."} onGoToField={goToField} canGoToField={(field) => fieldElementId(field) !== null} />;
  return <SemanticEditorFrame
    backHref={isEdit && metricId ? webPaths.metric(workspaceId, metricId) : webPaths.metricCollection(workspaceId)}
    backLabel={isEdit ? "Back to metric" : "Semantics"}
    title={isEdit ? metricQuery.data?.label || metricQuery.data?.name || "Metric" : label ? label : "New metric"}
    saving={saveMutation.isPending || savingAggregate}
    saveError={error}
    onSave={handleSave}
    onCancel={onCancel}
    aside={isEdit && metricId ? <SemanticCalculationRail workspaceId={workspaceId} principalId={principalId ?? ""} kind="METRIC" id={metricId} editing displayOnly={displayOnly}>{checkPanel}</SemanticCalculationRail> : checkPanel}
  >
    {visibleProblems.length > 0 && (
      <div ref={validationSummaryRef} tabIndex={-1} role="alert" aria-label="Metric validation errors" className="rounded-[6px] border border-error/30 bg-error/5 p-4 type-body text-error outline-none">
        <p>Fix the following before saving:</p>
        <ul className="mt-2 list-disc space-y-1 pl-5">
          {visibleProblems.map((problem) => <li key={`${problem.field}-${problem.message}`}>{problemSummary(problem)}</li>)}
        </ul>
      </div>
    )}
    <EditorSection title="How do you want to calculate it?" description={methodOpen ? null : "Changing the method starts its definition over."} action={null}>
      {methodOpen ? <>
      <div role="radiogroup" aria-label="Calculation method" className="grid gap-3 sm:grid-cols-2 2xl:grid-cols-3">
        {METRIC_TYPES.map((candidate, index) => {
          const checked = type === candidate;
          return <button key={candidate} ref={(node) => { if (node) typeChoiceRefs.current.set(candidate, node); else typeChoiceRefs.current.delete(candidate); }} type="button" role="radio" aria-checked={checked} tabIndex={checked ? 0 : -1} onClick={() => selectType(candidate)} onKeyDown={(event) => onTypeKeyDown(event, index)} className={`relative flex flex-col overflow-hidden rounded-[6px] border border-border p-3 text-left focus-visible:outline focus-visible:outline-[2px] focus-visible:outline-offset-2 focus-visible:outline-focus-ring ${checked ? "bg-selection-tint" : "bg-background hover:bg-surface"}`}>{checked ? <span className="absolute inset-y-0 left-0 w-[3px] bg-accent-marker" aria-hidden="true" /> : null}<span className="flex items-center justify-between gap-2"><span className="type-body text-foreground">{METRIC_TYPE_CHOICE_LABEL[candidate]}</span>{checked ? <Check className="h-4 w-4 flex-none text-focus-ring" aria-hidden="true" /> : null}</span><span className="type-meta mt-1 block text-foreground-secondary">{CALCULATION_METHOD_DESCRIPTION[candidate]}</span></button>;
        })}
      </div>
      </> : <div className="flex flex-wrap items-center justify-between gap-3">
        <div className="min-w-0"><p className="type-body text-foreground">{METRIC_TYPE_CHOICE_LABEL[type]}</p><p className="type-meta mt-0.5 text-foreground-secondary">{CALCULATION_METHOD_DESCRIPTION[type]}</p></div>
        <Button variant="secondary" size="sm" onClick={() => { setMethodOpen(true); window.requestAnimationFrame(() => typeChoiceRefs.current.get(type)?.focus()); }}>Change method</Button>
      </div>}
    </EditorSection>
    <EditorSection title="Definition" description={null} action={null}>
      {type === "simple" ? <AggregateDefinition params={params as SimpleMetricTypeParams} errors={fieldErrors} setParams={changeSimpleParams} models={models} newAggregate={newAggregate} onNewAggregate={(next) => { markDirty(); setNewAggregate(next); }} expression={aggregateExpression} onExpression={(next) => { markDirty(); setAggregateExpression(next); }} aggregation={aggregateAggregation} onAggregation={(next) => { markDirty(); setAggregateAggregation(next); }} allowNew={!isEdit} /> : <MetricDefinition type={type} params={params} errors={fieldErrors} setParams={(next) => { markDirty(); setParams(next); }} models={models} metrics={metrics} />}
      {type === "derived" && storage.type === "ratio" && <p className="type-meta mt-3 text-foreground-secondary">This simple division will be saved as a ratio. Input conditions and offsets are kept.</p>}
      {type === "cumulative" && <Link href={`${webPaths.metricCollection(workspaceId)}?view=setup`} className="type-meta mt-3 inline-block underline underline-offset-4">Set up a Calendar table if this running total needs one.</Link>}
    </EditorSection>
    <EditorSection title="Identity" description="Name is used in queries and can't be changed after the metric is created." action={null}>
      <div className="grid gap-4 md:grid-cols-2">
        <div><label htmlFor="metric-label" className={FIELD_LABEL_CLASS}>Label <span className="text-foreground-secondary">(optional)</span></label><Input id="metric-label" value={label} error={fieldErrors.label} onChange={(event) => changeLabel(event.target.value)} placeholder="e.g. Gross revenue" /></div>
        {isEdit ? (
          <div><span className={FIELD_LABEL_CLASS}>Name</span><p id="metric-name" tabIndex={-1} className="type-mono flex h-8 items-center text-foreground">{name}</p>{fieldErrors.name ? <p className="mt-1.5 text-xs text-error">{fieldErrors.name}</p> : null}</div>
        ) : (
          <div><label htmlFor="metric-name" className={FIELD_LABEL_CLASS}>Name <span className="text-error">*</span></label><Input id="metric-name" value={name} error={fieldErrors.name} onChange={(event) => { markDirty(); nameEditedRef.current = true; setName(event.target.value); }} placeholder="e.g. gross_revenue" /></div>
        )}
        <div className="md:col-span-2"><label htmlFor="metric-description" className={FIELD_LABEL_CLASS}>Description <span className="text-foreground-secondary">(optional)</span></label><TextArea id="metric-description" value={description} onChange={(event) => { markDirty(); setDescription(event.target.value); }} rows={2} /></div>
      </div>
    </EditorSection>
    <EditorSection title="Filters" description="Applied every time this metric is queried." framed={filters.length > 0} action={<Button variant="secondary" size="sm" onClick={() => { markDirty(); setFilters((current) => [...current, newPredicate()]); }}><Plus />Add condition</Button>}>
      <div className="space-y-3">
        {fieldErrors.filters ? <p className="type-meta text-error">{fieldErrors.filters}</p> : null}
        {filters.map((filter, index) => <FilterNodeEditor key={index} idPrefix={`metric-filter-${index}`} node={filter} models={models} onChange={(next) => { markDirty(); setFilters((current) => current.map((candidate, candidateIndex) => candidateIndex === index ? next : candidate)); }} onRemove={() => { markDirty(); setFilters((current) => current.filter((_, candidateIndex) => candidateIndex !== index)); }} />)}
        {filters.length === 0 && <p className="type-body text-foreground-secondary">No filters.</p>}
      </div>
    </EditorSection>
    <EditorSection title="Display" description="New runs use this format. The original values stay the same." action={null}>
      <MetricDisplayFields value={type === "simple" && newAggregate ? null : display} disabled={type === "simple" && newAggregate} onChange={(next) => { markDirty(); setDisplay(next); }} />
    </EditorSection>
    <EditorSection title="Time" description="Used when a query over time doesn't choose a grain." action={null}>
      <NativeSelect label="Default grain" value={countedByValue} onChange={(event) => { markDirty(); setTimeGranularity(event.target.value as TimeGranularity | ""); }} className="max-w-sm"><option value="">None</option>{countedByValue && countedByValue === savedTimeGranularity && !countedByOptions.includes(countedByValue) && <option value={countedByValue}>{TIME_GRANULARITY_LABELS[countedByValue]} (saved)</option>}{countedByOptions.map((granularity) => <option key={granularity} value={granularity}>{TIME_GRANULARITY_LABELS[granularity]}</option>)}</NativeSelect>
    </EditorSection>
  </SemanticEditorFrame>;
}


function AggregateDefinition({ params, errors, setParams, models, newAggregate, onNewAggregate, expression, onExpression, aggregation, onAggregation, allowNew }: { params: SimpleMetricTypeParams; errors: FieldErrors; setParams: (params: SimpleMetricTypeParams) => void; models: SemanticModelResponse[]; newAggregate: boolean; onNewAggregate: (next: boolean) => void; expression: string; onExpression: (next: string) => void; aggregation: MeasureAggregation; onAggregation: (next: MeasureAggregation) => void; allowNew: boolean }) {
  const workspaceId = useWorkspaceId();
  const principalId = usePrincipalId();
  const selected = models.find((model) => model.name === params.semanticModelName);
  const source = selected?.sourceRelations[0];
  const columns = useQuery({ queryKey: queryKeys.catalog.relation(workspaceId, principalId ?? "", source?.connectorId ?? "", source?.catalog ?? null, source?.schema ?? "", source?.relation ?? ""), queryFn: () => catalogAPI.getRelation(workspaceId, source!.connectorId, source!.relation, source!.schema, source!.catalog), enabled: Boolean(newAggregate && source && principalId) });
  return <div className="space-y-4"><NativeSelect id="metric-semantic-model" label="Model" value={params.semanticModelName} error={errors.semanticModel ?? errors.semanticModelName} onChange={(event) => setParams({ semanticModelName: event.target.value, measure: "" })}><option value="">Select model</option>{params.semanticModelName && !selected && <option value={params.semanticModelName}>{params.semanticModelName} (not found)</option>}{models.map((model) => <option key={model.id} value={model.name}>{modelOptionLabel(model)}</option>)}</NativeSelect>{selected && <><NativeSelect id="metric-measure" label="Aggregate metric" value={newAggregate ? "__new__" : params.measure} error={errors.measure} onChange={(event) => { onNewAggregate(event.target.value === "__new__"); setParams({ ...params, measure: event.target.value === "__new__" ? "" : event.target.value }); }}><option value="">Select aggregate</option>{allowNew && <option value="__new__">New aggregate from a column or expression</option>}{params.measure && !selected.measures.some((item) => item.name === params.measure) && <option value={params.measure}>{params.measure} (not found)</option>}{selected.measures.map((measure) => <option key={measure.name} value={measure.name}>{measureOptionLabel(measure)}</option>)}</NativeSelect>{newAggregate && <><Input label="Column or expression" id="metric-aggregate-expression" list="metric-aggregate-columns" value={expression} error={errors.aggregateExpression} onChange={(event) => onExpression(event.target.value)} placeholder="e.g. total_amount" /><datalist id="metric-aggregate-columns">{columns.data?.columns.map((column) => <option key={column.name} value={column.name} />)}</datalist><NativeSelect label="Aggregation" value={aggregation} onChange={(event) => onAggregation(event.target.value as MeasureAggregation)}>{Object.entries(AGGREGATION_LABELS).map(([value, label]) => <option key={value} value={value}>{label}</option>)}</NativeSelect>{columns.isError && <p className="type-meta text-error">Source columns could not be loaded. You can still enter an expression.</p>}<p className="type-meta text-foreground-secondary">Saving adds this aggregate to the model and creates its metric. The metric name below names both definitions.</p></>}</>}</div>;
}

type FieldErrors = Record<string, string>;
type MetricChoice = Pick<Metric, "name" | "label" | "type" | "typeParams">;

function MetricDefinition({ type, params, errors, setParams, models, metrics }: { type: Exclude<MetricType, "simple">; params: MetricTypeParams; errors: FieldErrors; setParams: (params: MetricTypeParams) => void; models: SemanticModelResponse[]; metrics: MetricChoice[] }) {

  if (type === "ratio") { const value = params as RatioMetricTypeParams; return <div className="grid gap-5 md:grid-cols-2"><MetricInput id="metric-input-numerator" label="Numerator" error={errors.numerator ?? errors["numerator.metricName"]} refValue={value.numerator} onChange={(numerator) => setParams({ ...value, numerator })} metrics={metrics} models={models} /><MetricInput id="metric-input-denominator" label="Denominator" error={errors.denominator ?? errors["denominator.metricName"]} refValue={value.denominator} onChange={(denominator) => setParams({ ...value, denominator })} metrics={metrics} models={models} /></div>; }
  if (type === "derived") return <DerivedDefinition params={params as DerivedMetricTypeParams} errors={errors} setParams={setParams} metrics={metrics} models={models} />;
  if (type === "cumulative") { const value = params as CumulativeMetricTypeParams; return <div className="space-y-5"><MetricInput id="metric-input-input-metric" label="Input metric" error={errors.inputMetric ?? errors["inputMetric.metricName"]} refValue={value.inputMetric} onChange={(inputMetric) => setParams({ ...value, inputMetric })} metrics={metrics} models={models} /><WindowFields window={value.window} grainToDate={value.grainToDate} allowGrainToDate error={errors.window} onChange={(patch) => setParams({ ...value, ...patch })} /><NativeSelect label="Period aggregation" value={value.periodAggregation ?? ""} onChange={(event) => setParams({ ...value, periodAggregation: (event.target.value || undefined) as PeriodAggregation | undefined })} className="max-w-xs"><option value="">Default (first)</option><option value="first">First</option><option value="last">Last</option><option value="average">Average</option></NativeSelect></div>; }
  const value = params as ConversionMetricTypeParams;
  const entities = conversionEntityChoices(value, metrics, models);
  return <div className="space-y-5">
    <div className="grid gap-5 md:grid-cols-2"><MetricInput id="metric-input-base-metric" label="Base metric" error={errors.baseMetric ?? errors["baseMetric.metricName"]} refValue={value.baseMetric} onChange={(baseMetric) => setParams({ ...value, baseMetric })} metrics={metrics} models={models} /><MetricInput id="metric-input-conversion-metric" label="Conversion metric" error={errors.conversionMetric ?? errors["conversionMetric.metricName"]} refValue={value.conversionMetric} onChange={(conversionMetric) => setParams({ ...value, conversionMetric })} metrics={metrics} models={models} /></div>
    <NativeSelect id="metric-conversion-entity" label="Key" helperText="The key that links a base event to its conversion, such as a customer." value={value.entity} error={errors.entity} onChange={(event) => setParams({ ...value, entity: event.target.value })} className="max-w-sm">
      <option value="">Select key</option>
      {value.entity && !entities.some((entity) => entity.name === value.entity) ? <option value={value.entity}>{value.entity}</option> : null}
      {entities.map((entity) => <option key={entity.name} value={entity.name}>{entity.label}</option>)}
    </NativeSelect>
    <NativeSelect label="Calculation" value={value.calculation} onChange={(event) => setParams({ ...value, calculation: event.target.value as ConversionMetricTypeParams["calculation"] })} className="max-w-sm"><option value="conversion_rate">Conversion rate</option><option value="conversions">Conversions</option></NativeSelect>
    <WindowFields window={value.window} allowGrainToDate={false} error={errors.window} onChange={(patch) => setParams({ ...value, ...patch })} />
    <ConstantProperties value={value.constantProperties ?? []} error={errors.constantProperties} onChange={(constantProperties) => setParams({ ...value, constantProperties })} />
  </div>;
}

// Entities of the models behind the base and conversion metrics when they are Simple; otherwise every model's entities.
function conversionEntityChoices(value: ConversionMetricTypeParams, metrics: MetricChoice[], models: SemanticModelResponse[]): Array<{ name: string; label: string }> {
  const modelNameOf = (ref: MetricRef) => {
    const metric = metrics.find((candidate) => candidate.name === ref.metricName);
    return metric?.type === "simple" ? (metric.typeParams as SimpleMetricTypeParams).semanticModelName : null;
  };
  const inputModels = [modelNameOf(value.baseMetric), modelNameOf(value.conversionMetric)].filter((modelName): modelName is string => Boolean(modelName));
  const scoped = inputModels.length > 0 ? models.filter((model) => inputModels.includes(model.name)) : models;
  const choices = new Map<string, string>();
  for (const model of scoped) {
    for (const entity of model.entities) choices.set(entity.name, choices.has(entity.name) ? entity.name : `${entity.label ? `${entity.label} · ` : ""}${entity.name}`);
  }
  return [...choices.entries()].map(([entityName, entityLabel]) => ({ name: entityName, label: entityLabel })).sort((left, right) => left.name.localeCompare(right.name));
}

function DerivedDefinition({ params, errors, setParams, metrics, models }: { params: DerivedMetricTypeParams; errors: FieldErrors; setParams: (params: DerivedMetricTypeParams) => void; metrics: MetricChoice[]; models: SemanticModelResponse[] }) {
  const [inputKeys, setInputKeys] = useState(() => params.inputMetrics.map(() => newInputKey()));
  if (inputKeys.length !== params.inputMetrics.length) setInputKeys(params.inputMetrics.map(() => newInputKey()));
  const updateInput = (index: number, patch: Partial<MetricRef>) => setParams({ ...params, inputMetrics: params.inputMetrics.map((input, inputIndex) => inputIndex === index ? { ...input, ...patch } : input) });
  const addInput = () => {
    setInputKeys([...inputKeys, newInputKey()]);
    setParams({ ...params, inputMetrics: [...params.inputMetrics, newRef()] });
  };
  const removeInput = (index: number) => {
    setInputKeys(inputKeys.filter((_, keyIndex) => keyIndex !== index));
    setParams({ ...params, inputMetrics: params.inputMetrics.filter((_, inputIndex) => inputIndex !== index) });
  };
  const removable = params.inputMetrics.length > 1;
  return <div className="space-y-4"><div className="flex items-center justify-between"><span className="type-body text-foreground">Input metrics</span><Button variant="secondary" size="sm" onClick={addInput}><Plus />Add input</Button></div>{errors.inputs ? <p className="type-meta text-error">{errors.inputs}</p> : null}{params.inputMetrics.map((input, index) => <MetricInput key={inputKeys[index] ?? index} id={`metric-input-input-${index + 1}`} label={`Input ${index + 1}`} error={errors[`input-${index}`] ?? errors[`inputMetrics[${index}].metricName`]} refValue={input} onChange={(next) => updateInput(index, next)} metrics={metrics} models={models} showAlias removeLabel={removable ? `Remove input ${index + 1}` : undefined} onRemove={() => removeInput(index)} />)}<TextArea label="Expression" required id="metric-derived-expression" value={params.expression} error={errors.expression} onChange={(event) => setParams({ ...params, expression: event.target.value })} rows={3} className="font-mono" placeholder="(current - prior) / prior" helperText="Refer to inputs by name or alias." /></div>;
}

function MetricInput({ id, label, error, refValue, onChange, metrics, models, showAlias = true, removeLabel, onRemove }: { id: string; label: string; error?: string; refValue: MetricRef; onChange: (value: MetricRef) => void; metrics: MetricChoice[]; models: SemanticModelResponse[]; showAlias?: boolean; removeLabel?: string; onRemove?: () => void }) {
  const hasAdvanced = Boolean(refValue.alias || refValue.filter || refValue.offsetWindow || refValue.offsetToGrain);
  const [advancedOpen, setAdvancedOpen] = useState(hasAdvanced);
  const advancedId = useId();
  const known = !refValue.metricName || metrics.some((metric) => metric.name === refValue.metricName);
  return <Card padding="md" className="space-y-3">
    <div className="flex items-start gap-2"><div className="min-w-0 flex-1"><NativeSelect id={id} label={label} value={refValue.metricName} error={error} onChange={(event) => onChange({ ...refValue, metricName: event.target.value })}>
      <option value="">Select metric</option>
      {known ? null : <option value={refValue.metricName}>{refValue.metricName} (not found)</option>}
      {metrics.map((metric) => <option key={metric.name} value={metric.name}>{metric.label ? `${metric.label} · ${metric.name}` : metric.name}</option>)}
    </NativeSelect></div>{removeLabel && onRemove ? <Button variant="ghost" size="sm" aria-label={removeLabel} className="mt-[22px]" onClick={onRemove}><Trash2 aria-hidden="true" /></Button> : null}</div>
    <button type="button" aria-expanded={advancedOpen} aria-controls={advancedId} onClick={() => setAdvancedOpen(!advancedOpen)} className="type-meta inline-flex items-center gap-1.5 text-foreground-secondary hover:text-foreground focus-visible:outline focus-visible:outline-[2px] focus-visible:outline-offset-2 focus-visible:outline-focus-ring">
      <ChevronDown className={`h-3.5 w-3.5 transition-transform duration-150 motion-reduce:transition-none ${advancedOpen ? "rotate-180" : ""}`} aria-hidden="true" />
      Advanced options
    </button>
    {advancedOpen ? <div id={advancedId} className="space-y-3">
      {showAlias && <Input label="Alias (optional)" value={refValue.alias ?? ""} onChange={(event) => onChange({ ...refValue, alias: event.target.value || undefined })} className="font-mono" placeholder={refValue.metricName || "input"} />}
      <div className="grid gap-3 sm:grid-cols-2">
        <Input label="Offset window" value={refValue.offsetWindow ?? ""} onChange={(event) => onChange({ ...refValue, offsetWindow: event.target.value || undefined })} placeholder="e.g. 1 month" helperText="Needs a Calendar table." />
        <NativeSelect label="Offset to start of" value={refValue.offsetToGrain ?? ""} helperText="Needs a Calendar table." onChange={(event) => onChange({ ...refValue, offsetToGrain: (event.target.value || undefined) as TimeGranularity | undefined })}><option value="">None</option>{TIME_GRANULARITIES.map((granularity) => <option key={granularity} value={granularity}>{TIME_GRANULARITY_LABELS[granularity]}</option>)}</NativeSelect>
      </div>
      <Button variant="ghost" size="sm" onClick={() => onChange({ ...refValue, filter: refValue.filter ? undefined : newPredicate() })}>{refValue.filter ? "Remove input condition" : "Add input condition"}</Button>
      {refValue.filter && <FilterNodeEditor idPrefix={`${id}-filter`} node={refValue.filter} models={models} onChange={(filter) => onChange({ ...refValue, filter })} onRemove={() => onChange({ ...refValue, filter: undefined })} />}
    </div> : null}
  </Card>;
}

function WindowFields({ window, grainToDate, allowGrainToDate, error, onChange }: { window?: string; grainToDate?: TimeGranularity; allowGrainToDate: boolean; error?: string; onChange: (patch: { window?: string; grainToDate?: TimeGranularity }) => void }) {
  const match = window?.match(/^(\d+)\s+(day|week|month|quarter|year)s?$/);
  const count = match?.[1] ?? "";
  const unit = match?.[2] ?? "day";
  return <div className="space-y-1.5"><div className="grid gap-3 md:grid-cols-3"><Input label="Window count" type="number" min="1" value={count} aria-invalid={Boolean(error)} disabled={Boolean(grainToDate)} onChange={(event) => onChange({ window: event.target.value ? `${event.target.value} ${unit}` : undefined, grainToDate: undefined })} placeholder="30" /><NativeSelect label="Window unit" value={unit} disabled={Boolean(grainToDate) || !count} onChange={(event) => onChange({ window: count ? `${count} ${event.target.value}` : undefined, grainToDate: undefined })}>{WINDOW_UNITS.map((candidate) => <option key={candidate} value={candidate}>{TIME_GRANULARITY_LABELS[candidate]}</option>)}</NativeSelect>{allowGrainToDate ? <NativeSelect label="Period to date" value={grainToDate ?? ""} disabled={Boolean(count)} onChange={(event) => onChange({ grainToDate: (event.target.value || undefined) as TimeGranularity | undefined, window: undefined })}><option value="">None</option>{TIME_GRANULARITIES.map((candidate) => <option key={candidate} value={candidate}>{TIME_GRANULARITY_LABELS[candidate]}</option>)}</NativeSelect> : null}</div>{error ? <p className="text-xs text-error">{error}</p> : null}</div>;
}

function ConstantProperties({ value, error, onChange }: { value: ConstantProperty[]; error?: string; onChange: (value: ConstantProperty[]) => void }) {
  return <div className="space-y-2"><div className="flex items-center justify-between"><span className="type-body">Constant properties <span className="text-foreground-secondary">(optional)</span></span><Button variant="secondary" size="sm" onClick={() => onChange([...value, { baseProperty: "", conversionProperty: "" }])}><Plus />Add</Button></div>{error ? <p className="text-xs text-error">{error}</p> : null}{value.map((property, index) => <div key={index} className="flex gap-2"><Input aria-label="Base property" value={property.baseProperty} onChange={(event) => onChange(value.map((candidate, candidateIndex) => candidateIndex === index ? { ...candidate, baseProperty: event.target.value } : candidate))} placeholder="base property" /><Input aria-label="Conversion property" value={property.conversionProperty} onChange={(event) => onChange(value.map((candidate, candidateIndex) => candidateIndex === index ? { ...candidate, conversionProperty: event.target.value } : candidate))} placeholder="conversion property" /><Button variant="ghost" size="sm" aria-label="Remove constant property" onClick={() => onChange(value.filter((_, candidateIndex) => candidateIndex !== index))}><Trash2 aria-hidden="true" /></Button></div>)}</div>;
}

function isPredicate(node: MetricFilter): node is MetricFilterPredicate { return "dimension" in node; }

function valuesForOperator(operator: FilterOperator, values: string[]): string[] {
  if (operator === "is_null" || operator === "is_not_null") return [];
  if (operator === "between") return [values[0] ?? "", values[1] ?? ""];
  if (operator === "in" || operator === "not_in") return values.length > 0 ? values : [""];
  return [values[0] ?? ""];
}

function FilterNodeEditor({ idPrefix, node, models, onChange, onRemove }: { idPrefix: string; node: MetricFilter; models: SemanticModelResponse[]; onChange: (node: MetricFilter) => void; onRemove: () => void }) {
  if (isPredicate(node)) {
    const setOperator = node.operator === "in" || node.operator === "not_in";
    const values = valuesForOperator(node.operator, node.values);
    const valueCount = values.length === 0 ? 0 : node.operator === "between" ? 2 : 1;
    const model = models.find((candidate) => candidate.name === node.dimension.semanticModelName);
    const modelKnown = !node.dimension.semanticModelName || Boolean(model);
    const dimensionKnown = !node.dimension.name || Boolean(model?.dimensions.some((dimension) => dimension.name === node.dimension.name));
    const wrap = (kind: "and" | "or" | "not") => onChange(kind === "and" ? { and: [node] } : kind === "or" ? { or: [node] } : { not: node });
    return <Card padding="md" className="space-y-3">
      <div className="grid gap-2 md:grid-cols-[1fr_1fr_170px_120px_auto]">
        <NativeSelect id={`${idPrefix}-model`} aria-label="Condition model" value={node.dimension.semanticModelName} onChange={(event) => onChange({ ...node, dimension: { semanticModelName: event.target.value, name: "" } })}>
          <option value="">Model</option>
          {modelKnown ? null : <option value={node.dimension.semanticModelName}>{node.dimension.semanticModelName} (not found)</option>}
          {models.map((candidate) => <option key={candidate.id} value={candidate.name}>{modelOptionLabel(candidate)}</option>)}
        </NativeSelect>
        <NativeSelect id={`${idPrefix}-dimension`} aria-label="Condition dimension" value={node.dimension.name} disabled={!node.dimension.semanticModelName} onChange={(event) => onChange({ ...node, dimension: { semanticModelName: node.dimension.semanticModelName, name: event.target.value } })}>
          <option value="">Dimension</option>
          {dimensionKnown ? null : <option value={node.dimension.name}>{node.dimension.name} (not found)</option>}
          {(model?.dimensions ?? []).map((dimension) => <option key={dimension.name} value={dimension.name}>{dimension.label ? `${dimension.label} · ${dimension.name}` : dimension.name}</option>)}
        </NativeSelect>
        <NativeSelect aria-label="Condition operator" value={node.operator} onChange={(event) => { const operator = event.target.value as FilterOperator; onChange({ ...node, operator, values: valuesForOperator(operator, node.values) }); }}>{FILTER_OPERATORS.map((operator) => <option key={operator} value={operator}>{FILTER_OPERATOR_LABELS[operator]}</option>)}</NativeSelect>
        <NativeSelect aria-label="Group condition" value="predicate" onChange={(event) => { if (event.target.value !== "predicate") wrap(event.target.value as "and" | "or" | "not"); }}><option value="predicate">Single</option><option value="and">All of</option><option value="or">Any of</option><option value="not">None of</option></NativeSelect>
        <Button variant="ghost" size="sm" aria-label="Remove condition" onClick={onRemove}><Trash2 aria-hidden="true" /></Button>
      </div>
      {valueCount > 0 && <div className={`grid gap-2 ${valueCount === 2 ? "md:grid-cols-2" : "grid-cols-1"}`}>{values.map((value, index) => <div key={index} className="flex gap-2"><Input aria-label={`Condition value ${index + 1}`} value={value} onChange={(event) => { const next = [...values]; next[index] = event.target.value; onChange({ ...node, values: next }); }} placeholder={valueCount === 2 ? (index === 0 ? "from" : "to") : "value"} />{setOperator && <Button variant="ghost" size="sm" aria-label={`Remove condition value ${index + 1}`} disabled={values.length === 1} onClick={() => onChange({ ...node, values: values.filter((_, valueIndex) => valueIndex !== index) })}><Trash2 aria-hidden="true" /></Button>}</div>)}{setOperator && <Button variant="ghost" size="sm" onClick={() => onChange({ ...node, values: [...values, ""] })}><Plus />Add value</Button>}</div>}
    </Card>;
  }
  const groupType: "and" | "or" | "not" = "and" in node ? "and" : "or" in node ? "or" : "not";
  const children: MetricFilter[] = "and" in node ? node.and : "or" in node ? node.or : [node.not];
  const setChildren = (next: MetricFilter[]) => onChange(groupType === "and" ? { and: next } : groupType === "or" ? { or: next } : { not: next[0] ?? newPredicate() });
  return <Card padding="md" className="space-y-3 border-dashed"><div className="flex items-center justify-between"><NativeSelect id={`${idPrefix}-model`} aria-label="Condition group" value={groupType} onChange={(event) => { const next = event.target.value as "and" | "or" | "not"; onChange(next === "and" ? { and: children } : next === "or" ? { or: children } : { not: children[0] ?? newPredicate() }); }} className="max-w-[140px]"><option value="and">All of</option><option value="or">Any of</option><option value="not">None of</option></NativeSelect><Button variant="ghost" size="sm" aria-label="Remove condition group" onClick={onRemove}><Trash2 aria-hidden="true" /></Button></div><div className="space-y-2 pl-3">{children.map((child, index) => <FilterNodeEditor key={index} idPrefix={`${idPrefix}-${index}`} node={child} models={models} onChange={(next) => setChildren(children.map((candidate, candidateIndex) => candidateIndex === index ? next : candidate))} onRemove={() => setChildren(children.filter((_, candidateIndex) => candidateIndex !== index))} />)}{groupType !== "not" && <Button variant="ghost" size="sm" onClick={() => setChildren([...children, newPredicate()])}><Plus />Add condition</Button>}</div></Card>;
}
