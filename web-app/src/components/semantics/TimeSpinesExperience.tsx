import * as Dialog from "@radix-ui/react-dialog";
import { semanticSaveFailure, type SemanticSaveFailure } from "@/api/semantic-check";
import { useEffect, useMemo, useState, useId } from "react";
import { useQuery } from "@tanstack/react-query";
import {
  ArrowLeft,
  Pencil,
  Plus,
  Save,
  Trash2,
  TriangleAlert,
  X,
} from "lucide-react";
import { catalogAPI } from "@/api/catalog";
import { CatalogTablePicker } from "@/components/catalog/CatalogTablePicker";
import { usePrincipalId, useWorkspaceId } from "@/hooks/use-workspace";
import { queryKeys } from "@/lib/query/keys";
import type { CatalogTableSearchHit } from "@/lib/types/catalog";
import { TIME_GRANULARITIES, TIME_GRANULARITY_LABELS } from "@/lib/types/semantic-model";
import { Button } from "@/components/ui/Button";
import { DIALOG_OVERLAY_CLASS } from "@/components/ui/Dialog";
import { IconButton } from "@/components/ui/IconButton";
import { StateBlock } from "@/components/ui/StateBlock";
import { FIELD_LABEL_CLASS, Input, NativeSelect } from "@/components/ui/Input";
import { ConnectorTypeMark } from "@/components/connectors/ConnectorTypeMark";
import { CONNECTOR_TYPE_META } from "@/components/connectors/connectorTypeMeta";
import type { Connector } from "@/lib/types/connector";
import {
  type TimeSpine,
  type TimeSpineConfiguration,
  type TimeSpineDefinition,
  type TimeSpineGranularity,
} from "@/lib/types/time-spine";

export interface TimeSpinesCollectionState {
  items: TimeSpineConfiguration[];
  loading: boolean;
  error: string | null;
}

interface TimeSpinesExperienceProps {
  canEdit: boolean;
  connectors: Connector[];
  configurations: TimeSpinesCollectionState;
  selectedConnectorId?: string;
  query: string;
  onConnectorChange: (connectorId: string) => void;
  onSave: (connectorId: string, definition: TimeSpineDefinition) => Promise<TimeSpineDefinition>;
}

const TIME_SPINE_PURPOSE =
  "Running totals, conversion metrics, and metric inputs with an offset need a Calendar table: a table with one row per day, week, or other period. Without one, those queries fail with “This metric needs a time spine.”";

const FOCUS_CLASS =
  "focus-visible:outline focus-visible:outline-[2px] focus-visible:outline-offset-2 focus-visible:outline-[var(--focus-ring)]";

type InspectorMode = "detail" | "edit";

interface SavedDefinitionOverride {
  definition: TimeSpineDefinition;
  sourceSignature: string;
}

function emptyDefinition(): TimeSpineDefinition {
  return { schemaVersion: 1, timeSpines: [] };
}

function emptySpine(): TimeSpine {
  return {
    relation: { schema: "", relation: "" },
    primaryColumn: { name: "", granularity: "day" },
  };
}

function newDraftRowKey(): string {
  return `time-spine-row-${crypto.randomUUID()}`;
}

function cloneDefinition(definition: TimeSpineDefinition): TimeSpineDefinition {
  return {
    schemaVersion: 1,
    timeSpines: definition.timeSpines.map((spine) => ({
      relation: {
        ...(spine.relation.catalog ? { catalog: spine.relation.catalog } : {}),
        schema: spine.relation.schema,
        relation: spine.relation.relation,
      },
      primaryColumn: {
        name: spine.primaryColumn.name,
        granularity: spine.primaryColumn.granularity,
      },
    })),
  };
}

function definitionSignature(definition: TimeSpineDefinition | undefined): string {
  return JSON.stringify(definition ?? emptyDefinition());
}

