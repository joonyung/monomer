import { DashboardDeletedState } from "@/components/dashboard/DashboardDeletedState";
import { DashboardSchedulesDialog } from "@/components/dashboard/DashboardSchedulesDialog";
import { readDashboardResultWithAccess } from "@/features/dashboard/dashboard-result-access";
import { useEffect, useMemo, useRef, useState } from "react";
import { useMutation, useQueries, useQuery, useQueryClient } from "@tanstack/react-query";
import { useParams } from "@tanstack/react-router";
import {
  dashboardAPI,
  DashboardApiError,
  dashboardIdempotencyKey,
  type DashboardDraftResponse,
  type DashboardJsonObject,
  type DashboardJsonValue,
  type DashboardResult,
} from "@/api/dashboard";
import { DashboardParameterForm } from "@/components/dashboard/DashboardParameterForm";
import { DashboardInputTimeZoneProvider } from "@/components/dashboard/DashboardInputTimeZone";
import { useWorkspaceRouteContext } from "@/components/auth/TenantRouteContext";
import { MainLayout } from "@/components/layout/MainLayout";
import { Button } from "@/components/ui/Button";
import { StateBlock } from "@/components/ui/StateBlock";
import { WorkspacePageHeader } from "@/components/layout/WorkspacePageHeader";
import { queryKeys } from "@/lib/query/keys";
import { usePrincipalId, useWorkspaceId } from "@/hooks/use-workspace";
import { validateDashboardResult, type NamedDashboardResult } from "@/features/dashboard/dashboard-result";
import { DashboardBuilderSurface } from "./DashboardBuilderSurface";
import { readyRunIds } from "./presentation";

function errorMessage(error: unknown, fallback: string): string {
  return error instanceof Error ? error.message : fallback;
}

function isMissingDraft(error: unknown): boolean {
  return error instanceof Error && "status" in error && (error as { status?: number }).status === 404;
}

// Members read time inputs in the Workspace Reporting time zone, the zone Control renders their queries in.
export default function DashboardBuilderPage({ linkedRunId }: { linkedRunId?: string }) {
  const { reportingTimeZone } = useWorkspaceRouteContext().workspace;
  return <DashboardInputTimeZoneProvider timeZone={reportingTimeZone}><DashboardBuilder linkedRunId={linkedRunId} /></DashboardInputTimeZoneProvider>;
}

