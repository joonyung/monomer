import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ArrowLeft, Plus, Search, Trash2 } from "lucide-react";
import { metricAPI } from "@/api/metric";
import { SemanticCalculationRail } from "@/components/semantics/SemanticCalculationRail";
import { catalogAPI } from "@/api/catalog";
import { connectorsAPI } from "@/api/connectors";
import {
  formatSourceRelation,
  semanticModelAPI,
  type CreateSemanticModelRequest,
  type SemanticModelResponse,
  type WriteSemanticModelRequest,
} from "@/api/semantic-model";
import type { Metric } from "@/lib/types/metric";
import type { CatalogTableSearchHit } from "@/lib/types/catalog";
import { Button } from "@/components/ui/Button";
import { Card } from "@/components/ui/Card";
import { IconButton } from "@/components/ui/IconButton";
import { FIELD_LABEL_CLASS, Input, TextArea } from "@/components/ui/Input";
import { StateBlock } from "@/components/ui/StateBlock";
import { CatalogTablePicker } from "@/components/catalog/CatalogTablePicker";
import { SemanticCheckPanel, useSemanticCheck } from "@/components/semantics/SemanticCheckPanel";
import { semanticSaveFailure, type SemanticCheckProblem } from "@/api/semantic-check";
import { EditorSection } from "@/components/editor/EditorPageFrame";
import { SemanticEditorFrame } from "@/components/semantics/SemanticPageLayout";
import { SegmentedControl } from "@/components/settings/SettingsPrimitives";
import { usePrincipalId, useWorkspaceId } from "@/hooks/use-workspace";
import { webPaths } from "@/lib/web-paths";
import { queryKeys } from "@/lib/query/keys";
import { refreshSemanticDefinitionReads } from "@/lib/query/semantic-definition-cache";
import { stableSemanticMutationIntent, type SemanticMutationIntent } from "@/lib/semantic-mutation-intent";
import { Link, useRouter } from "@/lib/navigation";
import { suggestTechnicalName } from "@/lib/utils/semantic-model-utils";
import {
  problemSummary,
  problemsByField,
  technicalNameProblem,
  validateSemanticModelDraft,
} from "@/components/semantic-model/semantic-model-validation";
import {
  definitionFromFields,
  emptyField,
  fieldsWithSourceColumns,
  applyRecommendation,
  fieldPaths,
  fieldsFromDefinition,
  followDraftPaths,
  fieldMatchesText,
  hiddenFieldIds,
  isEntityKind,
  metricTimeAfterFieldChange,
  type FieldDraft,
} from "@/components/semantic-model/semantic-model-fields";
import {
  connectorsInUse,
  removeSourceTable,
  replaceSourceTable,
  sourceRelationForSave,
  sourceTableFromCatalog,
  sourceTableFromHit,
  sourceConnectorName,
  sourceTablesFromDefinition,
  type SemanticModelSourceTable,
  type SourceTableDraft,
} from "@/components/semantic-model/semantic-model-sources";
import { FieldDetailsDialog, FieldsTable, metricsForField } from "@/components/semantic-model/SemanticModelFieldsTable";

export type { SemanticModelSourceTable } from "@/components/semantic-model/semantic-model-sources";

// "order_items" reads as "Order Items", the label a model opened from that table starts with.
export function tableTitle(tableName: string): string {
  return tableName
    .split(/[_\s-]+/)
    .filter(Boolean)
    .map((word) => word.charAt(0).toUpperCase() + word.slice(1).toLowerCase())
    .join(" ");
}

export function modelWriteRequest(definition: CreateSemanticModelRequest, metrics: Metric[], previousRequest: WriteSemanticModelRequest | null): WriteSemanticModelRequest {
  if (previousRequest && JSON.stringify(previousRequest.definition) === JSON.stringify(definition)) return previousRequest;
  return {
    definition,
    aggregateMetrics: definition.measures.filter((measure) => metricsForField(metrics, definition.name, measure.name).length === 0)
      .map((measure) => ({ measure: measure.name, name: measure.name, ...(measure.label ? { label: measure.label } : {}), ...(measure.description ? { description: measure.description } : {}) })),
  };
}