function relationPath(spine: TimeSpine): string {
  const catalog = spine.relation.catalog ? `${spine.relation.catalog}.` : "";
  return `${catalog}${spine.relation.schema}.${spine.relation.relation}`;
}

function spineSearchValues(spine: TimeSpine): string[] {
  return [
    spine.primaryColumn.granularity,
    spine.relation.catalog,
    spine.relation.schema,
    spine.relation.relation,
    spine.primaryColumn.name,
  ].filter((value): value is string => Boolean(value));
}

function connectorSearchValues(connector: Connector): string[] {
  return [connector.name, connector.type, CONNECTOR_TYPE_META[connector.type].label];
}

function matchesSearch(query: string, values: string[]): boolean {
  return query.length === 0 || values.some((value) => value.toLowerCase().includes(query));
}

export function validateTimeSpineDefinition(definition: TimeSpineDefinition): string | null {

  const seenGranularities = new Set<TimeSpineGranularity>();
  for (const [index, spine] of definition.timeSpines.entries()) {
    const row = index + 1;
    if (!spine.relation.schema.trim() || !spine.relation.relation.trim()) return `Calendar table ${row}: choose a table.`;
    if (!spine.primaryColumn.name.trim()) return `Calendar table ${row}: choose a primary column.`;
    if (seenGranularities.has(spine.primaryColumn.granularity)) {
      return `Calendar table ${row}: each granularity can be configured only once.`;
    }
    seenGranularities.add(spine.primaryColumn.granularity);
  }
  return null;
}

