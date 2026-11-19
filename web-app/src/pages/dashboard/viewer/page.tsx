import { isLoadErrorRetryable, loadErrorMessage } from "@/lib/api/load-error";
import { Link } from "@/lib/navigation";
import { webPaths } from "@/lib/web-paths";
import { immutableResultOptions, runPollInterval } from "@/features/dashboard/viewer-query-policy";
import { useEffect, useMemo, useRef, useState } from "react";
import { useInfiniteQuery, useMutation, useQueries, useQuery, useQueryClient } from "@tanstack/react-query";
import { useNavigate, useParams, useSearch } from "@tanstack/react-router";
import { memberSessionEntry } from "@/api/public-pages";
import { CalendarClock, Ellipsis, FilePenLine, History, RotateCcw, Trash2 } from "lucide-react";
import { DashboardApiError, dashboardAPI, dashboardIdempotencyKey, type DashboardJsonObject, type DashboardQueryRun, type DashboardResult, type DashboardSessionResponse } from "@/api/dashboard";
import { DashboardDeleteDialog } from "@/components/dashboard/DashboardDeleteDialog";
import { DashboardDeletedState } from "@/components/dashboard/DashboardDeletedState";
import { useWorkspaceAccess } from "@/components/auth/WorkspaceAccessProvider";
import { useWorkspaceRouteContext } from "@/components/auth/TenantRouteContext";
import { refreshDashboardDefinitionCaches } from "@/lib/query/dashboard-definition-cache";
import { DashboardSchedulesDialog } from "@/components/dashboard/DashboardSchedulesDialog";
import { readDashboardResultWithAccess } from "@/features/dashboard/dashboard-result-access";
import { DashboardLayout } from "@/components/dashboard/DashboardLayout";
import type { DashboardWidgetActions, DashboardWidgetMetricsTarget } from "@/components/dashboard/DashboardWidgetRenderer";
import { WidgetMetricsDialog } from "@/components/dashboard/WidgetMetricsDialog";
import { DashboardParameterForm, dashboardInputChips } from "@/components/dashboard/DashboardParameterForm";
import { DashboardActivitySidebar, DashboardQueryRunDetail } from "@/components/dashboard/DashboardActivitySidebar";
import { ActorProvenance } from "@/components/ui/ActorProvenance";
import { Button, buttonClassName } from "@/components/ui/Button";
import { sessionCreatedLabel, sessionExactTime } from "@/components/dashboard/session-time";
import { Dialog } from "@/components/ui/Dialog";
import { IconButton } from "@/components/ui/IconButton";
import { Menu, MenuContent, MenuItem, MenuTrigger } from "@/components/ui/Menu";
import { StateBlock } from "@/components/ui/StateBlock";
import { RunsStatusDot } from "@/components/runs";
import { workStatus, WorkStatus } from "@/components/ui/WorkStatus";
import { MainLayout } from "@/components/layout/MainLayout";
import { PageViewerFrame, PageViewerActions, PageViewerContext } from "@/components/pages/PageViewerFrame";
import { PageShareActions } from "@/components/pages/PageShareActions";
import { dashboardInputs, displayedResultsTimeZoneLabel, latestDisplayedResultAt } from "@/components/dashboard/capture/provenance";
import { DashboardInputTimeZoneProvider } from "@/components/dashboard/DashboardInputTimeZone";
import { usePrincipalId, useWorkspaceId } from "@/hooks/use-workspace";
import { queryKeys } from "@/lib/query/keys";
import { validateDashboardResult, type NamedDashboardResult } from "@/features/dashboard/dashboard-result";
import { dashboardQueryTypes } from "@/features/dashboard/widget-metrics";
import { ChangeInputsDialog } from "./ChangeInputsDialog";
import { DashboardInputsBar } from "./InputsBar";
import { dashboardResultsStatus, scheduledRunTime } from "./inputs";
import { compatibleParameterValues, dashboardSnapshotsEqual } from "./definition-entry";

import { acceptsChartInput, acceptScreenRequest, adoptWidgetBundles, beginScreenRequest, displayedWidgets, initialScreen, rejectScreenRequest, widgetResultAccessErrors, type DashboardScreenState, type DashboardWidgetBundle } from "./screen-state";

function sessionErrorMessage(error: unknown, fallback: string): string {
  return loadErrorMessage(error, fallback);
}

// A stable select lets TanStack Query validate each immutable result once per fetched value.
function selectDashboardResult(result: DashboardResult | null): NamedDashboardResult | null {
  return result === null ? null : validateDashboardResult(result);
}

export default function DashboardViewerPage() {
  const { dashboardId } = useParams({ from: "/workspaces/$workspaceId/dashboards/$dashboardId" });
  const search = useSearch({ from: "/workspaces/$workspaceId/dashboards/$dashboardId/" });
  const navigate = useNavigate({ from: "/workspaces/$workspaceId/dashboards/$dashboardId/" });
  return <MainLayout><DashboardViewer dashboardId={dashboardId} search={search} onSearchChange={(next) => { void navigate({ to: ".", replace: true, search: next }); }} /></MainLayout>;
}

export interface DashboardViewerSearch {
  scheduleKey?: string;
  scheduledAt?: string;
  session?: string;
  run?: string;
}

interface DashboardViewerProps {
  pagePresentation?: { title: string; description?: string | null; actions?: React.ReactNode; source: string | null };
  pagesEntry?: boolean;
  dashboardId: string;
  search: DashboardViewerSearch;
  onSearchChange: (search: DashboardViewerSearch) => void;
}

// Members read time inputs in the Workspace Reporting time zone, the zone Control renders their queries in.
export function DashboardViewer(props: DashboardViewerProps) {
  const { reportingTimeZone } = useWorkspaceRouteContext().workspace;
  return <DashboardInputTimeZoneProvider timeZone={reportingTimeZone}><DashboardViewerContent {...props} /></DashboardInputTimeZoneProvider>;
}