export function modelSaveFieldErrors(request: WriteSemanticModelRequest, errors: Record<string, string>): Record<string, string> {
  return Object.fromEntries(Object.entries(errors).map(([field, message]) => {
    const aggregate = /^aggregateMetrics\[(\d+)\](?:\.(.*))?$/.exec(field);
    if (!aggregate) return [field, message];
    const metric = request.aggregateMetrics[Number(aggregate[1])];
    const measureIndex = metric ? request.definition.measures.findIndex((measure) => measure.name === metric.measure) : -1;
    return [measureIndex < 0 ? field : `measures[${measureIndex}].${!aggregate[2] || aggregate[2] === "measure" ? "name" : aggregate[2]}`, message];
  }));
}

export interface SemanticModelEditorProps {
  modelId?: string;
  initialData?: SemanticModelResponse;
  // Preselects the first source of a new model and opens its columns; ignored when editing.
  initialSource?: SemanticModelSourceTable | null;
  onCancel?: () => void;
  onSaved?: (model: SemanticModelResponse) => void;
}

export function SemanticModelEditor({ modelId, initialData, initialSource = null, onCancel, onSaved }: SemanticModelEditorProps) {
  const workspaceId = useWorkspaceId();
  const principalId = usePrincipalId();
  const router = useRouter();
  const queryClient = useQueryClient();
  const isEdit = Boolean(modelId);
  const [name, setName] = useState(initialData?.name ?? "");
  const [label, setLabel] = useState(initialData?.label ?? "");
  const [description, setDescription] = useState(initialData?.description ?? "");
  const [sourceTables, setSourceTables] = useState<SourceTableDraft[]>([]);
  const [activeSourceId, setActiveSourceId] = useState("");
  const [fields, setFields] = useState<FieldDraft[]>(() => initialData ? fieldsFromDefinition(initialData.entities, initialData.dimensions, initialData.measures) : []);
  const [aggregationTimeDimension, setAggregationTimeDimension] = useState("");
  const [detailsFieldId, setDetailsFieldId] = useState<string | null>(null);
  const [columnFilter, setColumnFilter] = useState("");
  const [filterHiddenIds, setFilterHiddenIds] = useState<ReadonlySet<string> | null>(null);
  const [hiddenUnusedIds, setHiddenUnusedIds] = useState<ReadonlySet<string> | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [serverFieldErrors, setServerFieldErrors] = useState<{ errors: Record<string, string>; paths: ReadonlyMap<string, string> }>(
    { errors: {}, paths: new Map() },
  );
  const [nameTouched, setNameTouched] = useState(false);
  const [showValidation, setShowValidation] = useState(false);
  const [validationAttempt, setValidationAttempt] = useState(0);
  const nameEditedRef = useRef(false);
  const validationSummaryRef = useRef<HTMLDivElement>(null);
  const dirtyRef = useRef(false);
  const initializedScope = useRef<string | null>(null);
  const saveIntentRef = useRef<(SemanticMutationIntent & { request: WriteSemanticModelRequest }) | null>(null);
  const columnsScopeRef = useRef<string | null>(null);

  const connectorsQuery = useQuery({
    queryKey: queryKeys.connectors.list(workspaceId ?? "", principalId ?? ""),
    queryFn: () => connectorsAPI.list({ workspaceId }),
    enabled: Boolean(workspaceId && principalId),
  });

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
  useEffect(() => {
    if (validationAttempt > 0) validationSummaryRef.current?.focus();
  }, [validationAttempt]);

  const connectors = useMemo(() => connectorsQuery.data ?? [], [connectorsQuery.data]);
  const modelQuery = useQuery({
    queryKey: queryKeys.semanticModels.detail(workspaceId ?? "", principalId ?? "", modelId ?? ""),
    queryFn: () => semanticModelAPI.getById(workspaceId, modelId as string),
    enabled: Boolean(workspaceId && principalId && modelId),
    initialData,
  });

  const populate = useCallback((model: SemanticModelResponse) => {
    setName(model.name);
    setLabel(model.label ?? "");
    setDescription(model.description ?? "");
    const sources = sourceTablesFromDefinition(model.sourceRelations);
    setSourceTables(sources);
    setActiveSourceId(sources[0]?.id ?? "");
    setFields(fieldsFromDefinition(model.entities, model.dimensions, model.measures));
    setAggregationTimeDimension(model.aggregationTimeDimension ?? "");
  }, []);

  useEffect(() => {
    if (!modelQuery.data || !workspaceId || !principalId || !modelId) return;
    const scope = `${workspaceId}:${principalId}:${modelId}`;
    if (initializedScope.current === scope) return;
    initializedScope.current = scope;
    populate(modelQuery.data);
  }, [modelId, modelQuery.data, populate, principalId, workspaceId]);

  useEffect(() => {
    const handler = (event: BeforeUnloadEvent) => {
      if (!dirtyRef.current) return;
      event.preventDefault();
    };
    window.addEventListener("beforeunload", handler);
    return () => window.removeEventListener("beforeunload", handler);
  }, []);

  useEffect(() => {
    if (isEdit || !initialSource || sourceTables.length > 0) return;
    if (!connectors.some((connector) => connector.id === initialSource.connectorId)) return;
    const source = sourceTableFromCatalog(initialSource);
    setSourceTables([source]);
    setActiveSourceId(source.id);
    setLabel((current) => current || tableTitle(initialSource.relation));
    setName((current) => current || suggestTechnicalName(initialSource.relation));
  }, [connectors, initialSource, isEdit, sourceTables.length]);

  const activeSource = sourceTables.find((source) => source.id === activeSourceId) ?? sourceTables[0] ?? null;
  const activeRelationQuery = useQuery({
    queryKey: queryKeys.catalog.relation(
      workspaceId ?? "", principalId ?? "", activeSource?.connectorId ?? "", activeSource?.catalog ?? null,
      activeSource?.schema ?? "", activeSource?.relation ?? "",
    ),
    queryFn: () => catalogAPI.getRelation(workspaceId, activeSource?.connectorId as string, activeSource?.relation as string, activeSource?.schema as string, activeSource?.catalog),
    enabled: Boolean(workspaceId && principalId && activeSource),
  });
  useEffect(() => {
    if (!activeRelationQuery.data || !activeSource || modelsQuery.isPending || (isEdit && !modelQuery.data)) return;
    const scope = `${activeSource.connectorId}:${activeSource.catalog ?? ""}:${activeSource.schema}:${activeSource.relation}`;
    if (columnsScopeRef.current === scope) return;
    columnsScopeRef.current = scope;
    setFields((current) => fieldsWithSourceColumns(current, activeRelationQuery.data, modelsQuery.data ?? [], modelId));
  }, [activeRelationQuery.data, activeSource, isEdit, modelId, modelQuery.data, modelsQuery.data, modelsQuery.isPending]);

  const markDirty = () => {
    dirtyRef.current = true;
  };
  const addSource = (hit: CatalogTableSearchHit) => {
    markDirty();
    const source = sourceTableFromHit(hit);
    setSourceTables((current) => [...current, source]);
    setActiveSourceId(source.id);
    if (isEdit) return;
    setLabel((current) => current || tableTitle(source.relation));
    if (!nameEditedRef.current) setName((current) => current || suggestTechnicalName(source.relation));
  };
  const changeSource = (id: string, hit: CatalogTableSearchHit) => {
    markDirty();
    setSourceTables((current) => replaceSourceTable(current, id, hit));
    setActiveSourceId(id);
  };
  const removeSource = (id: string) => {
    markDirty();
    const next = removeSourceTable(sourceTables, id);
    setSourceTables(next);
    if (!next.some((source) => source.id === activeSourceId)) setActiveSourceId(next[0]?.id ?? "");
  };
  const showSourceColumns = (id: string) => {
    setActiveSourceId(id);
  };

  const updateField = (id: string, update: (field: FieldDraft) => FieldDraft) => {
    markDirty();
    const previous = fields.find((field) => field.id === id);
    if (previous) setAggregationTimeDimension(metricTimeAfterFieldChange(aggregationTimeDimension, previous, update(previous)));
    setFields((current) => current.map((field) => (field.id === id ? update(field) : field)));
  };
  const removeField = (id: string) => {
    markDirty();
    setFields((current) => current.filter((field) => field.id !== id));
  };
  const addEmptyField = () => {
    markDirty();
    setFields((current) => [...current, emptyField()]);
  };
  const duplicateField = (id: string) => {
    markDirty();
    setFields((current) => {
      const field = current.find((item) => item.id === id);
      if (!field) return current;
      const duplicate: FieldDraft = { ...emptyField(), kind: "unused", expression: field.expression, nativeType: field.nativeType, name: `${field.name}_count`, label: undefined, nameFollowsLabel: false };
      const index = current.findIndex((item) => item.id === id);
      return [...current.slice(0, index + 1), duplicate, ...current.slice(index + 1)];
    });
  };
  const changeColumnFilter = (text: string) => {
    setColumnFilter(text);
    setFilterHiddenIds(text.trim() ? new Set(fields.filter((field) => !fieldMatchesText(field, text)).map((field) => field.id)) : null);
  };
  const acceptRecommendations = () => {
    markDirty();
    setFields((current) => current.map(applyRecommendation));
  };
  const rejectRecommendations = () => setFields((current) => current.map((field) => ({ ...field, recommendation: undefined })));

  const changeLabel = (nextLabel: string) => {
    markDirty();
    setLabel(nextLabel);
    if (!isEdit && !nameEditedRef.current) setName(suggestTechnicalName(nextLabel));
  };
  const changeName = (nextName: string) => {
    markDirty();
    nameEditedRef.current = true;
    setName(nextName);
  };

  const definition = definitionFromFields(fields);
  const paths = fieldPaths(fields);
  const draftPaths = new Map([...paths, ...sourceTables.map((source, index) => [source.id, `sourceRelations[${index}]`] as const)]);
  const problems = validateSemanticModelDraft({
    name,
    sourceRelations: sourceTables.map(sourceRelationForSave),
    ...definition,
    aggregationTimeDimension,
  });
  const visibleProblems = showValidation ? problems : [];
  // A Metric time left pointing at a field that is no longer Time is shown as soon as it happens, not on Save.
  const metricTimeProblem = problems.some((problem) => problem.field === "aggregationTimeDimension");
  const fieldErrors = {
    ...followDraftPaths(serverFieldErrors.errors, serverFieldErrors.paths, draftPaths),
    ...problemsByField(visibleProblems),
  };
  const request: CreateSemanticModelRequest = {
    name: name.trim(),
    ...(label.trim() ? { label: label.trim() } : {}),
    ...(description.trim() ? { description: description.trim() } : {}),
    sourceRelations: sourceTables.map(sourceRelationForSave),
    ...definition,
    ...(aggregationTimeDimension ? { aggregationTimeDimension } : {}),
  };
  const check = useSemanticCheck(problems.length === 0 ? { kind: "SEMANTIC_MODEL", semanticModelId: modelId ?? null, request } : null);
  const elementIdForField = (field: string, code?: SemanticCheckProblem["code"]): string | null => {
    if (field === "name") return isEdit ? null : "semantic-model-name";
    if (field === "aggregationTimeDimension") return null;
    const match = /^((?:entities|dimensions|measures)\[\d+\])(?:\.(\w+))?/.exec(field);
    if (!match) return null;
    const fieldId = [...paths.entries()].find(([, path]) => path === match[1])?.[0];
    if (!fieldId) return null;
    const time = fields.find((candidate) => candidate.id === fieldId)?.kind === "time";
    // Time kind problems point at the dimension as a whole; its time kind control is what fixes them.
    const timeKindProblem = match[2] === "timeKind" || (match[2] === undefined && (code === "TIME_KIND_UNDECLARED" || code === "TIME_KIND_MISMATCH"));
    const control = match[2] === "expression" ? "expression" : time && match[2] === "granularity" ? "granularity" : time && timeKindProblem ? "timeKind" : "name";
    return `semantic-field-${fieldId}-${control}`;
  };
  const goToField = (field: string, code?: SemanticCheckProblem["code"]) => {
    const focusField = () => {
      const element = document.getElementById(elementIdForField(field, code) ?? "");
      element?.scrollIntoView({ block: "center" });
      element?.focus();
    };
    if (!columnFilter && !hiddenUnusedIds) return focusField();
    changeColumnFilter("");
    setHiddenUnusedIds(null);
    window.requestAnimationFrame(focusField);
  };
  const nameError = fieldErrors.name ?? (nameTouched ? technicalNameProblem(name, "orders") ?? undefined : undefined);

  const saveMutation = useMutation({
    mutationFn: ({ request, idempotencyKey }: { request: WriteSemanticModelRequest; idempotencyKey: string }) =>
      modelId ? semanticModelAPI.update(workspaceId, modelId, request, idempotencyKey) : semanticModelAPI.create(workspaceId, request, idempotencyKey),
    onSettled: async () => {
      if (workspaceId && principalId) {
        await refreshSemanticDefinitionReads(queryClient, workspaceId, principalId);
      }
    },
  });

  const handleSave = async () => {
    check.checkNow();
    changeColumnFilter("");
    setHiddenUnusedIds(null);
    if (problems.length > 0) {
      setError(null);
      setShowValidation(true);
      setValidationAttempt((attempt) => attempt + 1);
      return;
    }
    let writeRequest = modelWriteRequest(request, metricsQuery.data ?? [], saveIntentRef.current?.request ?? null);
    setShowValidation(false);
    setError(null);
    setServerFieldErrors({ errors: {}, paths: new Map() });
    try {
      const savedMetrics = await queryClient.ensureQueryData({
        queryKey: queryKeys.metrics.all(workspaceId, principalId ?? ""),
        queryFn: () => metricAPI.list(workspaceId),
      });
      writeRequest = modelWriteRequest(request, savedMetrics, saveIntentRef.current?.request ?? null);
      const metricConflict = writeRequest !== saveIntentRef.current?.request
        ? writeRequest.aggregateMetrics.find((aggregate) => savedMetrics.some((metric) => metric.name === aggregate.name))
        : undefined;
      if (metricConflict) {
        const message = `A metric named “${metricConflict.name}” already exists. Change this field's name before saving.`;
        setError(message);
        setServerFieldErrors({ errors: modelSaveFieldErrors(writeRequest, { [`aggregateMetrics[${writeRequest.aggregateMetrics.indexOf(metricConflict)}].name`]: message }), paths: draftPaths });
        return;
      }
      const intent = stableSemanticMutationIntent(saveIntentRef.current, JSON.stringify(writeRequest));
      saveIntentRef.current = { ...intent, request: writeRequest };
      const saved = await saveMutation.mutateAsync({ request: writeRequest, idempotencyKey: intent.idempotencyKey });
      saveIntentRef.current = null;
      dirtyRef.current = false;
      const firstMetricId = saved.aggregateMetrics[0]?.id;
      if (firstMetricId) router.push(webPaths.metric(workspaceId, firstMetricId));
      else if (onSaved) onSaved(saved.model);
      else router.push(webPaths.semanticModel(workspaceId, saved.model.id));
    } catch (cause) {
      const failure = semanticSaveFailure(cause);
      setError(failure.message);
      setServerFieldErrors({ errors: modelSaveFieldErrors(writeRequest, failure.fieldErrors), paths: draftPaths });
    }
  };

  if (isEdit && modelQuery.isPending) return <StateBlock kind="loading" layout="page" title="Loading semantic model" />;
  if (modelQuery.error && isEdit) return <StateBlock kind="error" layout="page" title={modelQuery.error.message} action={<Link className="type-control inline-flex items-center gap-2 underline" href={webPaths.semanticModelCollection(workspaceId)}><ArrowLeft className="h-4 w-4" aria-hidden="true" />Back to Semantics</Link>} />;

  const timeFieldNames = fields.filter((field) => field.kind === "time" && field.name).map((field) => field.name);
  const entityNames = fields.filter((field) => isEntityKind(field.kind) && field.name).map((field) => field.name);
  const detailsIndex = fields.findIndex((field) => field.id === detailsFieldId);
  const detailsField = detailsIndex >= 0 ? fields[detailsIndex] : null;
  const canAddSource = connectorsQuery.isSuccess && connectors.length > sourceTables.length;

  return (
    <SemanticEditorFrame
      backHref={isEdit && modelId ? webPaths.semanticModel(workspaceId, modelId) : webPaths.semanticModelCollection(workspaceId)}
      backLabel={isEdit ? "Back to model" : "Semantics"}
      title={isEdit ? modelQuery.data?.label || modelQuery.data?.name || "Model" : "New model"}
      saving={saveMutation.isPending}
      saveError={error}
      onSave={handleSave}
      onCancel={onCancel}
      aside={modelId ? <SemanticCalculationRail workspaceId={workspaceId} principalId={principalId ?? ""} kind="SEMANTIC_MODEL" id={modelId} editing>
        <SemanticCheckPanel mode="edit" state={check} connectors={connectors} incompleteMessage="Choose a source table and complete the required fields to check this model." onGoToField={goToField} canGoToField={(field) => elementIdForField(field) !== null} />
      </SemanticCalculationRail> : <SemanticCheckPanel mode="edit" state={check} connectors={connectors} incompleteMessage="Choose a source table and complete the required fields to check this model." onGoToField={goToField} canGoToField={(field) => elementIdForField(field) !== null} />}

    >
        {visibleProblems.length > 0 && (
          <div ref={validationSummaryRef} tabIndex={-1} role="alert" aria-label="Model validation errors" className="rounded-[6px] border border-error/30 bg-error/5 p-4 type-body text-error outline-none">
            <p>Fix the following before saving:</p>
            <ul className="mt-2 list-disc space-y-1 pl-5">
              {visibleProblems.map((problem) => <li key={`${problem.field}-${problem.message}`}>{problemSummary(problem)}</li>)}
            </ul>
          </div>
        )}
        <EditorSection title={sourceTables.length > 1 ? "Source tables" : "Source table"} description={null} action={null}>
          <SourceTables
            sources={sourceTables}
            connectors={connectors}
            activeSourceId={activeSource?.id ?? null}
            errors={fieldErrors}
            connectorsState={connectorsQuery.isError ? "error" : connectorsQuery.isPending ? "loading" : "ready"}
            canAddSource={canAddSource}
            workspaceConnectorsHref={webPaths.connectors(workspaceId)}
            onAdd={addSource}
            onChange={changeSource}
            onRemove={removeSource}
            onShowColumns={showSourceColumns}
          />
        </EditorSection>
        <EditorSection title="Identity" description="Name is used in queries and can't be changed after the model is created." action={null}>
          <div className="grid gap-4 md:grid-cols-2">
            <div><label htmlFor="semantic-model-label" className={FIELD_LABEL_CLASS}>Label <span className="text-foreground-secondary">(optional)</span></label><Input id="semantic-model-label" value={label} onChange={(event) => changeLabel(event.target.value)} placeholder="e.g. Orders" /></div>
            {isEdit ? (
              <div>
                <span className={FIELD_LABEL_CLASS}>Name</span>
                <p className="type-mono flex h-8 items-center text-foreground">{name}</p>
              </div>
            ) : (
              <div><label htmlFor="semantic-model-name" className={FIELD_LABEL_CLASS}>Name <span className="text-error">*</span></label><Input id="semantic-model-name" value={name} onChange={(event) => changeName(event.target.value)} onBlur={() => setNameTouched(true)} aria-invalid={Boolean(nameError)} error={nameError} placeholder="e.g. orders" /></div>
            )}
            <div className="md:col-span-2"><label htmlFor="semantic-model-description" className={FIELD_LABEL_CLASS}>Description <span className="text-foreground-secondary">(optional)</span></label><TextArea id="semantic-model-description" value={description} onChange={(event) => { markDirty(); setDescription(event.target.value); }} rows={2} placeholder="What one row of this table represents" /></div>
          </div>
        </EditorSection>

      <section aria-labelledby="semantic-model-fields-heading">
        <div className="mb-3 flex flex-wrap items-center justify-between gap-x-4 gap-y-2">
          <div className="min-w-0">
            <h2 id="semantic-model-fields-heading" className="type-item text-heading">Columns <span className="tabular-nums text-foreground-secondary">{fieldCountSummary(fields)}</span></h2>
            <p className="type-meta mt-1 text-foreground-secondary">Choose how each column is used. Aggregates become metrics.</p>
          </div>
          <div className="flex flex-wrap items-center gap-2">
            {fields.some((field) => field.recommendation) && <><Button variant="secondary" size="sm" onClick={acceptRecommendations}>Apply suggestions</Button><Button variant="ghost" size="sm" onClick={rejectRecommendations}>Dismiss suggestions</Button></>}
            <Button variant="ghost" size="sm" onClick={addEmptyField}><Plus />Add calculated field</Button>
          </div>
        </div>
        {fieldErrors.entities ? <p className="mb-3 type-meta text-error">{fieldErrors.entities}</p> : null}
        {metricTimeProblem ? <p role="alert" className="mb-3 flex flex-wrap items-center gap-x-2 type-meta text-error">Metric time: {aggregationTimeDimension} is not a Time field.<Button variant="ghost" size="sm" onClick={() => { markDirty(); setAggregationTimeDimension(""); }}>Clear Metric time</Button></p> : null}
        <Card padding="none" className="overflow-hidden">
          {fields.length > 0 && <div className="flex flex-wrap items-center gap-3 border-b border-border-light px-4 py-3">
            <div className="relative w-full max-w-sm"><Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-foreground-secondary" aria-hidden="true" /><Input type="search" aria-label="Filter columns" placeholder="Filter columns" value={columnFilter} onChange={(event) => changeColumnFilter(event.target.value)} className="pl-9" /></div>
            {(hiddenUnusedIds !== null || fields.some((field) => field.kind === "unused")) && <SegmentedControl label="Show columns" value={hiddenUnusedIds ? "inUse" : "all"} options={[{ value: "all", label: "All", count: fields.length }, { value: "inUse", label: "In use", count: fields.filter((field) => field.kind !== "unused").length }]} onChange={(value) => setHiddenUnusedIds(value === "inUse" ? new Set(fields.filter((field) => field.kind === "unused").map((field) => field.id)) : null)} />}
          </div>}
          {activeRelationQuery.isError && <StateBlock kind="error" layout="inline" title="Source columns could not be loaded" description="Existing fields are preserved. You can still add a calculated field." />}
          {activeRelationQuery.isFetching && fields.length === 0 && <StateBlock kind="loading" layout="inline" title="Loading source columns" />}
          {fields.length > 0 ? (
            <FieldsTable fields={fields} filterText={columnFilter} hiddenIds={hiddenFieldIds(fields, filterHiddenIds, hiddenUnusedIds)} paths={paths} errors={fieldErrors} models={modelsQuery.data ?? []} currentModelId={modelId ?? undefined} modelName={name} metrics={metricsQuery.data ?? null} workspaceId={workspaceId} modelTimeDimension={aggregationTimeDimension} onModelTimeDimensionChange={(fieldName) => { markDirty(); setAggregationTimeDimension(fieldName); }} onModelTimeDimensionClear={() => { markDirty(); setAggregationTimeDimension(""); }} onChange={updateField} onRemove={removeField} onOpenDetails={setDetailsFieldId} onDuplicate={duplicateField} />
          ) : (
            <StateBlock kind="empty" layout="inline" title="Choose a source table to describe its columns." />
          )}
        </Card>
      </section>

      {detailsField ? (
        <FieldDetailsDialog
          field={detailsField}
          rowNumber={detailsIndex + 1}
          path={paths.get(detailsField.id) ?? ""}
          errors={fieldErrors}
          timeFieldNames={timeFieldNames}
          entityNames={entityNames}
          onChange={(update) => updateField(detailsField.id, update)}
          onClose={() => setDetailsFieldId(null)}
        />
      ) : null}
    </SemanticEditorFrame>
  );
}