export function TimeSpinesExperience({
  canEdit,
  connectors,
  configurations,
  selectedConnectorId,
  query,
  onConnectorChange,
  onSave,
}: TimeSpinesExperienceProps) {
  const normalizedQuery = query.trim().toLowerCase();
  const selectedId = connectors.some((connector) => connector.id === selectedConnectorId)
    ? selectedConnectorId ?? null
    : connectors.length === 1 ? connectors[0].id : null;
  const [mode, setMode] = useState<InspectorMode>("detail");
  const [draftConnectorId, setDraftConnectorId] = useState<string | null>(null);
  const [draft, setDraft] = useState<TimeSpineDefinition>(emptyDefinition);
  const [draftRowKeys, setDraftRowKeys] = useState<string[]>([]);
  const [savedDefinitionOverrides, setSavedDefinitionOverrides] = useState<Record<string, SavedDefinitionOverride>>({});
  const [validationError, setValidationError] = useState<string | null>(null);
  const [saveError, setSaveError] = useState<SemanticSaveFailure | null>(null);
  const [saving, setSaving] = useState(false);
  const [mobileSelectionOpen, setMobileSelectionOpen] = useState(Boolean(selectedId));
  const [isNarrow, setIsNarrow] = useState(
    () => typeof window !== "undefined" && window.innerWidth < 1280,
  );

  useEffect(() => {
    if (connectors.length === 1 && selectedConnectorId !== connectors[0].id) {
      onConnectorChange(connectors[0].id);
    }
  }, [connectors, onConnectorChange, selectedConnectorId]);

  useEffect(() => {
    const mediaQuery = window.matchMedia("(max-width: 1279px)");
    const updateViewport = () => setIsNarrow(mediaQuery.matches);
    updateViewport();
    mediaQuery.addEventListener("change", updateViewport);
    return () => mediaQuery.removeEventListener("change", updateViewport);
  }, []);

  const configurationByConnector = useMemo(
    () => new Map(configurations.items.map((configuration) => [configuration.connectorId, configuration])),
    [configurations.items],
  );

  const connectorById = useMemo(
    () => new Map(connectors.map((connector) => [connector.id, connector])),
    [connectors],
  );

  useEffect(() => {
    setSavedDefinitionOverrides((current) => {
      let changed = false;
      const next = { ...current };
      for (const [connectorId, override] of Object.entries(current)) {
        const serverDefinition = configurationByConnector.get(connectorId)?.definition;
        if (definitionSignature(serverDefinition) === override.sourceSignature) continue;
        delete next[connectorId];
        changed = true;
      }
      return changed ? next : current;
    });
  }, [configurationByConnector]);

  const visibleConnectors = useMemo(() => {
    if (normalizedQuery.length === 0) {
      return connectors.map((connector) => ({
        connector,
        matchingSpines: configurationByConnector.get(connector.id)?.definition.timeSpines ?? [],
      }));
    }
    return connectors.flatMap((connector) => {
      const configuration = configurationByConnector.get(connector.id);
      const connectorMatches = matchesSearch(normalizedQuery, connectorSearchValues(connector));
      const matchingSpines = configuration?.definition.timeSpines.filter((spine) =>
        matchesSearch(normalizedQuery, spineSearchValues(spine)),
      ) ?? [];
      if (connectorMatches || matchingSpines.length > 0) {
        return [{ connector, matchingSpines: connectorMatches ? configuration?.definition.timeSpines ?? [] : matchingSpines }];
      }
      return [];
    });
  }, [configurationByConnector, connectors, normalizedQuery]);

  const selectedConnector = selectedId ? connectorById.get(selectedId) : undefined;
  const selectedConfiguration = selectedId ? configurationByConnector.get(selectedId) : undefined;
  const selectedOverride = selectedId ? savedDefinitionOverrides[selectedId] : undefined;
  const currentSourceSignature = definitionSignature(selectedConfiguration?.definition);
  const selectedDefinition = selectedId
    ? selectedOverride && selectedOverride.sourceSignature === currentSourceSignature
      ? selectedOverride.definition
      : selectedConfiguration?.definition ?? emptyDefinition()
    : emptyDefinition();
  const selectedDraft = draftConnectorId === selectedId ? draft : selectedDefinition;

  const selectConnector = (connectorId: string) => {
    onConnectorChange(connectorId);
    setMode("detail");
    setDraftConnectorId(null);
    setValidationError(null);
    setSaveError(null);
    setMobileSelectionOpen(true);
  };

  const startEditing = () => {
    if (!canEdit) return;
    if (!selectedId) return;
    setDraftConnectorId(selectedId);
    setDraft(cloneDefinition(selectedDefinition));
    setDraftRowKeys(selectedDefinition.timeSpines.map(() => newDraftRowKey()));
    setValidationError(null);
    setSaveError(null);
    setMode("edit");
  };

  const returnToDetail = () => {
    setMode("detail");
    setValidationError(null);
    setSaveError(null);
  };

  const updateSpine = (index: number, update: (spine: TimeSpine) => TimeSpine) => {
    setDraft((current) => ({
      schemaVersion: 1,
      timeSpines: current.timeSpines.map((spine, spineIndex) => spineIndex === index ? update(spine) : spine),
    }));
    setValidationError(null);
    setSaveError(null);
  };

  const addSpine = () => {
    const rowKey = newDraftRowKey();
    setDraft((current) => ({ schemaVersion: 1, timeSpines: [...current.timeSpines, emptySpine()] }));
    setDraftRowKeys((current) => [...current, rowKey]);
    setValidationError(null);
    setSaveError(null);
  };

  const removeSpine = (index: number) => {
    setDraft((current) => ({
      schemaVersion: 1,
      timeSpines: current.timeSpines.filter((_, spineIndex) => spineIndex !== index),
    }));
    setDraftRowKeys((current) => current.filter((_, keyIndex) => keyIndex !== index));
    setValidationError(null);
    setSaveError(null);
  };

  const save = async () => {
    if (!canEdit || !selectedId || draftConnectorId !== selectedId) return;
    const error = validateTimeSpineDefinition(draft);
    if (error) {
      setValidationError(error);
      return;
    }
    setSaving(true);
    setValidationError(null);
    setSaveError(null);
    try {
      const sourceSignature = definitionSignature(selectedConfiguration?.definition);
      const saved = await onSave(selectedId, cloneDefinition(draft));
      const savedClone = cloneDefinition(saved);
      setSavedDefinitionOverrides((current) => ({
        ...current,
        [selectedId]: { definition: savedClone, sourceSignature },
      }));
      setDraft(savedClone);
      setMode("detail");
    } catch (error) {
      setSaveError(semanticSaveFailure(error));
    } finally {
      setSaving(false);
    }
  };

  const detail = selectedConnector ? (
    <TimeSpineInspector
      canEdit={canEdit}
      connector={selectedConnector}
      definition={selectedDefinition}
      draft={selectedDraft}
      draftRowKeys={draftRowKeys}
      mode={mode}
      saving={saving}
      validationError={validationError}
      saveError={saveError}
      onEdit={startEditing}
      onBack={returnToDetail}
      onSave={() => void save()}
      onDraftSpineChange={updateSpine}
      onAddSpine={addSpine}
      onRemoveSpine={removeSpine}
      onClose={() => setMobileSelectionOpen(false)}
    />
  ) : (
    <EmptyInspector />
  );

  return (
    <div className="flex min-h-0 flex-1 flex-col bg-background" data-testid="time-spines-experience">
      {configurations.loading ? (
        <div aria-label="Calendar tables loading">
          <StateBlock kind="loading" layout="page" title="Loading Calendar tables…" />
        </div>
      ) : configurations.error ? (
        <StateBlock headingLevel={2} kind="error" layout="page" title="Unable to load Calendar tables" description={configurations.error} />
      ) : connectors.length === 0 ? (
        <StateBlock headingLevel={2} kind="empty" layout="page" title="No connectors yet" description="Add a connector before configuring a Calendar table." />
      ) : (
        <>
          <div className="flex min-h-0 min-w-0 flex-1">
            <section className="min-h-0 min-w-0 flex-1 overflow-y-auto" aria-label="Connectors and Calendar tables">
              <div className="border-b border-border px-4 py-3 sm:px-6">
                <span className="type-group text-foreground-secondary">Connectors</span>
              </div>
              {visibleConnectors.length === 0 ? (
                <StateBlock kind="empty" layout="inline" title={`No connectors or Calendar tables match “${query}”.`} />
              ) : (
                <div>
                  {visibleConnectors.map(({ connector, matchingSpines }) => {
                    const selected = connector.id === selectedId;
                    const configuration = configurationByConnector.get(connector.id);
                    return (
                      <div key={connector.id} className="border-b border-border-light last:border-b-0">
                        <button
                          type="button"
                          aria-current={selected ? "true" : undefined}
                          onClick={() => selectConnector(connector.id)}
                          className={`relative flex min-h-[68px] w-full items-center gap-3 px-4 py-3 text-left transition-colors sm:px-6 ${FOCUS_CLASS} ${selected ? "bg-surface" : "hover:bg-surface/60"}`}
                        >
                          {selected ? <span aria-hidden="true" className="absolute inset-y-0 left-0 w-[2px] bg-accent-marker" /> : null}
                          <ConnectorTypeMark type={connector.type} size="sm" />
                          <span className="min-w-0 flex-1">
                            <span className="type-item block truncate text-foreground">{connector.name}</span>
                            <span className="type-meta mt-0.5 block truncate text-foreground-secondary">{CONNECTOR_TYPE_META[connector.type].label}</span>
                          </span>
                          <span className="type-status inline-flex flex-none items-center gap-1.5 tabular-nums text-foreground-secondary">
                            <span className="h-1.5 w-1.5 rounded-full bg-foreground-secondary" aria-hidden="true" />
                            {configuration && configuration.definition.timeSpines.length > 0
                              ? `${configuration.definition.timeSpines.length} configured`
                              : "Not configured"}
                          </span>
                        </button>
                        {configuration && matchingSpines.length > 0 ? (
                          <div className="border-t border-border-light bg-background py-1 pl-10 pr-4 sm:pl-16 sm:pr-6">
                            {matchingSpines.map((spine, index) => (
                              <div key={`${connector.id}-${index}-${spine.primaryColumn.granularity}`} className="flex min-h-[50px] items-center gap-3 border-b border-border-light py-2 last:border-b-0">
                                <span className="type-group w-24 flex-none text-foreground-secondary">{TIME_GRANULARITY_LABELS[spine.primaryColumn.granularity]}</span>
                                <span className="min-w-0 flex-1">
                                  <span className="type-mono block truncate text-foreground">{relationPath(spine)}</span>
                                  <span className="type-meta mt-0.5 block truncate text-foreground-secondary">Primary column · {spine.primaryColumn.name}</span>
                                </span>
                              </div>
                            ))}
                          </div>
                        ) : null}
                      </div>
                    );
                  })}
                </div>
              )}
            </section>

            <aside className="hidden min-h-0 w-[520px] flex-none overflow-hidden border-l border-border xl:flex" aria-label="Calendar table details">
              {detail}
            </aside>
          </div>

          <Dialog.Root
            open={isNarrow && mobileSelectionOpen && Boolean(selectedConnector)}
            onOpenChange={setMobileSelectionOpen}
          >
            <Dialog.Portal>
              <Dialog.Overlay className={`${DIALOG_OVERLAY_CLASS} xl:hidden`} />
              <Dialog.Content
                data-testid="time-spines-drawer"
                className="fixed inset-y-0 right-0 z-50 flex w-full max-w-[560px] flex-col overflow-hidden border-l border-border bg-background shadow-modal focus:outline-none xl:hidden"
                style={{ fontFeatureSettings: '"ss01"' }}
                onPointerDownOutside={(event) => event.preventDefault()}
              >
                <Dialog.Title className="sr-only">Calendar table details</Dialog.Title>
                <Dialog.Description className="sr-only">Inspect or configure the selected connector’s Calendar tables.</Dialog.Description>
                {detail}
              </Dialog.Content>
            </Dialog.Portal>
          </Dialog.Root>
        </>
      )}
    </div>
  );
}