function DashboardBuilder({ linkedRunId }: { linkedRunId?: string }) {
  const workspaceId = useWorkspaceId();
  const principalId = usePrincipalId();
  const { dashboardId } = useParams({ from: "/workspaces/$workspaceId/dashboards/$dashboardId/builder" });
  const queryClient = useQueryClient();
  const [selectedRunId, setSelectedRunId] = useState<string | null>(linkedRunId ?? null);
  const [newlyAddedRunId, setNewlyAddedRunId] = useState<string | null>(null);
  const [appliedRevision, setAppliedRevision] = useState<number | null>(null);
  const startAttempt = useRef<string | null>(null);
  const previousTargets = useRef<Record<string, string>>({});
  const writeLock = useRef(false);
  const [writePending, setWritePending] = useState(false);
  const [writeError, setWriteError] = useState<string | null>(null);

  const dashboardQuery = useQuery({
    queryKey: queryKeys.dashboards.detail(workspaceId, principalId ?? "", dashboardId),
    queryFn: ({ signal }) => dashboardAPI.get(workspaceId, dashboardId, signal),
    enabled: Boolean(principalId),
    retry: false,
    refetchOnWindowFocus: "always",
  });
  const dashboardDeleted = isMissingDraft(dashboardQuery.error);
  const draftQueryKey = useMemo(
    () => queryKeys.dashboards.draft(workspaceId, principalId ?? "", dashboardId),
    [dashboardId, principalId, workspaceId],
  );
  const draftQuery = useQuery({
    queryKey: draftQueryKey,
    queryFn: async ({ signal }) => {
      const next = await dashboardAPI.getDraft(workspaceId, dashboardId, signal);
      const current = queryClient.getQueryData<DashboardDraftResponse>(draftQueryKey);
      return current && current.authoringRevision > next.authoringRevision ? current : next;
    },
    enabled: Boolean(principalId) && !dashboardDeleted,
    retry: false,
  });
  useEffect(() => {
    if (isMissingDraft(draftQuery.error)) void queryClient.refetchQueries({ queryKey: queryKeys.dashboards.detail(workspaceId, principalId ?? "", dashboardId), exact: true });
  }, [draftQuery.error, queryClient, workspaceId, principalId, dashboardId]);

  const linkedRunQueryKey = useMemo(() => [...draftQueryKey, "linked-run", linkedRunId ?? ""], [draftQueryKey, linkedRunId]);
  const linkedRunQuery = useQuery({
    queryKey: linkedRunQueryKey,
    queryFn: ({ signal }) => dashboardAPI.getDraftQueryRun(workspaceId, dashboardId, linkedRunId!, signal),
    enabled: Boolean(principalId && linkedRunId && draftQuery.data && !draftQuery.isError) && !dashboardDeleted,
    retry: false,
    refetchInterval: query => query.state.data?.state === "RUNNING" ? 2_000 : 15_000,
  });
  const observedDraft = useMemo(() => {
    const draft = draftQuery.data;
    const linkedRun = linkedRunQuery.isError ? undefined : linkedRunQuery.data;
    if (!draft || !linkedRun || linkedRun.queryRunId !== linkedRunId) return draft;
    return { ...draft, recentQueryRuns: [linkedRun, ...draft.recentQueryRuns.filter(run => run.queryRunId !== linkedRunId)] };
  }, [draftQuery.data, linkedRunId, linkedRunQuery.data, linkedRunQuery.isError]);

  useEffect(() => { setSelectedRunId(linkedRunId ?? null); }, [dashboardId, linkedRunId, principalId, workspaceId]);

  const synchronizeDraft = async () => {
    await queryClient.cancelQueries({ queryKey: draftQueryKey, exact: true });
    await queryClient.refetchQueries({ queryKey: draftQueryKey, exact: true, type: "active" });
  };
  const writeDraft = async <T,>(action: () => Promise<T>): Promise<T> => {
    if (writeLock.current) throw new Error("A Draft change is already in progress.");
    writeLock.current = true;
    setWritePending(true);
    setWriteError(null);
    try {
      await queryClient.cancelQueries({ queryKey: draftQueryKey, exact: true });
      const result = await action();
      await synchronizeDraft();
      return result;
    } catch (error) {
      if (isMissingDraft(error)) await queryClient.refetchQueries({ queryKey: queryKeys.dashboards.detail(workspaceId, principalId ?? "", dashboardId), exact: true });
      setWriteError(error instanceof DashboardApiError && error.status === 409
        ? "The shared Draft changed. Review the latest values and try again."
        : errorMessage(error, "The Draft change could not be saved. Try again."));
      if (error instanceof DashboardApiError && (error.status === 401 || error.status === 403)) {
        await queryClient.resetQueries({ queryKey: draftQueryKey, exact: true });
      } else await synchronizeDraft();
      throw error;
    } finally {
      writeLock.current = false;
      setWritePending(false);
    }
  };

  const startMutation = useMutation({
    mutationFn: (parameterBindings: DashboardJsonObject) => writeDraft(() => dashboardAPI.startDraft({
      workspaceId,
      dashboardId,
      parameterBindings,
      idempotencyKey: dashboardIdempotencyKey("draft-start"),
    })),
    onSuccess: () => {
      startAttempt.current = null;
    },
  });
  const refreshMutation = useMutation({
    mutationFn: (queryNames: readonly string[]) => {
      if (!draftQuery.data) throw new Error("Dashboard Draft is required.");
      return writeDraft(() => dashboardAPI.refreshDraft({
        workspaceId,
        dashboardId,
        queryNames: [...queryNames],
        idempotencyKey: dashboardIdempotencyKey("draft-refresh"),
      }));
    },
  });
  const parameterMutation = useMutation({
    mutationFn: ({ name, value }: { name: string; value: DashboardJsonValue }) => {
      const draft = draftQuery.data;
      if (!draft) throw new Error("Dashboard Draft is required.");
      return writeDraft(() => dashboardAPI.setDraftParameter({
        workspaceId,
        dashboardId,
        name,
        expectedRevision: draft.authoringRevision,
        value,
        idempotencyKey: dashboardIdempotencyKey(`draft-parameter-${name}`),
      }));
    },

  });
  const variablesMutation = useMutation({
    mutationFn: ({ variables, expectedRevision }: { variables: DashboardJsonObject; expectedRevision: number }) => writeDraft(() => dashboardAPI.setDraftVariables({
      workspaceId, dashboardId, variables, expectedRevision,
      idempotencyKey: dashboardIdempotencyKey("draft-variables"),
    })),
  });
  const cancelMutation = useMutation({
    mutationFn: (runId: string) => writeDraft(() => dashboardAPI.cancelDraftRun({
      workspaceId,
      dashboardId,
      runId,
      idempotencyKey: dashboardIdempotencyKey("draft-cancel"),
    })),
  });
  const discardMutation = useMutation({
    mutationFn: () => {
      const draft = draftQuery.data;
      if (!draft) throw new Error("Dashboard Draft is required.");
      return writeDraft(() => dashboardAPI.discardDraft({
        workspaceId,
        dashboardId,
        expectedRevision: draft.authoringRevision,
        idempotencyKey: dashboardIdempotencyKey("draft-discard"),
      }));
    },
    onSuccess: () => {
      setAppliedRevision(null);
      void queryClient.invalidateQueries({ queryKey: queryKeys.dashboards.list(workspaceId, principalId ?? "") });
    },
  });
  const applyMutation = useMutation({
    onMutate: () => draftQuery.data?.authoringRevision ?? null,
    mutationFn: () => {
      const draft = draftQuery.data;
      if (!draft) throw new Error("Dashboard Draft is required.");
      return writeDraft(() => dashboardAPI.applyDraft({
        workspaceId,
        dashboardId,
        expectedRevision: draft.authoringRevision,
        idempotencyKey: dashboardIdempotencyKey("draft-apply"),
      }));
    },
    onSuccess: (applied, _variables, revision) => {
      const detailKey = queryKeys.dashboards.detail(workspaceId, principalId ?? "", dashboardId);
      queryClient.setQueryData(detailKey, applied);
      void queryClient.invalidateQueries({ queryKey: detailKey });
      void queryClient.invalidateQueries({ queryKey: queryKeys.dashboards.list(workspaceId, principalId ?? "") });
      setAppliedRevision(revision);
    },
  });

  useEffect(() => {
    const dashboard = dashboardQuery.data;
    if (dashboardDeleted || linkedRunId || !dashboard || draftQuery.isPending || draftQuery.data || !isMissingDraft(draftQuery.error)) return;
    if (Object.keys(dashboard.definition.parameters).length > 0) return;
    const attemptKey = `${workspaceId}:${dashboardId}`;
    if (startAttempt.current === attemptKey || startMutation.isPending) return;
    startAttempt.current = attemptKey;
    startMutation.mutate({});
  }, [dashboardId, dashboardQuery.data, draftQuery.data, draftQuery.error, draftQuery.isPending, linkedRunId, startMutation, workspaceId, dashboardDeleted]);

  useEffect(() => {
    const draft = draftQuery.data;
    if (!draft) return;
    const nextTargets = draft.targetQueryRuns;
    const changedTarget = Object.entries(nextTargets).find(([queryName, runId]) => previousTargets.current[queryName] !== undefined && previousTargets.current[queryName] !== runId);
    if (changedTarget) setNewlyAddedRunId(changedTarget[1]);
    previousTargets.current = { ...nextTargets };
  }, [draftQuery.data]);

  const hasDraft = Boolean(draftQuery.data);
  useEffect(() => {
    if (!hasDraft || !principalId || dashboardDeleted) return;
    const controller = new AbortController();
    let stopped = false;
    let reconnectTimer: number | null = null;
    let reconnectAttempt = 0;
    const connect = async () => {
      try {
        await dashboardAPI.watchDraft({
          workspaceId,
          dashboardId,
          signal: controller.signal,
          onEvent: () => {
            void queryClient.invalidateQueries({ queryKey: draftQueryKey });
          },
        });
      } catch {
        if (controller.signal.aborted) return;
      }
      if (stopped || controller.signal.aborted) return;
      await queryClient.refetchQueries({ queryKey: queryKeys.dashboards.detail(workspaceId, principalId, dashboardId), exact: true });
      if (stopped || controller.signal.aborted || isMissingDraft(queryClient.getQueryState(queryKeys.dashboards.detail(workspaceId, principalId, dashboardId))?.error)) return;
      await queryClient.refetchQueries({ queryKey: draftQueryKey, type: "active" });
      reconnectAttempt = Math.min(reconnectAttempt + 1, 3);
      reconnectTimer = window.setTimeout(() => void connect(), 500 * (2 ** reconnectAttempt));
    };
    void connect();
    return () => {
      stopped = true;
      controller.abort();
      if (reconnectTimer !== null) window.clearTimeout(reconnectTimer);
    };
  }, [dashboardId, hasDraft, draftQueryKey, principalId, queryClient, workspaceId, dashboardDeleted]);

  const resultIds = useMemo(() => {
    if (!observedDraft) return selectedRunId ? [selectedRunId] : [];
    return [...new Set([...readyRunIds(observedDraft), ...(selectedRunId ? [selectedRunId] : [])])];
  }, [observedDraft, selectedRunId]);
  const readyResultIdSet = useMemo(
    () => new Set(observedDraft ? readyRunIds(observedDraft) : []),
    [observedDraft],
  );
  const resultQueries = useQueries({
    queries: resultIds.map((runId) => ({
      queryKey: queryKeys.dashboards.draftResult(workspaceId, principalId ?? "", dashboardId, runId),
      queryFn: ({ signal }: { signal: AbortSignal }) => readDashboardResultWithAccess(() => dashboardAPI.getDraftResult({ workspaceId, dashboardId, runId, signal })),
      enabled: Boolean(
        principalId
        && observedDraft
        && !dashboardDeleted
        && (readyResultIdSet.has(runId) || observedDraft.recentQueryRuns.some((run) => run.queryRunId === runId && run.state === "SUCCEEDED" && run.resultAvailable)),
      ),
      staleTime: 0,
      refetchInterval: 15_000,
      retry: false,
    })),
  });
  const deniedRunIds = new Set(resultIds.filter((_, index) => {
    const error = resultQueries[index]?.error;
    return resultQueries[index]?.data === null || error instanceof DashboardApiError && (error.status === 401 || error.status === 403);
  }));
  const resultSignature = resultIds.map((runId, index) => `${runId}:${resultQueries[index]?.status ?? "pending"}:${resultQueries[index]?.data?.queryRunId ?? ""}`).join("|");
  const resultsByRunId = useMemo<Readonly<Record<string, NamedDashboardResult>>>(() => {
    const results: Record<string, NamedDashboardResult> = {};
    resultIds.forEach((runId, index) => {
      const result = resultQueries[index]?.data as DashboardResult | undefined;
      if (!result || resultQueries[index]?.isSuccess !== true) return;
      try {
        results[runId] = validateDashboardResult(result);
      } catch {
        // A malformed immutable artifact is unavailable to this preview.
      }
    });
    return results;
    // Result artifacts are immutable; status and result identity are the only changes that matter here.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [resultSignature]);

  if (dashboardDeleted) return <BuilderShell title="Draft review"><DashboardDeletedState workspaceId={workspaceId} dashboardId={dashboardId} canReadHistory /></BuilderShell>;
  if (dashboardQuery.isPending) return <BuilderState title="Draft review" message="Reading the current Dashboard…" />;
  if (dashboardQuery.isError || !dashboardQuery.data) {
    return <BuilderState title="Draft review" message={errorMessage(dashboardQuery.error, "The Dashboard could not be loaded.")} error />;
  }

  const dashboard = dashboardQuery.data;
  const draftMissing = draftQuery.isError && isMissingDraft(draftQuery.error);
  if (draftQuery.isPending) return <BuilderState title={`${dashboard.definition.meta.title}${linkedRunId ? " · Draft run" : ""}`} message="Reading the shared Draft…" />;
  if (linkedRunId && (draftQuery.isError || !draftQuery.data)) {
    return <BuilderState title={`${dashboard.definition.meta.title}${linkedRunId ? " · Draft run" : ""}`} message="The linked run is unavailable because the shared Draft could not be read." error onRetry={() => void draftQuery.refetch()} />;
  }
  if (linkedRunId && linkedRunQuery.isPending) return <BuilderState title={`${dashboard.definition.meta.title}${linkedRunId ? " · Draft run" : ""}`} message="Reading the linked Draft run…" />;
  if (linkedRunId && (linkedRunQuery.isError || linkedRunQuery.data?.queryRunId !== linkedRunId)) {
    return <BuilderState title={`${dashboard.definition.meta.title}${linkedRunId ? " · Draft run" : ""}`} message="The linked run is unavailable in the current Draft." error onRetry={() => void linkedRunQuery.refetch()} />;
  }
  if (draftMissing && Object.keys(dashboard.definition.parameters).length > 0) {
    return (
      <BuilderShell title={`${dashboard.definition.meta.title}${linkedRunId ? " · Draft run" : ""}`}>
        <DashboardParameterForm
          parameters={dashboard.definition.parameters}
          pending={startMutation.isPending}
          error={startMutation.error ? errorMessage(startMutation.error, "The Draft could not be started.") : null}
          onOpen={(parameterBindings) => startMutation.mutate(parameterBindings)}
        />
      </BuilderShell>
    );
  }
  if (draftQuery.isError || !draftQuery.data) {
    return <BuilderState title={`${dashboard.definition.meta.title}${linkedRunId ? " · Draft run" : ""}`} message={startMutation.isPending ? "Starting the shared Draft…" : errorMessage(draftQuery.error ?? startMutation.error, "The shared Draft could not be loaded.")} error={!startMutation.isPending && Boolean((draftQuery.error && !draftMissing) || startMutation.error)} onRetry={!startMutation.isPending && startMutation.error ? () => startMutation.mutate({}) : undefined} />;
  }

  return (
    <MainLayout>
      <DashboardBuilderSurface
        key={`${workspaceId}:${principalId}:${dashboardId}`}
        observationOnly={Boolean(linkedRunId)}
        scheduleControl={<DashboardSchedulesDialog dashboardId={dashboardId} draftDefinition={observedDraft!.definition} />}
        draft={observedDraft!}
        principalId={principalId ?? null}
        appliedDefinition={dashboard.definition}
        appliedRevision={appliedRevision}
        resultsByRunId={resultsByRunId}
        deniedRunIds={deniedRunIds}
        selectedRunId={selectedRunId}
        onSelectedRunChange={setSelectedRunId}
        newlyAddedRunId={newlyAddedRunId}
        writePending={writePending}
        onVariablesSet={(variables, expectedRevision) => { if (!writeLock.current && !linkedRunId) variablesMutation.mutate({ variables, expectedRevision }); }}
        refreshPending={refreshMutation.isPending}
        applyPending={applyMutation.isPending}
        discardPending={discardMutation.isPending}
        parameterPendingName={parameterMutation.isPending ? parameterMutation.variables?.name ?? null : null}
        cancellingRunId={cancelMutation.isPending ? cancelMutation.variables : null}
        errorMessage={writeError}
        onRefresh={(queryNames) => { if (!writeLock.current && !linkedRunId) refreshMutation.mutate(queryNames); }}
        onApply={() => { if (!writeLock.current && !linkedRunId) applyMutation.mutate(); }}
        onDiscard={() => { if (!writeLock.current && !linkedRunId) discardMutation.mutate(); }}
        onParameterSet={(name, value) => { if (!writeLock.current && !linkedRunId) parameterMutation.mutate({ name, value }); }}
        onCancel={(runId) => { if (!writeLock.current && !linkedRunId) cancelMutation.mutate(runId); }}
      />
    </MainLayout>
  );
}

function BuilderShell({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <MainLayout>
      <div className="flex h-full min-h-0 flex-col bg-background" style={{ fontFeatureSettings: '"ss01"' }}>
        <WorkspacePageHeader section="Draft review" title={title} />
        <main className="min-h-0 flex-1 overflow-y-auto px-5 py-6 sm:px-8">{children}</main>
      </div>
    </MainLayout>
  );
}

function BuilderState({ title, message, error = false, onRetry }: { title: string; message: string; error?: boolean; onRetry?: () => void }) {
  return (
    <BuilderShell title={title}>
      <StateBlock
        kind={error ? "error" : "loading"}
        layout="page"
        title={message}
        action={onRetry && <Button type="button" variant="secondary" onClick={onRetry}>Retry</Button>}
      />
    </BuilderShell>
  );
}
