import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import type { PartitionRepairAction, PartitionRepairRequest } from "@/components/semantics/source-partition";
import { useWorkspaceAccess } from "@/components/auth/WorkspaceAccessProvider";
import { useWorkspaceRouteContext } from "@/components/auth/TenantRouteContext";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useNavigate, useSearch } from "@tanstack/react-router";
import { metricAPI } from "@/api/metric";
import { semanticModelAPI } from "@/api/semantic-model";
import {
  exploreAPI,
  newExploreIdempotencyKey,
  type ExploreRun,
  type SemanticGroupBy,
  type SemanticQuerySpec,
} from "@/api/explore";
import { toExploreErrorPresentation } from "@/api/explore-errors";
import { MainLayout } from "@/components/layout/MainLayout";
import { useConnectors } from "@/hooks/useConnectors";
import { usePrincipalId, useWorkspaceId } from "@/hooks/use-workspace";
import { queryKeys } from "@/lib/query/keys";
import {
  draftFromRun,
  draftMatchesRun,
  emptyExploreDraft,
  NO_UNAVAILABLE_SELECTIONS,
  readStoredDraft,
  replacedQuestionWorthKeeping,
  semanticQueryOf,
  tablePreviewSql,
  unavailableSelections,
  writeStoredDraft,
  type ExploreDraft,
  type ExploreMode,
  type SemanticDraft,
} from "./explore-draft";
import { ExploreWorkbench } from "./ExploreWorkbench";
import { connectorName, startRejectionGuidance } from "./run-presentation";
import { metricRunProblem, metricsReadingModel, resolveSemanticConnectors } from "./semantic-connectors";
import { withMetricChosen } from "./SemanticQuestion";
import { filterComparisonMismatch } from "./semantic-filters";

const RUNNING_POLL_INTERVAL_MS = 2_000;

// A start mutation's error state renders the rejection, so its returned promise only needs settling.
function settleRenderedStartRejection(): void {}

export function selectActiveExploreRun(
  selectedRunId: string | null,
  data: ExploreRun | undefined,
  error: unknown,
): ExploreRun | null {
  if (!selectedRunId || error != null || !data || data.runId !== selectedRunId) return null;
  return data;
}

/** A Metrics question asking only for one metric; its connector comes from the metric. */
export function questionForMetric(metricName: string): SemanticDraft {
  return { ...emptyExploreDraft().semantic, metrics: [metricName] };
}

function sameGroupBy(left: SemanticGroupBy, right: SemanticGroupBy): boolean {
  return JSON.stringify(left) === JSON.stringify(right);
}

/** Keeps sort orders only for metrics and group-bys that are still in the question. */
export function normalizeSemanticDraft(draft: SemanticDraft): SemanticDraft {
  const orderBy = draft.orderBy.filter((order) => order.target.kind === "METRIC"
    ? draft.metrics.includes(order.target.metricName)
    : draft.groupBys.some((groupBy) => order.target.kind === "GROUP_BY" && sameGroupBy(groupBy, order.target.groupBy)));
  return orderBy.length === draft.orderBy.length ? draft : { ...draft, orderBy };
}

export default function ExplorePage() {
  const workspaceId = useWorkspaceId();
  return <ExploreWorkspacePage key={workspaceId} workspaceId={workspaceId} />;
}