function fieldCountSummary(fields: readonly FieldDraft[]): string {
  const inUse = fields.filter((field) => field.kind !== "unused").length;
  return inUse === fields.length ? `· ${fields.length}` : `· ${inUse} of ${fields.length} in use`;
}

export interface SourceTablesProps {
  sources: SourceTableDraft[];
  connectors: ReadonlyArray<{ id: string; name: string }>;
  activeSourceId: string | null;
  errors: Record<string, string>;
  connectorsState: "loading" | "error" | "ready";
  canAddSource: boolean;
  workspaceConnectorsHref: string;
  onAdd: (hit: CatalogTableSearchHit) => void;
  onChange: (id: string, hit: CatalogTableSearchHit) => void;
  onRemove: (id: string) => void;
  onShowColumns: (id: string) => void;
}

export function SourceTables({ sources, connectors, activeSourceId, errors, connectorsState, canAddSource, workspaceConnectorsHref, onAdd, onChange, onRemove, onShowColumns }: SourceTablesProps) {
  if (sources.length === 0) {
    if (connectorsState === "ready" && connectors.length === 0) {
      return (
        <div role="status" className="rounded-[6px] border border-border bg-surface">
          <StateBlock
            kind="empty"
            layout="inline"
            title="Add a connector before choosing a source table."
            action={<Link href={workspaceConnectorsHref} className="type-control underline focus-visible:outline focus-visible:outline-[2px] focus-visible:outline-focus-ring">Go to Connectors</Link>}
          />
        </div>
      );
    }
    if (connectorsState !== "ready") {
      return <StateBlock kind={connectorsState} layout="inline" title={connectorsState === "error" ? "Connectors could not be loaded. Try again." : "Loading connectors…"} />;
    }
    return (
      <div>
        <CatalogTablePicker triggerLabel="Choose source table" disabled={!canAddSource} onSelect={onAdd} />
        {errors.sourceRelations ? <p className="mt-2 type-meta text-error">{errors.sourceRelations}</p> : null}
      </div>
    );
  }
  const multiple = sources.length > 1;
  return (
    <div className="space-y-3">
      <ul aria-label="Source tables" className="divide-y divide-border-light">
        {sources.map((source, index) => {
          const sourceError = errors[`sourceRelations[${index}]`];
          return (
            <li key={source.id} className="py-2 first:pt-0">
              <div className="flex flex-wrap items-center gap-x-3 gap-y-2">
                <span className="flex min-w-0 flex-wrap items-baseline gap-x-2"><span className="type-body text-foreground">{sourceConnectorName(source, connectors)}</span><span className="type-mono min-w-0 text-foreground [overflow-wrap:anywhere]">{formatSourceRelation(source)}</span></span>
                <CatalogTablePicker
                  triggerLabel="Change"
                  triggerVariant="ghost"
                  triggerAriaLabel={multiple ? `Change source table ${index + 1}` : "Change source table"}
                  excludeConnectorIds={connectorsInUse(sources, source.id)}
                  onSelect={(hit) => onChange(source.id, hit)}
                />
                {multiple ? (
                  <span className="ml-auto flex items-center gap-1">
                    {source.id === activeSourceId ? null : <Button variant="ghost" size="sm" onClick={() => onShowColumns(source.id)}>Show columns</Button>}
                    <IconButton label={`Remove source table ${index + 1}`} icon={Trash2} onClick={() => onRemove(source.id)} />
                  </span>
                ) : null}
              </div>
              {sourceError ? <p className="mt-1.5 type-meta text-error">{sourceError}</p> : null}
            </li>
          );
        })}
      </ul>
      {canAddSource ? <CatalogTablePicker triggerLabel="Use another connector" triggerVariant="ghost" excludeConnectorIds={connectorsInUse(sources, null)} onSelect={onAdd} /> : null}
      {canAddSource || multiple ? <p className="type-meta text-foreground-secondary">Same table through another connection, such as a separate compute warehouse or a migrated copy. Each query runs on one connector.</p> : null}
      {multiple && <p className="type-meta text-foreground-secondary">Numbers match only when these tables hold the same data.</p>}
    </div>
  );
}