function DashboardViewerContent({ dashboardId, search, onSearchChange, pagesEntry = false, pagePresentation }: DashboardViewerProps) {
  const workspaceId = useWorkspaceId();
  const principalId = usePrincipalId();
  const queryClient = useQueryClient();
  const access = useWorkspaceAccess();
  const { workspace } = useWorkspaceRouteContext();
  const navigate = useNavigate();
  const [deleteOpen, setDeleteOpen] = useState(false);
  const occurrenceMode = search.scheduleKey !== undefined || search.scheduledAt !== undefined;
  const occurrenceQuery = useQuery({
    queryKey: queryKeys.dashboards.occurrence(workspaceId, principalId ?? "", dashboardId, search.scheduleKey ?? "", search.scheduledAt ?? ""),
    queryFn: ({ signal }) => dashboardAPI.getOccurrence(workspaceId, dashboardId, search.scheduleKey!, search.scheduledAt!, signal),
    enabled: Boolean(principalId && search.scheduleKey && search.scheduledAt),
    refetchInterval: 15_000,
    retry: false,
  });
  const occurrence = occurrenceQuery.isError ? undefined : occurrenceQuery.data;
  const sessionId = occurrenceMode ? occurrence?.sessionId ?? null : search.session ?? null;
  const [entryVisit] = useState(() => crypto.randomUUID());
  const entryQuery = useQuery({
    queryKey: ["member-pages-entry", workspaceId, principalId, dashboardId, entryVisit, search.session ?? "default"],
    queryFn: ({ signal }) => memberSessionEntry(workspaceId, dashboardId, search.session, signal),
    enabled: pagesEntry && !occurrenceMode && Boolean(principalId),
    retry: false, staleTime: Infinity, gcTime: 0, refetchOnWindowFocus: false,
  });
  const [adoptedEntry, setAdoptedEntry] = useState<unknown>(null);
  useEffect(() => {
    if (adoptedEntry === entryQuery.data) return;
    if (!pagesEntry || occurrenceMode) return;
    const selected = entryQuery.data?.session;
    if (!selected || selected.dashboardId !== dashboardId || !principalId) return;
    for (const run of Object.values(entryQuery.data!.initialQueryRuns)) {
      queryClient.setQueryData(queryKeys.dashboards.sessionRun(workspaceId, principalId, selected.dashboardSessionId, run.queryRunId), run);
    }
    setAdoptedEntry(entryQuery.data);
    queryClient.setQueryData(queryKeys.dashboards.session(workspaceId, principalId ?? "", selected.dashboardSessionId), selected);
    if (!search.session) {
      queryClient.setQueryData(["member-pages-entry", workspaceId, principalId, dashboardId, entryVisit, selected.dashboardSessionId], entryQuery.data);
      onSearchChange({ session: selected.dashboardSessionId });
    }
  }, [adoptedEntry, dashboardId, entryQuery.data, entryVisit, occurrenceMode, onSearchChange, pagesEntry, principalId, queryClient, search.session, workspaceId]);
  const chartCanvasRef = useRef<HTMLDivElement>(null);
  const [openError, setOpenError] = useState<string | null>(null);
  const autoOpenAttempt = useRef<string | null>(null);
  const latestRestoreAttempt = useRef<string | null>(null);
  const applyEntrySession = useRef<DashboardSessionResponse | undefined>(undefined);
  const [entryNotice, setEntryNotice] = useState<{ sessionId: string; kind: "updated" | "inputs" } | null>(null);
  const [changeInputsOpen, setChangeInputsOpen] = useState(false);
  const [widgetRunNames, setWidgetRunNames] = useState<readonly string[] | null>(null);
  const [widgetMetricsTarget, setWidgetMetricsTarget] = useState<DashboardWidgetMetricsTarget | null>(null);
  const [runsCollapsed, setRunsCollapsed] = useState(true);
  const [expandedGroups, setExpandedGroups] = useState<Record<string, readonly string[]>>({});
  const [activityView, setActivityView] = useState<"sessions" | "queries">(pagesEntry || search.run ? "queries" : "sessions");
  const [activityMobileOpen, setActivityMobileOpen] = useState(false);

  const [displayedResults, setDisplayedResults] = useState<Record<string, NamedDashboardResult>>({});
  const [resultAccessErrors, setResultAccessErrors] = useState<Record<string, string>>({});
  const [adoptedBundles, setAdoptedBundles] = useState<Record<string, DashboardWidgetBundle>>({});
  const identity = `${workspaceId}:${principalId}:${dashboardId}:${sessionId}:${search.scheduleKey ?? ""}:${search.scheduledAt ?? ""}`;
  const screenRef = useRef<{ identity: string; state: DashboardScreenState; instance: object } | null>(null);
  const [screen, setScreen] = useState<DashboardScreenState>(() => initialScreen({}));
  const initialized = useRef<string | null>(null);
  const mounted = useRef(false);
  const screenIdentity = useRef<string | null>(null);
  const renderedScreenIdentity = screenRef.current?.identity;
  function updateScreen(state: DashboardScreenState) {
    screenRef.current = { identity, state, instance: screenRef.current?.identity === identity ? screenRef.current.instance : {} };
    setScreen(state);
  }
  async function submitVariables(changes: DashboardJsonObject, refreshQueryNames?: readonly string[]) {
    const current = screenRef.current;
    if (occurrenceMode || !current || current.identity !== identity || !sessionId) return;
    const next = beginScreenRequest(current.state, changes);
    updateScreen(next);
    const request = { workspaceId, sessionId, variables: next.candidate, idempotencyKey: dashboardIdempotencyKey(refreshQueryNames ? "refresh" : "resolve") };
    try {
      const response = refreshQueryNames
        ? await dashboardAPI.refreshSession({ ...request, queryNames: [...refreshQueryNames] })
        : await dashboardAPI.resolveSession(request);
      if (!mounted.current || screenRef.current?.instance !== current.instance) return;
      updateScreen(acceptScreenRequest(screenRef.current.state, next.latestRequest, response));
      void queryClient.invalidateQueries({ queryKey: queryKeys.dashboards.sessions(workspaceId, principalId ?? "", dashboardId, null, 20) });
      void queryClient.invalidateQueries({ queryKey: queryKeys.dashboards.session(workspaceId, principalId ?? "", sessionId) });
      void queryClient.invalidateQueries({ queryKey: queryKeys.dashboards.sessionQueryRuns(workspaceId, principalId ?? "", sessionId, null, 50) });
    } catch (error) {
      if (!mounted.current || screenRef.current?.instance !== current.instance) return;
      if (error instanceof DashboardApiError && (error.status === 401 || error.status === 403)) {
        void queryClient.invalidateQueries({ queryKey: queryKeys.dashboards.session(workspaceId, principalId ?? "", sessionId) });
      }
      if (error instanceof DashboardApiError && error.status === 404) void queryClient.refetchQueries({ queryKey: queryKeys.dashboards.detail(workspaceId, principalId ?? "", dashboardId), exact: true });
      updateScreen(rejectScreenRequest(screenRef.current.state, next.latestRequest, sessionErrorMessage(error, "The inputs could not be applied. Previous inputs and results are shown.")));
    }
  }

  const dashboardQuery = useQuery({
    queryKey: queryKeys.dashboards.detail(workspaceId, principalId ?? "", dashboardId),
    queryFn: ({ signal }) => dashboardAPI.get(workspaceId, dashboardId, signal),
    enabled: Boolean(principalId && dashboardId),
    retry: false,
    refetchOnWindowFocus: "always",
    refetchOnMount: !pagesEntry && !occurrenceMode ? "always" : true,
  });
  const dashboardDeleted = dashboardQuery.error instanceof DashboardApiError && dashboardQuery.error.status === 404;
  const sessionsQuery = useInfiniteQuery({
    queryKey: queryKeys.dashboards.sessions(workspaceId, principalId ?? "", dashboardId, null, 20),
    queryFn: ({ pageParam, signal }) => dashboardAPI.listSessions(workspaceId, dashboardId, { cursor: pageParam ?? undefined, pageSize: 20, signal }),
    initialPageParam: null as string | null,
    getNextPageParam: (lastPage) => lastPage.nextCursor ?? undefined,
    enabled: Boolean(principalId && dashboardId) && !dashboardDeleted && (!pagesEntry || (!sessionId && entryQuery.isSuccess && !entryQuery.data?.session) || (activityMobileOpen && activityView === "sessions")),
    staleTime: 0,
    refetchInterval: 15_000,
    retry: false,
  });
  const sessions = useMemo(
    () => sessionsQuery.data?.pages.flatMap((page) => page.items) ?? [],
    [sessionsQuery.data],
  );
  const sessionQuery = useQuery({
    queryKey: queryKeys.dashboards.session(workspaceId, principalId ?? "", sessionId ?? ""),
    queryFn: ({ signal }) => dashboardAPI.getSession(workspaceId, sessionId as string, signal),
    enabled: Boolean(principalId && sessionId) && !dashboardDeleted && (!pagesEntry || occurrenceMode || (entryQuery.isSuccess && adoptedEntry === entryQuery.data)),
    staleTime: pagesEntry ? 15_000 : 0,
    refetchInterval: 15_000,
    retry: false,
  });
  useEffect(() => {
    if ([sessionQuery.error, occurrenceQuery.error, sessionsQuery.error].some(error => error instanceof DashboardApiError && error.status === 404)) {
      void queryClient.refetchQueries({ queryKey: queryKeys.dashboards.detail(workspaceId, principalId ?? "", dashboardId), exact: true });
    }
  }, [sessionQuery.error, occurrenceQuery.error, sessionsQuery.error, queryClient, workspaceId, principalId, dashboardId]);
  const sessionMatchesDashboard = sessionQuery.data?.dashboardId === dashboardId && !sessionQuery.isError;
  const internalViewer = !pagesEntry && !occurrenceMode;
  // Draft review is offered only when the list reports unapplied Draft changes.
  const dashboardListQuery = useQuery({
    queryKey: queryKeys.dashboards.list(workspaceId, principalId ?? ""),
    queryFn: ({ signal }) => dashboardAPI.list(workspaceId, signal),
    enabled: internalViewer && Boolean(principalId) && !dashboardDeleted,
    retry: false,
  });
  const draftHasChanges = dashboardListQuery.data?.find((item) => item.dashboardId === dashboardId)?.draftHasChanges === true;
  const definition = dashboardQuery.data?.definition ?? (sessionMatchesDashboard ? sessionQuery.data?.definition : undefined);
  const subscribedSessionId = sessionMatchesDashboard ? sessionQuery.data?.dashboardSessionId : undefined;
  const latestSessionId = sessions[0]?.dashboardSessionId;
  const latestSessionQuery = useQuery({
    queryKey: queryKeys.dashboards.session(workspaceId, principalId ?? "", latestSessionId ?? ""),
    queryFn: ({ signal }) => dashboardAPI.getSession(workspaceId, latestSessionId!, signal),
    enabled: internalViewer && !sessionId && !search.run && Boolean(principalId && latestSessionId) && sessionsQuery.isSuccess && sessionsQuery.isFetchedAfterMount && !dashboardDeleted,
    staleTime: 0,
    retry: false,
  });
  const openMutation = useMutation({
    onMutate: () => ({ dashboardId, previous: applyEntrySession.current }),
    mutationFn: (parameterBindings: DashboardJsonObject) => dashboardAPI.openSession({
      workspaceId,
      dashboardId,
      parameterBindings,
      idempotencyKey: dashboardIdempotencyKey("open"),
    }),
    onSuccess: (response, _bindings, request) => {
      if (!mounted.current || request?.dashboardId !== dashboardId) return;
      setOpenError(null);
      setChangeInputsOpen(false);
      if (request.previous) setEntryNotice({ sessionId: response.dashboardSessionId, kind: "updated" });
      applyEntrySession.current = undefined;
      void queryClient.invalidateQueries({ queryKey: queryKeys.dashboards.sessions(workspaceId, principalId ?? "", dashboardId, null, 20) });
      onSearchChange({ session: response.dashboardSessionId });
    },
    onError: (error, _bindings, request) => {
      if (!mounted.current || request?.dashboardId !== dashboardId) return;
      const previous = request.previous;
      if (previous && error instanceof DashboardApiError && error.problem?.code === "INVALID_SESSION_PARAMETERS") {
        applyEntrySession.current = undefined;
        setOpenError(null);
        setEntryNotice({ sessionId: previous.dashboardSessionId, kind: "inputs" });
        setChangeInputsOpen(true);
        onSearchChange({ session: previous.dashboardSessionId });
        return;
      }
      setOpenError(sessionErrorMessage(error, "The results could not be opened."));
      if (error instanceof DashboardApiError && error.status === 404) void queryClient.refetchQueries({ queryKey: queryKeys.dashboards.detail(workspaceId, principalId ?? "", dashboardId), exact: true });
    },
  });

  // A cached History list can predate results opened elsewhere; only a list read after this mount
  // chooses the latest results or decides that none exist.
  const freshSessions = sessionsQuery.isSuccess && sessionsQuery.isFetchedAfterMount;
  useEffect(() => {
    if (dashboardDeleted || pagesEntry || occurrenceMode || !definition || sessionId || search.run || Object.keys(definition.parameters).length > 0 || !freshSessions || sessions.length > 0) return;
    const attemptKey = `${workspaceId}:${dashboardId}`;
    if (autoOpenAttempt.current === attemptKey) return;
    autoOpenAttempt.current = attemptKey;
    openMutation.mutate({});
  }, [dashboardId, definition, openMutation, sessionId, sessions.length, freshSessions, search.run, workspaceId, occurrenceMode, pagesEntry, entryQuery.isSuccess, dashboardDeleted]);

  useEffect(() => {
    // A selected Session ends the current bare entry, so the next bare entry restores again.
    if (sessionId) latestRestoreAttempt.current = null;
    if (pagesEntry || occurrenceMode || sessionId || search.run || !freshSessions || !dashboardQuery.isFetchedAfterMount || dashboardQuery.isError || !dashboardQuery.data || !latestSessionQuery.isFetchedAfterMount || !latestSessionQuery.isSuccess) return;
    const latest = latestSessionQuery.data;
    if (latest.dashboardId !== dashboardId) return;
    const restoreKey = `${workspaceId}:${principalId}:${dashboardId}`;
    if (latestRestoreAttempt.current === restoreKey) return;
    latestRestoreAttempt.current = restoreKey;
    setEntryNotice(null);
    setOpenError(null);
    applyEntrySession.current = undefined;
    if (access.hasRole("EXPLORER") && !dashboardSnapshotsEqual(latest.definition, dashboardQuery.data.definition)) {
      applyEntrySession.current = latest;
      openMutation.mutate(latest.parameterBindings);
    } else onSearchChange({ session: latest.dashboardSessionId });
  }, [access, dashboardId, dashboardQuery.data, dashboardQuery.isError, dashboardQuery.isFetchedAfterMount, freshSessions, latestSessionQuery.data, latestSessionQuery.isFetchedAfterMount, latestSessionQuery.isSuccess, occurrenceMode, onSearchChange, openMutation, pagesEntry, principalId, search.run, sessionId, workspaceId]);

  useEffect(() => {
    mounted.current = true;
    if (screenIdentity.current !== identity) {
      screenIdentity.current = identity;
      screenRef.current = null;
      initialized.current = null;
      setScreen(initialScreen({}));
      setDisplayedResults({});
      setAdoptedBundles({});
      setResultAccessErrors({});
    }
    return () => { mounted.current = false; };
  }, [identity]);

  useEffect(() => {
    const session = sessionQuery.data;
    if (!session || session.dashboardId !== dashboardId || initialized.current === identity) return;
    if (pagesEntry && !occurrenceMode && !entryQuery.isSuccess) return;
    if (occurrenceMode && (!occurrence || occurrence.sessionId !== session.dashboardSessionId)) return;
    initialized.current = identity;
    updateScreen({ ...initialScreen(session.initialVariables), queryRuns: occurrenceMode ? occurrence!.initialQueryRuns : pagesEntry ? Object.fromEntries(Object.entries(entryQuery.data?.initialQueryRuns ?? {}).map(([name, run]) => [name, run.queryRunId])) : {} });
    const required = Object.keys(session.definition.queries);
    if (!occurrenceMode && (!pagesEntry || !required.every(name => entryQuery.data?.initialQueryRuns[name]))) void submitVariables({});
  // Revalidation must never resolve or replace this mounted screen's selection.
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [identity, sessionQuery.data, dashboardId, occurrence, occurrenceMode, pagesEntry, entryQuery.data, entryQuery.isSuccess]);

  useEffect(() => {
    if (!subscribedSessionId || dashboardDeleted) return;
    const controller = new AbortController();
    let reconnectTimer: number | undefined;
    let reconnectAttempt = 0;
    const invalidate = () => {
      void queryClient.invalidateQueries({ queryKey: queryKeys.dashboards.sessions(workspaceId, principalId ?? "", dashboardId, null, 20) });
      void queryClient.invalidateQueries({ queryKey: queryKeys.dashboards.session(workspaceId, principalId ?? "", subscribedSessionId) });
      void queryClient.invalidateQueries({ queryKey: queryKeys.dashboards.sessionQueryRuns(workspaceId, principalId ?? "", subscribedSessionId, null, 50) });
      void queryClient.invalidateQueries({ queryKey: queryKeys.dashboards.sessionRun(workspaceId, principalId ?? "", subscribedSessionId), predicate: query => runPollInterval(query as { state: { data?: { state: string } } }) !== false });
      void queryClient.invalidateQueries({ queryKey: queryKeys.dashboards.result(workspaceId, principalId ?? "", subscribedSessionId), predicate: query => query.state.data === undefined });
    };
    const connect = async () => {
      try {
        await dashboardAPI.watchSession({ workspaceId, sessionId: subscribedSessionId, signal: controller.signal, onEvent: invalidate });
      } catch {
        if (controller.signal.aborted) return;
      }
      if (controller.signal.aborted) return;
      await queryClient.refetchQueries({ queryKey: queryKeys.dashboards.detail(workspaceId, principalId ?? "", dashboardId), exact: true });
      const currentError = queryClient.getQueryState(queryKeys.dashboards.detail(workspaceId, principalId ?? "", dashboardId))?.error;
      if (controller.signal.aborted || currentError instanceof DashboardApiError && currentError.status === 404) return;
      invalidate();
      reconnectAttempt = Math.min(reconnectAttempt + 1, 3);
      reconnectTimer = window.setTimeout(() => void connect(), 500 * (2 ** reconnectAttempt));
    };
    void connect();
    return () => { controller.abort(); window.clearTimeout(reconnectTimer); };
  }, [dashboardId, principalId, queryClient, subscribedSessionId, workspaceId, dashboardDeleted]);

  const session = sessionQuery.data;
  const sessionQueryRunsQuery = useInfiniteQuery({
    queryKey: queryKeys.dashboards.sessionQueryRuns(workspaceId, principalId ?? "", sessionId ?? "", null, 50),
    queryFn: ({ pageParam, signal }) => dashboardAPI.listSessionQueryRuns(workspaceId, sessionId as string, {
      cursor: pageParam ?? undefined,
      pageSize: 50,
      signal,
    }),
    initialPageParam: null as string | null,
    getNextPageParam: (lastPage) => lastPage.nextCursor ?? undefined,
    enabled: Boolean(principalId && sessionId) && !dashboardDeleted && (!pagesEntry || (activityMobileOpen && activityView === "queries")),
    staleTime: 0,
    refetchInterval: 15_000,
    retry: false,
  });
  const sessionQueryRuns = useMemo(
    () => sessionQueryRunsQuery.data?.pages.flatMap((page) => page.items) ?? [],
    [sessionQueryRunsQuery.data],
  );
  const linkedRunQuery = useQuery({
    queryKey: queryKeys.dashboards.sessionRun(workspaceId, principalId ?? "", sessionId ?? "", search.run ?? ""),
    queryFn: ({ signal }) => dashboardAPI.getSessionQueryRun(workspaceId, sessionId!, search.run!, signal),
    enabled: Boolean(principalId && sessionId && search.run && sessionMatchesDashboard) && !dashboardDeleted,
    retry: false,
    gcTime: 0,
    refetchInterval: query => query.state.data?.state === "RUNNING" ? 2_000 : false,
  });
  const linkedRun = !linkedRunQuery.isError && linkedRunQuery.data?.queryRunId === search.run ? linkedRunQuery.data : undefined;
  const activityQueryRuns = search.run && (linkedRun || linkedRunQuery.isError)
    ? [...(linkedRun ? [linkedRun] : []), ...sessionQueryRuns.filter(run => run.queryRunId !== search.run)]
    : sessionQueryRuns;
  useEffect(() => { if (search.run) { setActivityView("queries"); setRunsCollapsed(false); } }, [search.run]);
  const toggleHistory = () => {
    if (window.matchMedia("(min-width: 1024px)").matches) setRunsCollapsed((collapsed) => !collapsed);
    else setActivityMobileOpen(true);
  };
  const observedRunIds = renderedScreenIdentity !== identity ? [] : [...new Set([...Object.values(screen.queryRuns), ...Object.values(adoptedBundles).flatMap(bundle => Object.values(bundle.queryRuns))])];
  const runQueries = useQueries({ queries: observedRunIds.map(runId => ({
    queryKey: queryKeys.dashboards.sessionRun(workspaceId, principalId ?? "", sessionId ?? "", runId),
    queryFn: ({ signal }: { signal: AbortSignal }) => dashboardAPI.getSessionQueryRun(workspaceId, sessionId as string, runId, signal),
    enabled: Boolean(sessionId) && !dashboardDeleted, ...immutableResultOptions, refetchInterval: runPollInterval,
  })) });
  const selectedRuns = runQueries.flatMap(query => query.data ? [query.data] : []);
  const permissionDenied = (error: unknown) => error instanceof DashboardApiError && (error.status === 401 || error.status === 403);
  const deniedRunIds = new Set(observedRunIds.filter((_, index) => permissionDenied(runQueries[index]?.error)));
  const resultIds = observedRunIds.filter((id, index) => !deniedRunIds.has(id) && runQueries[index]?.data?.state === "SUCCEEDED");
  const resultQueries = useQueries({ queries: resultIds.map(runId => ({
    queryKey: queryKeys.dashboards.result(workspaceId, principalId ?? "", sessionId ?? "", runId),
    queryFn: ({ signal }: { signal: AbortSignal }) => readDashboardResultWithAccess(() => dashboardAPI.getResult({ workspaceId, sessionId: sessionId as string, runId, signal })),
    select: selectDashboardResult,
    enabled: Boolean(sessionId) && !dashboardDeleted, ...immutableResultOptions,
  })) });
  const successfulResults: Record<string, NamedDashboardResult> = {};
  const unavailableRunIds = new Set(observedRunIds.filter((_, index) => runQueries[index]?.isError));
  resultIds.forEach((id, index) => {
    const query = resultQueries[index];
    if (query.data === null || permissionDenied(query.error)) deniedRunIds.add(id);
    if (query.isError) unavailableRunIds.add(id);
    if (query.isSuccess && query.data) successfulResults[id] = query.data;
  });
  const resultSignature = JSON.stringify([screen.inputOrder, screen.queryRuns, observedRunIds.map((id, index) => [id, runQueries[index]?.dataUpdatedAt, runQueries[index]?.errorUpdatedAt]), resultIds.map((id, index) => [id, resultQueries[index]?.dataUpdatedAt, resultQueries[index]?.errorUpdatedAt])]);
  useEffect(() => {
    if (renderedScreenIdentity !== identity) return;
    const available = { ...displayedResults, ...successfulResults };
    for (const id of deniedRunIds) {
      delete available[id];
      const resultKey = queryKeys.dashboards.result(workspaceId, principalId ?? "", sessionId ?? "", id);
      const cachedResult = queryClient.getQueryData(resultKey);
      if (cachedResult !== undefined && cachedResult !== null) queryClient.setQueryData(resultKey, null);
    }
    setDisplayedResults(available);
    if (session) {
      const nextBundles = adoptWidgetBundles(session.definition, screen, available, adoptedBundles, deniedRunIds);
      setAdoptedBundles(nextBundles);
      setResultAccessErrors(current => widgetResultAccessErrors(session.definition, screen.queryRuns, adoptedBundles, nextBundles, deniedRunIds, current));
    }
  // Immutable artifacts and request order define bundle adoption; no Session ready state exists.
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [identity, renderedScreenIdentity, resultSignature]);

  const currentParameters = dashboardQuery.data?.definition.parameters;
  const reviewInputs = entryNotice?.sessionId === sessionId && entryNotice.kind === "inputs";
  const canChangeInputs = internalViewer && access.hasRole("EXPLORER") && Boolean(currentParameters && (Object.keys(currentParameters).length > 0 || reviewInputs));
  // Run again opens new results with the same inputs on the current definition; when the definition's
  // parameters changed since these results, the user confirms the inputs instead. It waits for the
  // current definition, since the shown results' definition cannot decide either.
  const rerunBindings = internalViewer && currentParameters !== undefined && Object.keys(currentParameters).length === 0 ? {}
    : internalViewer && currentParameters !== undefined && sessionQuery.data
    && Object.keys(currentParameters).length === Object.keys(sessionQuery.data.parameterBindings).length
    && Object.keys(currentParameters).every((name) => Object.hasOwn(sessionQuery.data!.parameterBindings, name))
    ? sessionQuery.data.parameterBindings
    : null;

  const definitionActions = !pagesEntry && access.hasRole("EXPLORER") ? <>
    <Menu modal={false}>
      <MenuTrigger asChild><IconButton size="md" label="More actions" icon={Ellipsis} /></MenuTrigger>
      <MenuContent align="end" className="min-w-[180px]">
        {internalViewer && <MenuItem icon={RotateCcw} disabled={openMutation.isPending || currentParameters === undefined} onSelect={() => { if (rerunBindings) openMutation.mutate(rerunBindings); else setChangeInputsOpen(true); }}>Run again</MenuItem>}
        {draftHasChanges ? <MenuItem asChild><Link href={webPaths.dashboardBuilder(workspaceId, dashboardId)}><FilePenLine aria-hidden="true" />Review draft</Link></MenuItem> : null}
        <MenuItem asChild><Link href={webPaths.dashboardHistory(workspaceId, dashboardId)}><History aria-hidden="true" />History</Link></MenuItem>
        <MenuItem icon={Trash2} onSelect={() => setDeleteOpen(true)}>Delete dashboard</MenuItem>
      </MenuContent>
    </Menu>
    <DashboardDeleteDialog open={deleteOpen} workspaceId={workspaceId} principalId={principalId ?? ""} dashboardId={dashboardId} publicPages={workspace.publicPages} onCancel={() => setDeleteOpen(false)} onDeleted={async () => {
      setDeleteOpen(false);
      await navigate({ href: webPaths.dashboards(workspaceId) });
      await refreshDashboardDefinitionCaches(queryClient, workspaceId, principalId ?? "", dashboardId, true);
    }} />
  </> : null;
  const scheduleControl = <><DashboardSchedulesDialog dashboardId={dashboardId} onViewResult={(scheduleKey, scheduledAt) => onSearchChange({ scheduleKey, scheduledAt })} />{definitionActions}</>;
  if (dashboardDeleted && !pagesEntry) return <DashboardViewerShell title="Dashboard unavailable"><DashboardDeletedState workspaceId={workspaceId} dashboardId={dashboardId} canReadHistory={access.hasRole("EXPLORER")} /></DashboardViewerShell>;
  if (occurrenceMode && (!search.scheduleKey || !search.scheduledAt)) return <DashboardViewerShell actions={scheduleControl} title="Scheduled result"><StateBlock kind="error" layout="page" title="A schedule key and scheduled time are required." /></DashboardViewerShell>;
  if (occurrenceMode && occurrenceQuery.isPending) return <DashboardViewerShell actions={scheduleControl} title="Scheduled result"><StateBlock kind="loading" layout="page" title="Loading the scheduled result…" /></DashboardViewerShell>;
  if (occurrenceMode && !occurrence) return <DashboardViewerShell actions={scheduleControl} title="Scheduled result"><StateBlock kind="error" layout="page" title="This scheduled result is unavailable." /></DashboardViewerShell>;
  if (occurrenceMode && occurrence && !sessionId) return <DashboardViewerShell actions={scheduleControl} title={occurrence.definition.meta.title}><StateBlock kind="empty" layout="page" title={`Scheduled run · ${scheduledRunTime(occurrence.scheduledAt, Date.now())}`} description={`${workStatus(occurrence.outcome).label} · ${occurrence.reason ?? "No query was started"}`} icon={CalendarClock} /></DashboardViewerShell>;
  if (pagesEntry && !occurrenceMode && entryQuery.data?.session && entryQuery.data.session.dashboardId !== dashboardId) return <DashboardViewerShell title={definition?.meta.title ?? "Dashboard"}><StateBlock kind="error" layout="page" title="These results belong to a different dashboard." /></DashboardViewerShell>;
  if (pagesEntry && !occurrenceMode && entryQuery.isError) return <DashboardViewerShell title="Page unavailable"><StateBlock kind="error" layout="page" title="The dashboard results could not be loaded." description={loadErrorMessage(entryQuery.error, "Prepared results are unavailable for this Page.")} action={<DashboardLoadErrorActions workspaceId={workspaceId} pagesEntry error={entryQuery.error} onRetry={() => void entryQuery.refetch()} />} /></DashboardViewerShell>;
  if (pagesEntry && !occurrenceMode && !sessionId && entryQuery.isPending) return <DashboardViewerShell title={definition?.meta.title ?? "Dashboard"}><StateBlock kind="loading" layout="page" title="Finding prepared results…" /></DashboardViewerShell>;
  if (dashboardQuery.isPending && !definition) return <DashboardViewerShell actions={scheduleControl} title="Dashboard loading"><StateBlock kind="loading" layout="page" title="Loading the dashboard definition…" /></DashboardViewerShell>;
  if (!definition || permissionDenied(dashboardQuery.error)) return <DashboardViewerShell actions={scheduleControl} title="Dashboard unavailable"><StateBlock kind="error" layout="page" title={sessionErrorMessage(dashboardQuery.error, "The dashboard could not be loaded.")} action={<DashboardLoadErrorActions workspaceId={workspaceId} pagesEntry={pagesEntry} error={dashboardQuery.error} onRetry={dashboardQuery.error ? () => void dashboardQuery.refetch() : undefined} />} /></DashboardViewerShell>;
  if (pagesEntry && !sessionId && entryQuery.isSuccess && !entryQuery.data?.session) return <DashboardViewerShell actions={scheduleControl} title={definition.meta.title}><StateBlock kind="empty" layout="page" title="No results yet" description="Open the dashboard to run it. Its latest results will appear here." icon={CalendarClock} action={<Link href={webPaths.dashboard(workspaceId, dashboardId)} className={buttonClassName("secondary", "md", "")}>Open dashboard</Link>} /></DashboardViewerShell>;
  if (!sessionId && search.run) return <DashboardViewerShell actions={scheduleControl} title={definition.meta.title}><StateBlock kind="error" layout="page" title="This query run link is incomplete. Open the run from History." /></DashboardViewerShell>;
  if (!sessionId) {
    if (sessionsQuery.isPending || !sessionsQuery.isFetchedAfterMount) return <DashboardViewerShell actions={scheduleControl} title={definition.meta.title}><StateBlock kind="loading" layout="page" title="Finding the latest results…" /></DashboardViewerShell>;
    if (sessionsQuery.isError) return <DashboardViewerShell actions={scheduleControl} title={definition.meta.title}><StateBlock kind="error" layout="page" title={sessionErrorMessage(sessionsQuery.error, "History could not be loaded.")} action={<DashboardLoadErrorActions workspaceId={workspaceId} pagesEntry={pagesEntry} error={sessionsQuery.error} onRetry={() => void sessionsQuery.refetch()} />} /></DashboardViewerShell>;
    if (sessions.length > 0 && dashboardQuery.isError) return <DashboardViewerShell actions={scheduleControl} title={definition.meta.title}><StateBlock kind="error" layout="page" title={sessionErrorMessage(dashboardQuery.error, "The current dashboard definition could not be loaded.")} action={<DashboardLoadErrorActions workspaceId={workspaceId} pagesEntry={pagesEntry} error={dashboardQuery.error} onRetry={() => void dashboardQuery.refetch()} />} /></DashboardViewerShell>;
    if (sessions.length > 0) return <DashboardViewerShell actions={scheduleControl} title={definition.meta.title}><StateBlock kind={openError || latestSessionQuery.isError ? "error" : "loading"} layout="page" title={openError ?? (latestSessionQuery.isError ? sessionErrorMessage(latestSessionQuery.error, "The latest results could not be loaded.") : openMutation.isPending ? "Opening the updated dashboard…" : "Restoring the latest results…")} action={openError && isLoadErrorRetryable(openMutation.error) ? <Button variant="secondary" onClick={() => { if (applyEntrySession.current) openMutation.mutate(applyEntrySession.current.parameterBindings); }}>Retry</Button> : latestSessionQuery.isError ? <DashboardLoadErrorActions workspaceId={workspaceId} pagesEntry={pagesEntry} error={latestSessionQuery.error} onRetry={() => void latestSessionQuery.refetch()} /> : null} /></DashboardViewerShell>;
    if (Object.keys(definition.parameters).length > 0) {
      return <DashboardViewerShell actions={scheduleControl} title={definition.meta.title} description={definition.meta.description}><DashboardParameterForm key={dashboardId} parameters={definition.parameters} pending={openMutation.isPending} error={openError} onOpen={(bindings) => openMutation.mutate(bindings)} /></DashboardViewerShell>;
    }
    return <DashboardViewerShell actions={scheduleControl} title={definition.meta.title}><StateBlock kind={openError ? "error" : "loading"} layout="page" title={openMutation.isPending ? "Opening the dashboard…" : openError ?? "Preparing the dashboard…"} action={openError && isLoadErrorRetryable(openMutation.error) ? <Button type="button" variant="secondary" onClick={() => { autoOpenAttempt.current = null; setOpenError(null); openMutation.mutate({}); }}>Retry</Button> : null} /></DashboardViewerShell>;
  }
  if (sessionQuery.isPending) return <DashboardViewerShell actions={scheduleControl} title={definition.meta.title}><StateBlock kind="loading" layout="page" title="Loading results…" /></DashboardViewerShell>;
  if (!session || permissionDenied(sessionQuery.error)) return <DashboardViewerShell actions={scheduleControl} title={definition.meta.title}><StateBlock kind="error" layout="page" title={sessionErrorMessage(sessionQuery.error, "These results could not be loaded.")} action={<DashboardLoadErrorActions workspaceId={workspaceId} pagesEntry={pagesEntry} error={sessionQuery.error} onRetry={sessionQuery.error ? () => void sessionQuery.refetch() : undefined} />} /></DashboardViewerShell>;
  if (session.dashboardId !== dashboardId) return <DashboardViewerShell actions={scheduleControl} title={definition.meta.title}><StateBlock kind="error" layout="page" title="These results belong to a different dashboard." /></DashboardViewerShell>;

  if (renderedScreenIdentity !== identity) return <DashboardViewerShell actions={scheduleControl} title={definition.meta.title}><StateBlock kind="loading" layout="page" title="Preparing inputs…" /></DashboardViewerShell>;

  const display = displayedWidgets(session.definition, screen, selectedRuns, displayedResults, adoptedBundles, unavailableRunIds, deniedRunIds, internalViewer);
  const resultError = resultQueries.find((result) => result.isError)?.error;
  const listedActivitySessions = sessions.map((summary) => summary.dashboardSessionId === session.dashboardSessionId
    ? { ...summary, runningQueryRuns: session.runningQueryRuns }
    : summary);
  const entrySession = entryQuery.data?.session;
  const activitySessions = entrySession?.dashboardSessionId === session.dashboardSessionId && !listedActivitySessions.some(item => item.dashboardSessionId === session.dashboardSessionId)
    ? [...listedActivitySessions, { ...entrySession, runningQueryRuns: session.runningQueryRuns }]
    : listedActivitySessions;
  const anySessionRunning = activitySessions.some((summary) => summary.runningQueryRuns.some((run) => run.state === "RUNNING"));
  const refreshingQueryNames = Object.keys(screen.queryRuns).filter(name => screen.pending || selectedRuns.some(run => run.queryRunId === screen.queryRuns[name] && run.state === "RUNNING"));
  const resultsAt = latestDisplayedResultAt(display.resultsByWidgetId, selectedRuns);
  const resultsTimeZone = displayedResultsTimeZoneLabel(display.resultsByWidgetId);
  const pageInputs = pagesEntry ? dashboardInputs(session.parameterBindings, session.definition.parameters) : [];
  const historyVisible = activityMobileOpen || !runsCollapsed;
  const widgetRuns = (widgetRunNames ?? []).map((name) => ({ name, run: selectedRuns.find((run) => run.queryRunId === screen.queryRuns[name]) }));
  const widgetActions: DashboardWidgetActions | undefined = pagesEntry ? undefined
    : internalViewer ? { pending: screen.pending, onRefresh: (queryNames) => void submitVariables({}, queryNames), onShowRuns: setWidgetRunNames, onShowMetrics: setWidgetMetricsTarget, onShowSql: setWidgetMetricsTarget }
      : { pending: screen.pending, onShowMetrics: setWidgetMetricsTarget, onShowSql: setWidgetMetricsTarget };
  const actions = pagesEntry
    ? <PageViewerActions captureTarget={chartCanvasRef} provenance={{ title: pagePresentation?.title ?? definition.meta.title, description: pagePresentation ? pagePresentation.description ?? null : definition.meta.description ?? null, inputs: pageInputs, resultsAt, source: pagePresentation?.source ?? null }} sessionId={session.dashboardSessionId} historyOpen={activityMobileOpen} onHistory={occurrenceMode ? undefined : () => setActivityMobileOpen(true)}>{pagePresentation?.actions}</PageViewerActions>
    : occurrenceMode ? scheduleControl : <>
      <Button type="button" variant="secondary" className="relative" aria-expanded={historyVisible} aria-controls={runsCollapsed ? undefined : "dashboard-activity-sidebar"} onClick={toggleHistory}>
        <History aria-hidden="true" />History{anySessionRunning ? <RunsStatusDot running /> : null}
      </Button>
      <PageShareActions sessionId={session.dashboardSessionId} />
      {scheduleControl}

    </>;
  return (
    <DashboardViewerShell actions={actions}
      title={pagePresentation?.title ?? definition.meta.title}
      description={pagePresentation ? pagePresentation.description : internalViewer && dashboardQuery.data ? (
        <>
          {definition.meta.description ? <p>{definition.meta.description}</p> : null}
          <p className="type-meta tabular-nums">
            <ActorProvenance
              verb="Updated"
              actor={dashboardQuery.data.updatedBy}
              surface={dashboardQuery.data.credentialSurface}
              agentApp={dashboardQuery.data.agentApp}
              principalId={principalId ?? null}
              sampleDefinition={Boolean(workspace.sample)}
              time={<time dateTime={dashboardQuery.data.updatedAt} title={sessionExactTime(dashboardQuery.data.updatedAt)}>{sessionCreatedLabel(dashboardQuery.data.updatedAt, Date.now())}</time>}
            />
          </p>
        </>
      ) : definition.meta.description}
      aside={occurrenceMode ? undefined : <DashboardActivitySidebar principalId={principalId} drawerOnly={pagesEntry} readOnly={pagesEntry} expandedGroups={expandedGroups} onExpandedGroupsChange={setExpandedGroups}
        sessions={activitySessions}
        selectedSessionId={session.dashboardSessionId}
        selectedSession={session}
        linkedRunId={search.run}
        queryRuns={activityQueryRuns}
        targetQueryRuns={screen.queryRuns}
        queryRunsLoading={Boolean(search.run && linkedRunQuery.isPending) || sessionQueryRunsQuery.isPending || sessionQueryRunsQuery.isFetching && sessionQueryRuns.length === 0}
        queryRunsError={linkedRunQuery.isError ? sessionErrorMessage(linkedRunQuery.error, "The linked query run is unavailable.") : sessionQueryRunsQuery.error ? sessionErrorMessage(sessionQueryRunsQuery.error, "Query run history could not be loaded.") : null}
        hasNextQueryRuns={sessionQueryRunsQuery.hasNextPage}
        loadingNextQueryRuns={sessionQueryRunsQuery.isFetchingNextPage}
        refreshingQueryNames={refreshingQueryNames}
        onRefreshQuery={(queryName) => void submitVariables({}, [queryName])}
        onLoadMoreQueryRuns={() => {
          if (linkedRunQuery.isError) void linkedRunQuery.refetch();
          if (sessionQueryRunsQuery.isError) void sessionQueryRunsQuery.refetch();
          else if (!linkedRunQuery.isError) void sessionQueryRunsQuery.fetchNextPage();
        }}
        hasNextSessions={sessionsQuery.hasNextPage}
        loadingNextSessions={sessionsQuery.isFetchingNextPage}
        sessionsError={sessionsQuery.error ? sessionErrorMessage(sessionsQuery.error, "History could not be loaded.") : null}
        onLoadMoreSessions={() => void (sessionsQuery.isError ? sessionsQuery.refetch() : sessionsQuery.fetchNextPage())}
        onSelectSession={(nextSessionId) => {
          setEntryNotice(null);
          setChangeInputsOpen(false);
          setOpenError(null);
          if (nextSessionId !== session.dashboardSessionId) onSearchChange({ session: nextSessionId });
        }}
        view={activityView}
        onViewChange={setActivityView}
        collapsed={runsCollapsed}
        onCollapsedChange={setRunsCollapsed}
        mobileOpen={activityMobileOpen}
        onMobileOpenChange={setActivityMobileOpen}
      />}
      toolbar={pagesEntry ? undefined : <DashboardInputsBar
        chips={dashboardInputChips(session.parameterBindings, session.definition.parameters)}
        status={dashboardResultsStatus(resultsAt, screen.pending || Object.values(screen.queryRuns).some((runId) => selectedRuns.some((run) => run.queryRunId === runId && run.state === "RUNNING")))}
        timeZone={resultsTimeZone}
        onChangeInputs={canChangeInputs ? () => { setOpenError(null); setChangeInputsOpen(true); } : undefined}
      />}
    >
      {pagesEntry && <PageViewerContext inputs={pageInputs} resultsAt={resultsAt} timeZone={resultsTimeZone} />}
      {entryNotice?.sessionId === sessionId ? <p role="status" className="type-meta mb-4 text-foreground-secondary">{entryNotice.kind === "updated" ? "Updated for definition changes. Previous results are in History." : "The dashboard inputs have changed. Review inputs to use the updated dashboard."}</p> : null}
      {occurrenceMode && occurrence ? <OccurrenceBanner occurrence={occurrence} onExplore={() => onSearchChange({ session: session.dashboardSessionId })} /> : null}
      {screen.error ? <p role="alert" className="type-meta mb-4 text-error">{screen.error}</p> : null}
      {openError && !changeInputsOpen ? <p role="alert" className="type-meta mb-4 text-error">{openError}</p> : null}
      {resultError && Object.keys(displayedResults).length === 0 ? <p role="alert" className="type-meta mb-4 text-error">Results could not be loaded. Try again shortly.</p> : null}
      <div ref={chartCanvasRef} className="relative mx-auto w-full max-w-[1380px]">
        <DashboardLayout
          readOnly={occurrenceMode}
          key={session.dashboardSessionId}
          definition={session.definition}
          queryTypes={dashboardQueryTypes(session.definition.queries)}
          parameters={session.parameterBindings}
          variables={screen.candidate}
          variablesByWidgetId={display.variablesByWidgetId}
          variableRevision={screen.inputOrder}
          variableRevisionsByWidgetId={display.variableRevisionsByWidgetId}
          variableDefinitions={session.definition.variables}
          resultsByWidgetId={display.resultsByWidgetId}
          displayedWidgetIds={display.widgetIds}
          refreshingWidgetIds={display.refreshingIds}
          failureWidgetIds={display.failureIds}
          errorMessagesByWidgetId={{ ...display.errorMessagesByWidgetId, ...resultAccessErrors }}
          loadingMessagesByWidgetId={display.loadingMessagesByWidgetId}
          widgetActions={widgetActions}
          onInputVariablesSet={(values) => void submitVariables(values)}
          onVariablesSet={(values, displayedInputOrder) => {
            const current = screenRef.current;
            if (current?.identity === identity && acceptsChartInput(current.state, displayedInputOrder)) void submitVariables(values);
          }}
        />
      </div>
      {canChangeInputs && currentParameters ? <ChangeInputsDialog key={session.dashboardSessionId} open={changeInputsOpen} onOpenChange={setChangeInputsOpen} parameters={currentParameters} currentValues={reviewInputs ? compatibleParameterValues(currentParameters, session.parameterBindings) : session.parameterBindings} reviewRequired={reviewInputs} pending={openMutation.isPending} error={openError} onSubmit={(bindings) => openMutation.mutate(bindings)} /> : null}
      <Dialog closeLabel="Close run details" open={widgetRunNames !== null} onOpenChange={(open) => { if (!open) setWidgetRunNames(null); }} size="sm" title="Run details" description={widgetRuns.map(({ name }) => name).join(", ")}>
        {widgetRuns.map(({ name, run }) => <section key={name} aria-label={name} className="border-b border-border-light pb-4 mb-4 last:mb-0 last:border-0 last:pb-0">
          {widgetRuns.length > 1 ? <h3 className="type-control mb-3 text-heading">{name}</h3> : null}
          {run ? <DashboardQueryRunDetail run={run} principalId={principalId} currentTarget /> : <p className="type-meta text-foreground-secondary">Run details are not available yet.</p>}
        </section>)}
      </Dialog>
      <WidgetMetricsDialog workspaceId={workspaceId} principalId={principalId ?? null} sampleWorkspace={Boolean(workspace.sample)} queries={session.definition.queries} target={widgetMetricsTarget} onClose={() => setWidgetMetricsTarget(null)} />
    </DashboardViewerShell>
  );
}

type DashboardOccurrence = NonNullable<Awaited<ReturnType<typeof dashboardAPI.getOccurrence>>>;

function OccurrenceBanner({ occurrence, onExplore }: { occurrence: DashboardOccurrence; onExplore: () => void }) {
  const [now] = useState(() => Date.now());
  const failedRuns: [string, Pick<DashboardQueryRun, "failureMessage" | "updatedAt">][] = Object.entries(occurrence.queryRuns).filter(([, run]) => run.state === "FAILED");
  return <section aria-label="Scheduled run" className="type-control mb-5 rounded-[6px] border border-border bg-surface px-4 py-3">
    <div className="flex flex-wrap items-center justify-between gap-3">
      <div>
        <p className="flex flex-wrap items-center gap-x-2"><span className="tabular-nums">Scheduled run · <time dateTime={occurrence.scheduledAt} title={new Date(occurrence.scheduledAt).toLocaleString("en-US")}>{scheduledRunTime(occurrence.scheduledAt, now)}</time></span><WorkStatus state={occurrence.resultState ?? occurrence.outcome} labelDisplay="inline" size="sm" /></p>
        <p className="type-meta mt-1 text-foreground-secondary">These are the results this run produced. Its inputs and query runs stay fixed.</p>
      </div>
      <Button type="button" variant="secondary" size="sm" onClick={onExplore}>Explore these results</Button>
    </div>
    {failedRuns.map(([name, run]) => <p key={name} className="type-meta mt-2 text-error">{name}: {run.failureMessage ?? "The query failed."} · Failed <time dateTime={run.updatedAt} className="tabular-nums">{scheduledRunTime(run.updatedAt, now)}</time></p>)}
  </section>;
}

function DashboardViewerShell({
  actions,
  title,
  description,
  toolbar,
  aside,
  children,
}: {
  title: string;
  description?: React.ReactNode;
  actions?: React.ReactNode;
  toolbar?: React.ReactNode;
  aside?: React.ReactNode;
  children: React.ReactNode;
}) {
  return <PageViewerFrame title={title} description={description} actions={actions} toolbar={toolbar} aside={aside}>{children}</PageViewerFrame>;
}

function DashboardLoadErrorActions({ workspaceId, pagesEntry, error, onRetry }: { workspaceId: string; pagesEntry: boolean; error: unknown; onRetry?: () => void }) {
  const retry = onRetry && isLoadErrorRetryable(error);
  return <><Link href={pagesEntry ? webPaths.pages(workspaceId) : webPaths.dashboards(workspaceId)} className={buttonClassName(retry ? "ghost" : "secondary", "md", "")}>{pagesEntry ? "Back to Pages" : "Back to dashboards"}</Link>{retry ? <Button type="button" variant="secondary" onClick={onRetry}>Retry</Button> : null}</>;
}