function EmptyInspector() {
  return (
    <StateBlock kind="empty" layout="page" title="Select a connector to inspect its Calendar table configuration." />
  );
}

function TimeSpineInspector({
  canEdit,
  connector,
  definition,
  draft,
  draftRowKeys,
  mode,
  saving,
  validationError,
  saveError,
  onEdit,
  onBack,
  onSave,
  onDraftSpineChange,
  onAddSpine,
  onRemoveSpine,
  onClose,
}: {
  canEdit: boolean;
  connector: Connector;
  definition: TimeSpineDefinition;
  draft: TimeSpineDefinition;
  draftRowKeys: string[];
  mode: InspectorMode;
  saving: boolean;
  validationError: string | null;
  saveError: SemanticSaveFailure | null;
  onEdit: () => void;
  onBack: () => void;
  onSave: () => void;
  onDraftSpineChange: (index: number, update: (spine: TimeSpine) => TimeSpine) => void;
  onAddSpine: () => void;
  onRemoveSpine: (index: number) => void;
  onClose: () => void;
}) {
  const isEditing = canEdit && mode === "edit";
  return (
    <div className="flex min-h-0 min-w-0 flex-1 flex-col bg-background">
      <header className="flex flex-none items-start justify-between gap-3 border-b border-border px-5 py-4 sm:px-6">
        <div className="flex min-w-0 items-start gap-3">
          <ConnectorTypeMark type={connector.type} size="md" />
          <div className="min-w-0">
            <p className="type-group text-foreground-secondary">Calendar tables</p>
            <h3 className="type-section mt-1 truncate text-heading">{connector.name}</h3>
            <p className="type-meta mt-1 truncate text-foreground-secondary">{CONNECTOR_TYPE_META[connector.type].label}</p>
          </div>
        </div>
        <div className="flex flex-none items-center gap-1">
          {isEditing ? (
            <IconButton label="Back to Calendar table details" icon={ArrowLeft} title="Back to details" onClick={onBack} />
          ) : canEdit && definition.timeSpines.length > 0 ? (
            <IconButton label={`Configure Calendar tables for ${connector.name}`} icon={Pencil} title="Configure Calendar tables" onClick={onEdit} />
          ) : null}
          <IconButton label="Close Calendar table details" icon={X} title="Close" className="xl:hidden" onClick={onClose} />
        </div>
      </header>

      <p className="type-meta flex-none border-b border-border px-5 py-3 text-foreground-secondary sm:px-6">
        {TIME_SPINE_PURPOSE}
      </p>

      {isEditing ? (
        <div className="min-h-0 flex-1 overflow-y-auto px-5 py-5 sm:px-6">
          <div className="mb-4 flex items-center justify-between gap-3">
            <div>
              <h4 className="type-item text-heading">Configure Calendar tables</h4>
              <p className="type-meta mt-1 text-foreground-secondary">One configuration replaces the connector’s current set.</p>
            </div>
            <Button type="button" size="sm" pending={saving} onClick={onSave}>
              {!saving && <Save aria-hidden="true" />}
              Save
            </Button>
          </div>

          {validationError || saveError ? (
            <div className="mb-4 flex items-start gap-2 border border-error/30 bg-error/5 px-3 py-2.5 type-meta text-error" role="alert">
              <TriangleAlert className="mt-0.5 h-3.5 w-3.5 flex-none" aria-hidden="true" />
              <span>{validationError ?? saveError?.message}</span>
            </div>
          ) : null}

          <div className="space-y-4">
            {draft.timeSpines.map((spine, index) => (
              <TimeSpineEditorRow
                key={draftRowKeys[index]}
                index={index}
                connectorId={connector.id}
                spine={spine}
                fieldErrors={saveError?.fieldErrors ?? {}}
                onChange={(update) => onDraftSpineChange(index, update)}
                onRemove={() => onRemoveSpine(index)}
              />
            ))}
            <Button type="button" variant="secondary" size="md" onClick={onAddSpine}>
              <Plus aria-hidden="true" />
              Add Calendar table
            </Button>
            {draft.timeSpines.length === 0 ? (
              <p className="type-meta text-foreground-secondary">No calendar tables in this draft. Save to clear the configuration.</p>
            ) : null}
          </div>
        </div>
      ) : (
        <div className="min-h-0 flex-1 overflow-y-auto px-5 py-5 sm:px-6">
          <div className="mb-5 flex items-end justify-between gap-3 border-b border-border pb-3">
            <h4 className="type-item text-heading">Configured Calendar tables</h4>
            <span className="type-meta tabular-nums text-foreground-secondary">{definition.timeSpines.length}</span>
          </div>
          {definition.timeSpines.length === 0 ? (
            <div className="border-b border-border">
              <StateBlock
                kind="empty"
                layout="inline"
                title="No Calendar table is configured for this connector."
                action={canEdit ? (
                  <Button type="button" variant="secondary" size="md" onClick={onEdit}>
                    <Plus aria-hidden="true" />
                    Configure Calendar tables
                  </Button>
                ) : undefined}
              />
            </div>
          ) : (
            <dl className="divide-y divide-border">
              {definition.timeSpines.map((spine, index) => (
                <div key={`${index}-${spine.primaryColumn.granularity}`} className="grid grid-cols-[88px_minmax(0,1fr)] gap-4 py-4 first:pt-0">
                  <dt className="type-group text-foreground-secondary">{TIME_GRANULARITY_LABELS[spine.primaryColumn.granularity]}</dt>
                  <dd className="min-w-0">
                    <div className="type-mono truncate text-foreground">{relationPath(spine)}</div>
                    <div className="type-meta mt-1 text-foreground-secondary">Primary column · <span className="text-foreground">{spine.primaryColumn.name}</span></div>
                  </dd>
                </div>
              ))}
            </dl>
          )}
        </div>
      )}
    </div>
  );
}