function ExploreWorkspacePage({ workspaceId }: { workspaceId: string }) {
  const principalId = usePrincipalId();
  const access = useWorkspaceAccess();
  const { reportingTimeZone } = useWorkspaceRouteContext().workspace;
  const search = useSearch({ from: "/workspaces/$workspaceId/explore" });
  const navigate = useNavigate({ from: "/workspaces/$workspaceId/explore" });
  const queryClient = useQueryClient();
  const runId = search.run ?? null;
  const { connectors, loading: connectorsLoading, error: connectorsError, retry: retryConnectors } = useConnectors();
  const [draft, setDraftState] = useState<ExploreDraft>(() => readStoredDraft(workspaceId) ?? emptyExploreDraft());
  const [newlyAddedRunId, setNewlyAddedRunId] = useState<string | null>(null);
  const [renderedSqlOpen, setRenderedSqlOpen] = useState(false);
  const [partitionRepair, setPartitionRepair] = useState<PartitionRepairRequest | null>(null);
  const partitionRepairSequence = useRef(0);
  const requestPartitionRepair = (action: PartitionRepairAction) => {
    setDraftState((current) => ({ ...current, mode: "SEMANTIC" }));
    setPartitionRepair({ ...action, id: ++partitionRepairSequence.current });
  };

  useEffect(() => {
    writeStoredDraft(workspaceId, draft);
  }, [draft, workspaceId]);

  const metricsQuery = useQuery({
    queryKey: queryKeys.metrics.all(workspaceId, principalId ?? ""),
    queryFn: () => metricAPI.list(workspaceId),
    enabled: Boolean(workspaceId && principalId),
  });
  const semanticModelsQuery = useQuery({
    queryKey: queryKeys.semanticModels.all(workspaceId, principalId ?? ""),
    queryFn: () => semanticModelAPI.list(workspaceId),
    enabled: Boolean(workspaceId && principalId),
  });
  const metrics = useMemo(() => metricsQuery.data ?? [], [metricsQuery.data]);
  const semanticModels = useMemo(() => semanticModelsQuery.data ?? [], [semanticModelsQuery.data]);

  const definitionsResolving = connectorsLoading || metricsQuery.isPending || semanticModelsQuery.isPending;
  const connectorResolution = useMemo(
    () => resolveSemanticConnectors(draft.semantic.metrics, metrics, semanticModels, connectors.map((connector) => connector.id)),
    [connectors, draft.semantic.metrics, metrics, semanticModels],
  );
  const compatibleIds = connectorResolution.compatible;
  const semanticConnectorId = compatibleIds.length === 1
    ? compatibleIds[0] as string
    : draft.semantic.connectorId && compatibleIds.includes(draft.semantic.connectorId) ? draft.semantic.connectorId : null;
  const sqlConnectorId = draft.sql.connectorId && connectors.some((connector) => connector.id === draft.sql.connectorId)
    ? draft.sql.connectorId
    : connectors.length === 1 ? (connectors[0] as (typeof connectors)[number]).id : null;

  const groupByQuery = useQuery({
    queryKey: queryKeys.explore.semanticGroupBys(workspaceId, principalId ?? "", semanticConnectorId ?? "", draft.semantic.metrics),
    queryFn: () => exploreAPI.listSemanticGroupBys({
      workspaceId,
      connectorId: semanticConnectorId as string,
      metrics: draft.semantic.metrics,
    }),
    enabled: Boolean(workspaceId && principalId && semanticConnectorId && draft.semantic.metrics.length > 0),
  });

  const runQuery = useQuery({
    queryKey: queryKeys.explore.run(workspaceId, principalId ?? "", runId ?? ""),
    queryFn: () => exploreAPI.readRun({ workspaceId, runId: runId as string }),
    enabled: Boolean(workspaceId && principalId && runId),
    refetchInterval: (query) => query.state.data?.state === "RUNNING" ? RUNNING_POLL_INTERVAL_MS : false,
  });
  const recentRunsQuery = useQuery({
    queryKey: queryKeys.explore.recentRuns(workspaceId, principalId ?? ""),
    queryFn: () => exploreAPI.listRecentRuns({ workspaceId }),
    enabled: Boolean(workspaceId && principalId),
    refetchInterval: (query) => query.state.data?.some((run) => run.state === "RUNNING")
      ? RUNNING_POLL_INTERVAL_MS
      : false,
  });

  const sqlStartMutation = useMutation({
    mutationFn: (request: { connectorId: string; sql: string }) => exploreAPI.startSqlRun({
      workspaceId,
      request,
      idempotencyKey: newExploreIdempotencyKey("SQL"),
    }),
  });
  const semanticStartMutation = useMutation({
    mutationFn: (request: { connectorId: string; query: SemanticQuerySpec }) => exploreAPI.startSemanticRun({
      workspaceId,
      request,
      idempotencyKey: newExploreIdempotencyKey("SEMANTIC"),
    }),
  });
  const semanticSqlRenderMutation = useMutation({
    mutationFn: (request: { connectorId: string; query: SemanticQuerySpec }) => exploreAPI.renderSemanticSql({
      workspaceId,
      request,
    }),
  });
  const cancelMutation = useMutation({
    mutationFn: (cancelRunId: string) => exploreAPI.cancelRun({ workspaceId, runId: cancelRunId }),
    onSuccess: (updatedRun) => {
      queryClient.setQueryData(queryKeys.explore.run(workspaceId, principalId ?? "", updatedRun.runId), updatedRun);
      queryClient.setQueryData<ExploreRun[]>(
        queryKeys.explore.recentRuns(workspaceId, principalId ?? ""),
        (current) => (current ?? []).map((candidate) => candidate.runId === updatedRun.runId ? updatedRun : candidate),
      );
    },
  });

  const { reset: resetSqlStart } = sqlStartMutation;
  const { reset: resetSemanticStart } = semanticStartMutation;
  // An edit clears a settled rejection, but never a start still in flight: its outcome must land.
  const startPending = useRef({ sql: false, semantic: false });
  startPending.current = { sql: sqlStartMutation.isPending, semantic: semanticStartMutation.isPending };
  const setDraft = useCallback((update: (current: ExploreDraft) => ExploreDraft) => {
    if (!startPending.current.sql) resetSqlStart();
    if (!startPending.current.semantic) resetSemanticStart();
    setDraftState((current) => {
      const next = update(current);
      return next.semantic === current.semantic ? next : { ...next, semantic: normalizeSemanticDraft(next.semantic) };
    });
  }, [resetSemanticStart, resetSqlStart]);

  // A cross-connector metric choice that removed chosen metrics, kept while the metric list is unchanged.
  // Shown only while the question is exactly as the switch left it; any edit, Run, or mode change ends it.
  const [metricSwap, setMetricSwap] = useState<{ before: SemanticDraft; after: string; removed: string[]; removedCannotRun: boolean; chosen: string } | null>(null);
  // Counts metric choices that switched the connector, so the toolbar connector can mark the change once.
  const [connectorSwitches, setConnectorSwitches] = useState(0);

  const activeRun = selectActiveExploreRun(runId, runQuery.data, runQuery.error);
  const restoredRunId = useRef<string | null>(null);
  const activeRunRef = useRef(activeRun);
  activeRunRef.current = activeRun;
  // A question restored from the viewed run stays exactly as it ran until the person edits it; a
  // selection the current definitions no longer offer is then reported by the server on Run.
  const asksViewedRun = (draftState: ExploreDraft): boolean => {
    const viewed = activeRunRef.current;
    return Boolean(viewed && restoredRunId.current === viewed.runId && draftMatchesRun(draftState, viewed));
  };

  // Metrics deleted since the draft was saved leave the question.
  useEffect(() => {
    if (!metricsQuery.data) return;
    const current = metricsQuery.data;
    setDraftState((draftState) => {
      if (asksViewedRun(draftState)) return draftState;
      const kept = draftState.semantic.metrics.filter((name) => current.some((metric) => metric.name === name));
      return kept.length === draftState.semantic.metrics.length
        ? draftState
        : { ...draftState, semantic: normalizeSemanticDraft({ ...draftState.semantic, metrics: kept }) };
    });
  }, [metricsQuery.data]);

  // `?metric=` starts a fresh Metrics question with just that metric, then leaves the URL so a
  // reload keeps later edits. It never starts a run; an unknown name is dropped.
  const requestedMetric = search.metric ?? null;
  useEffect(() => {
    if (!requestedMetric || !metricsQuery.data || metricsQuery.isFetching) return;
    if (metricsQuery.data.some((metric) => metric.name === requestedMetric)) {
      setDraft((draftState) => ({ ...draftState, mode: "SEMANTIC", semantic: { ...questionForMetric(requestedMetric), groupBys: search.dimensionModel && search.dimension ? [{ kind: "DIMENSION", semanticModelName: search.dimensionModel, name: search.dimension, granularity: search.granularity ?? null }] : [] } }));
    }
    void navigate({ to: ".", replace: true, search: { run: search.run, model: search.model } });
    // The link is consumed once; its name is the identity.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [metricsQuery.data, metricsQuery.isFetching, requestedMetric]);

  // `?model=` narrows the metric picker to that model's metrics until cleared.
  const modelFilter = search.model ? semanticModels.find((model) => model.id === search.model) ?? null : null;
  const appliedModelFilter = useRef<string | null>(null);
  useEffect(() => {
    if (!search.model || appliedModelFilter.current === search.model) return;
    appliedModelFilter.current = search.model;
    setDraft((draftState) => ({ ...draftState, mode: "SEMANTIC" }));
  }, [search.model, setDraft]);
  const visibleMetricNames = useMemo(
    () => (modelFilter ? metricsReadingModel(modelFilter.name, metrics) : metrics).map((metric) => metric.name),
    [metrics, modelFilter],
  );

  // A Catalog table link fills SQL mode with a preview query on that connector. It never starts a run.
  const tableLink = search.connector && search.schema && search.table
    ? { connectorId: search.connector, catalog: search.catalog ?? null, schema: search.schema, table: search.table }
    : null;
  useEffect(() => {
    if (!tableLink || connectorsLoading || connectorsError) return;
    const connector = connectors.find((candidate) => candidate.id === tableLink.connectorId);
    if (connector) {
      setDraft((draftState) => ({
        ...draftState,
        mode: "SQL",
        sql: { connectorId: connector.id, text: tablePreviewSql(connector.type, tableLink) },
      }));
    }
    void navigate({ to: ".", replace: true, search: { run: search.run, model: search.model } });
    // The link is consumed once; its fields are the identity.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [tableLink?.connectorId, tableLink?.catalog, tableLink?.schema, tableLink?.table, connectorsLoading, connectorsError, connectors]);

  const draftRef = useRef(draft);
  draftRef.current = draft;
  // The last question of each kind that ran or was opened, so returning to it is never offered as a loss.
  // A run of one kind leaves the other kind's question alone, so each kind keeps its own.
  const lastRunQuestions = useRef<Record<ExploreRun["kind"], ExploreDraft | null>>({ SQL: null, SEMANTIC: null });
  const [replacedQuestion, setReplacedQuestion] = useState<{ runId: string; kind: ExploreRun["kind"]; draft: ExploreDraft } | null>(null);
  const restoreRunQuestion = useCallback((run: ExploreRun) => {
    const current = draftRef.current;
    const restored = draftFromRun(current, run);
    setReplacedQuestion(replacedQuestionWorthKeeping(current, run, lastRunQuestions.current[run.kind])
      ? { runId: run.runId, kind: run.kind, draft: current }
      : null);
    lastRunQuestions.current = { ...lastRunQuestions.current, [run.kind]: restored };
    setDraft(() => restored);
  }, [setDraft]);

  useEffect(() => {
    if (!activeRun || restoredRunId.current === activeRun.runId) return;
    restoredRunId.current = activeRun.runId;
    restoreRunQuestion(activeRun);
  }, [activeRun, restoreRunQuestion]);

  const editedSinceRun = Boolean(activeRun && restoredRunId.current === activeRun.runId && !draftMatchesRun(draft, activeRun));

  /** `asked` is the draft when Run was pressed; edits made while the start is in flight have not run. */
  const showStartedRun = (started: ExploreRun, asked: ExploreDraft) => {
    queryClient.setQueryData<ExploreRun[]>(
      queryKeys.explore.recentRuns(workspaceId, principalId ?? ""),
      (current) => [started, ...(current ?? []).filter((run) => run.runId !== started.runId)].slice(0, 20),
    );
    // A start view carries no result; seeding it would hide the result of a replayed, finished run.
    if (started.state === "RUNNING") {
      queryClient.setQueryData(queryKeys.explore.run(workspaceId, principalId ?? "", started.runId), started);
    }
    restoredRunId.current = started.runId;
    lastRunQuestions.current = {
      ...lastRunQuestions.current,
      [started.kind]: started.kind === "SQL"
        ? { ...asked, sql: { ...asked.sql, connectorId: started.connectorId } }
        : { ...asked, semantic: { ...asked.semantic, connectorId: started.connectorId } },
    };
    setReplacedQuestion(null);
    setDraftState((current) => started.kind === "SQL"
      ? { ...current, sql: { ...current.sql, connectorId: started.connectorId } }
      : { ...current, semantic: { ...current.semantic, connectorId: started.connectorId } });
    setNewlyAddedRunId(started.runId);
    void navigate({ to: ".", search: { run: started.runId, model: search.model } });
  };

  const startSql = () => {
    if (!sqlConnectorId) return;
    sqlStartMutation.reset();
    const asked = draft;
    void sqlStartMutation.mutateAsync({ connectorId: sqlConnectorId, sql: draft.sql.text.trim() })
      .then((started) => showStartedRun(started, asked), settleRenderedStartRejection);
  };

  const startSemantic = () => {
    if (!semanticConnectorId) return;
    setMetricSwap(null);
    semanticStartMutation.reset();
    const asked = draft;
    void semanticStartMutation.mutateAsync({ connectorId: semanticConnectorId, query: semanticQueryOf(draft.semantic) })
      .then((started) => showStartedRun(started, asked), settleRenderedStartRejection);
  };

  const renderSemanticSql = () => {
    if (!semanticConnectorId) return;
    semanticSqlRenderMutation.reset();
    semanticSqlRenderMutation.mutate({ connectorId: semanticConnectorId, query: semanticQueryOf(draft.semantic) });
  };

  const inspectRun = (selectedRunId: string) => {
    if (selectedRunId === runId && activeRun) {
      restoreRunQuestion(activeRun);
      return;
    }
    void navigate({ to: ".", search: { run: selectedRunId, model: search.model } });
  };

  const startError = draft.mode === "SQL"
    ? toExploreErrorPresentation(sqlStartMutation.error)
    : toExploreErrorPresentation(semanticStartMutation.error);
  const loadedConnectors = connectorsLoading || connectorsError ? null : connectors;
  const guidanceContext = (connectorId: string | null) => ({
    workspaceId,
    connectorId,
    connectors: loadedConnectors,
    metricNames: draft.semantic.metrics,
    metrics,
    models: semanticModels,
    canEditDefinitions: access.hasRole("EDITOR"),
    onPartitionRepair: requestPartitionRepair,
  });
  const startFailure = startError
    ? startRejectionGuidance(startError, draft.mode, guidanceContext(draft.mode === "SQL" ? sqlConnectorId : semanticConnectorId))
    : null;
  const renderError = toExploreErrorPresentation(semanticSqlRenderMutation.error);
  const renderFailure = renderError ? startRejectionGuidance(renderError, "SEMANTIC", guidanceContext(semanticConnectorId)) : null;
  const cancelError = cancelMutation.variables === activeRun?.runId ? toExploreErrorPresentation(cancelMutation.error) : null;
  const unavailable = groupByQuery.data ? unavailableSelections(draft.semantic, groupByQuery.data.groupBys) : NO_UNAVAILABLE_SELECTIONS;
  const semanticReady = Boolean(semanticConnectorId) && draft.semantic.metrics.length > 0
    && unavailable.groupBys.length === 0 && unavailable.filters.length === 0
    && !draft.semantic.filters.some((filter) => filterComparisonMismatch(filter, groupByQuery.data?.dimensionFilterTypes ?? []));
  const replacedQuestionShown = replacedQuestion && activeRun?.runId === replacedQuestion.runId && !editedSinceRun ? replacedQuestion : null;

  return (
    <MainLayout>
      <div className="flex h-full flex-col bg-background">
        <ExploreWorkbench
          workspaceId={workspaceId}
          principalId={principalId}
          mode={draft.mode}
          onModeChange={(mode: ExploreMode) => { setMetricSwap(null); setDraft((draftState) => ({ ...draftState, mode })); }}
          connectors={connectors}
          connectorsLoading={connectorsLoading}
          connectorsError={connectorsError}
          onRetryConnectors={retryConnectors}
          semanticQuestion={{
            workspaceId,
            reportingTimeZone,
            draft: draft.semantic,
            partitionRepair,
            onPartitionRepair: requestPartitionRepair,
            onDraftChange: (semantic) => setDraft((draftState) => ({ ...draftState, semantic })),
            metrics,
            metricsLoading: metricsQuery.isPending,
            metricsError: metricsQuery.error instanceof Error ? metricsQuery.error.message : null,
            modelFilter,
            onClearModelFilter: () => { void navigate({ to: ".", replace: true, search: { run: search.run } }); },
            visibleMetricNames,
            semanticModels,
            semanticModelsLoading: semanticModelsQuery.isPending,
            semanticModelsError: toExploreErrorPresentation(semanticModelsQuery.error),
            onRetrySemanticModels: () => { void semanticModelsQuery.refetch(); },
            groupBys: groupByQuery.data?.groupBys ?? [],
            dimensionFilterTypes: groupByQuery.data?.dimensionFilterTypes ?? [],
            excludedSemanticModels: groupByQuery.data?.excludedSemanticModels ?? [],
            groupBysLoading: groupByQuery.isLoading,
            groupBysError: toExploreErrorPresentation(groupByQuery.error),
            unavailable,
            canEditDefinitions: access.hasRole("EDITOR"),
            onChooseMetric: (metricName) => {
              const connectorIds = connectors.map((candidate) => candidate.id);
              const chosen = withMetricChosen(draft.semantic, metricName, metrics, semanticModels, connectorIds);
              setDraft((draftState) => ({ ...draftState, semantic: { ...draftState.semantic, metrics: chosen.draft.metrics } }));
              if (chosen.removed.length === 0) { setMetricSwap(null); return; }
              setMetricSwap({
                before: draft.semantic,
                after: JSON.stringify(normalizeSemanticDraft(chosen.draft)),
                removed: chosen.removed,
                removedCannotRun: chosen.removed.every((name) => metricRunProblem(name, metrics, semanticModels, connectorIds)),
                chosen: metricName,
              });
              setConnectorSwitches((count) => count + 1);
            },
            metricSwap: metricSwap && draft.mode === "SEMANTIC" && metricSwap.after === JSON.stringify(draft.semantic) ? {
              removed: metricSwap.removed,
              removedCannotRun: metricSwap.removedCannotRun,
              connectorName: connectors.find((candidate) => candidate.id === semanticConnectorId)?.name ?? null,
              chosen: metricSwap.chosen,
              onUndo: () => {
                setDraft((draftState) => ({ ...draftState, semantic: metricSwap.before }));
                setMetricSwap(null);
              },
            } : null,
            connector: {
              all: connectors,
              switchCount: connectorSwitches,
              compatible: connectors.filter((connector) => compatibleIds.includes(connector.id)),
              selectedId: semanticConnectorId,
              onSelect: (connectorId) => setDraft((draftState) => ({ ...draftState, semantic: { ...draftState.semantic, connectorId } })),
              resolving: definitionsResolving,
              problem: connectorResolution.problem,
            },
          }}
          semanticRunDisabled={!semanticReady || semanticStartMutation.isPending}
          semanticStarting={semanticStartMutation.isPending}
          onRunSemantic={startSemantic}
          renderedSql={{
            open: renderedSqlOpen,
            onOpenChange: setRenderedSqlOpen,
            loading: semanticSqlRenderMutation.isPending,
            failure: renderFailure,
            render: semanticSqlRenderMutation.data ?? null,
            connectorName: semanticConnectorId ? connectorName(semanticConnectorId, connectors) : "Connector",
            disabled: !semanticReady || semanticSqlRenderMutation.isPending,
            onRender: renderSemanticSql,
          }}
          sql={draft.sql.text}
          onSqlChange={(text) => setDraft((draftState) => ({ ...draftState, sql: { ...draftState.sql, text } }))}
          sqlConnectorId={sqlConnectorId}
          onSqlConnectorChange={(connectorId) => setDraft((draftState) => ({ ...draftState, sql: { ...draftState.sql, connectorId } }))}
          sqlRunDisabled={!sqlConnectorId || draft.sql.text.trim().length === 0 || sqlStartMutation.isPending}
          sqlStarting={sqlStartMutation.isPending}
          onRunSql={startSql}
          startFailure={startFailure}
          replacedQuestion={replacedQuestionShown ? {
            // Only the replaced kind comes back; the other kind's question keeps any later edits.
            onRestore: () => {
              const saved = replacedQuestionShown.draft;
              setDraft((current) => replacedQuestionShown.kind === "SQL"
                ? { ...current, mode: "SQL", sql: saved.sql }
                : { ...current, mode: "SEMANTIC", semantic: saved.semantic });
              setReplacedQuestion(null);
            },
            onDismiss: () => setReplacedQuestion(null),
          } : null}
          runPanel={{
            workspaceId,
            principalId,
            run: activeRun,
            loading: Boolean(runId) && runQuery.isPending,
            error: toExploreErrorPresentation(runQuery.error),
            editedSinceRun,
            connectors: loadedConnectors,
            metrics,
            semanticModels,
            canEditDefinitions: access.hasRole("EDITOR"),
            onPartitionRepair: requestPartitionRepair,
            onCancel: (cancelRunId) => cancelMutation.mutate(cancelRunId),
            cancelling: cancelMutation.isPending,
            cancelError,
            onOpenInSql: (run) => setDraft((draftState) => ({ ...draftState, mode: "SQL", sql: { connectorId: run.connectorId, text: run.sql } })),
            // Run again and the edited notice's Run ask the viewed run's kind, whichever tab is showing.
            onRunAgain: activeRun?.kind === "SQL" ? startSql : startSemantic,
            runAgainDisabled: activeRun?.kind === "SQL"
              ? !sqlConnectorId || draft.sql.text.trim().length === 0 || sqlStartMutation.isPending
              : !semanticReady || semanticStartMutation.isPending,
            onClose: () => {
              setReplacedQuestion(null);
              void navigate({ to: ".", search: { model: search.model } });
            },
          }}
          runs={recentRunsQuery.data ?? []}
          metrics={metrics}
          semanticModels={semanticModels}
          newlyAddedRunId={newlyAddedRunId}
          selectedRunId={runId}
          onInspectRun={inspectRun}
        />
      </div>
    </MainLayout>
  );
}