// Columns come from Catalog. A table Catalog cannot describe (not loaded, removed, or typed by
// hand) falls back to free-text names, because the save contract never checks existence.
export function TimeSpineEditorRow({
  index,
  connectorId,
  spine,
  fieldErrors = {},
  onChange,
  onRemove,
}: {
  index: number;
  connectorId: string;
  spine: TimeSpine;
  fieldErrors?: Record<string, string>;
  onChange: (update: (spine: TimeSpine) => TimeSpine) => void;
  onRemove: () => void;
}) {
  const workspaceId = useWorkspaceId();
  const principalId = usePrincipalId();
  const [typingTableName, setTypingTableName] = useState(false);
  // The detail renders in both the wide aside and the narrow drawer, so ids must be unique per instance.
  const instanceId = useId();
  const fieldId = (field: string) => `${instanceId}time-spine-${index}-${field}`;
  const hasTable = Boolean(spine.relation.schema.trim() && spine.relation.relation.trim());
  const relationQuery = useQuery({
    queryKey: queryKeys.catalog.relation(
      workspaceId, principalId ?? "", connectorId, spine.relation.catalog ?? null, spine.relation.schema, spine.relation.relation,
    ),
    queryFn: () => catalogAPI.getRelation(workspaceId, connectorId, spine.relation.relation, spine.relation.schema, spine.relation.catalog),
    enabled: Boolean(principalId) && hasTable && !typingTableName,
    retry: false,
  });
  const catalogUnavailable = hasTable && !typingTableName
    && (relationQuery.isError || (relationQuery.isPending && relationQuery.fetchStatus === "idle"));
  const freeText = typingTableName || catalogUnavailable;
  const columns = relationQuery.data?.columns ?? [];
  const columnListed = columns.some((column) => column.name === spine.primaryColumn.name);

  // Typing stops Catalog lookups so the form does not switch to a column list mid-edit.
  const editTableName = (patch: Partial<TimeSpine["relation"]>) => {
    setTypingTableName(true);
    onChange((current) => ({ ...current, relation: { ...current.relation, ...patch } }));
  };

  const chooseTable = (hit: CatalogTableSearchHit) => {
    setTypingTableName(false);
    onChange((current) => ({
      relation: {
        ...(hit.catalogName ? { catalog: hit.catalogName } : {}),
        schema: hit.schemaName,
        relation: hit.tableName,
      },
      primaryColumn: { ...current.primaryColumn, name: "" },
    }));
  };

  return (
    <fieldset className="border border-border p-4">
      <div className="flex items-center justify-between gap-3">
        <legend className="type-control text-foreground">Calendar table {index + 1}</legend>
        <Button
          type="button"
          variant="ghost"
          size="sm"
          onClick={onRemove}
          aria-label={`Remove Calendar table ${index + 1}`}
        >
          <Trash2 aria-hidden="true" />
          Remove
        </Button>
      </div>

      <div className="mt-4">
        <span className={FIELD_LABEL_CLASS}>Table</span>
        <div className="flex flex-wrap items-center gap-x-3 gap-y-2">
          {hasTable && !typingTableName ? (
            <span className="type-mono min-w-0 truncate text-foreground">{relationPath(spine)}</span>
          ) : !typingTableName ? (
            <span className="type-meta text-foreground-secondary">No table chosen</span>
          ) : null}
          <CatalogTablePicker
            triggerLabel={hasTable && !typingTableName ? "Change table" : "Choose table"}
            triggerAriaLabel={`${hasTable && !typingTableName ? "Change" : "Choose"} table for Calendar table ${index + 1}`}
            connectorId={connectorId}
            onSelect={chooseTable}
          />
          {!freeText ? (
            <Button type="button" variant="ghost" size="sm" onClick={() => setTypingTableName(true)}>
              Type the table name
            </Button>
          ) : null}
        </div>
        {catalogUnavailable ? (
          <p className="type-meta mt-1.5 text-foreground-secondary">
            Catalog has no columns for this table. Check the names below, or load the table in Catalog to pick its column.
          </p>
        ) : typingTableName ? (
          <p className="type-meta mt-1.5 text-foreground-secondary">
            Typed names are not checked when you save; a query that needs this Calendar table fails if they are wrong.
          </p>
        ) : null}
      </div>

      <div className="mt-4 grid grid-cols-1 gap-4 sm:grid-cols-2">
        {freeText ? (
          <>
            <div>
              <label className={FIELD_LABEL_CLASS} htmlFor={fieldId("catalog")}>
                Catalog <span className="text-foreground-secondary">(optional)</span>
              </label>
              <Input
                id={fieldId("catalog")}
                error={fieldErrors[`timeSpines[${index}].relation.catalog`]}
                value={spine.relation.catalog ?? ""}
                placeholder="warehouse"
                onChange={(event) => editTableName({ catalog: event.target.value || undefined })}
              />
            </div>
            <Input
              id={fieldId("schema")}
              label="Schema"
              error={fieldErrors[`timeSpines[${index}].relation.schema`]}
              value={spine.relation.schema}
              placeholder="analytics"
              onChange={(event) => editTableName({ schema: event.target.value })}
            />
            <Input
              id={fieldId("relation")}
              label="Table"
              error={fieldErrors[`timeSpines[${index}].relation.relation`]}
              value={spine.relation.relation}
              placeholder="dim_date"
              onChange={(event) => editTableName({ relation: event.target.value })}
            />
            <Input
              id={fieldId("column")}
              label="Primary column"
              error={fieldErrors[`timeSpines[${index}].primaryColumn.name`]}
              value={spine.primaryColumn.name}
              placeholder="date_day"
              onChange={(event) => onChange((current) => ({
                ...current,
                primaryColumn: { ...current.primaryColumn, name: event.target.value },
              }))}
            />
          </>
        ) : (
          <NativeSelect
            id={fieldId("column")}
            label="Primary column"
            error={fieldErrors[`timeSpines[${index}].primaryColumn.name`]}
            value={spine.primaryColumn.name}
            disabled={!hasTable || relationQuery.isPending}
            onChange={(event) => onChange((current) => ({
              ...current,
              primaryColumn: { ...current.primaryColumn, name: event.target.value },
            }))}
          >
            <option value="">
              {!hasTable ? "Choose a table first" : relationQuery.isPending ? "Loading columns…" : "Choose a column"}
            </option>
            {spine.primaryColumn.name && !columnListed && !relationQuery.isPending ? (
              <option value={spine.primaryColumn.name}>{spine.primaryColumn.name} (not in Catalog)</option>
            ) : null}
            {columns.map((column) => (
              <option key={column.name} value={column.name}>{column.name} · {column.nativeType}</option>
            ))}
          </NativeSelect>
        )}
        <div className={freeText ? "sm:col-span-2" : undefined}>
          <NativeSelect
            id={fieldId("granularity")}
            label="Grain"
            error={fieldErrors[`timeSpines[${index}].primaryColumn.granularity`]}
            value={spine.primaryColumn.granularity}
            onChange={(event) => onChange((current) => ({
              ...current,
              primaryColumn: {
                ...current.primaryColumn,
                granularity: event.target.value as TimeSpineGranularity,
              },
            }))}
          >
            {TIME_GRANULARITIES.map((granularity) => (
              <option key={granularity} value={granularity}>{TIME_GRANULARITY_LABELS[granularity]}</option>
            ))}
          </NativeSelect>
        </div>
      </div>
    </fieldset>
  );
}
