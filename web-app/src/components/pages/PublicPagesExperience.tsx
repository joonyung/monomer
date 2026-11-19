import { immutableResultOptions, runPollInterval } from "@/features/dashboard/viewer-query-policy";
import { useEffect, useMemo, useRef, useState, type ReactNode } from "react";
import {
  useInfiniteQuery,
  useQueries,
  useQuery,
  useQueryClient,
} from "@tanstack/react-query";
import { useNavigate, useParams, useSearch } from "@tanstack/react-router";
import { useTransientScrollbar } from "@/components/ui/useTransientScrollbar";
import { z } from "zod";
import { LayoutDashboard } from "lucide-react";
import {
  publicPagesAPI,
  publicPagesKey,
  isPublicAccessDenied,
  type PublicTree,
  type PublicPage,
  type PublicEntry,
  type PublicSession,
} from "@/api/public-pages";
import type { DashboardJsonObject } from "@/api/dashboard";
import { useAuthStore } from "@/lib/auth/store";
import { DashboardLayout } from "@/components/dashboard/DashboardLayout";
import { DocumentRenderer } from "./DocumentRenderer";
import { PublicPagesShell } from "./PublicPagesShell";
import { useReaderPageSource, useReaderReportingTimeZone } from "./usePageSource";
import { DashboardInputTimeZoneProvider } from "@/components/dashboard/DashboardInputTimeZone";
import { DashboardActivitySidebar } from "@/components/dashboard/DashboardActivitySidebar";
import { PageViewerFrame, PageViewerActions, PageViewerContext } from "./PageViewerFrame";
import { dashboardInputs, displayedResultsTimeZoneLabel, latestDisplayedResultAt } from "@/components/dashboard/capture/provenance";
import {
  validateDashboardResult,
  type NamedDashboardResult,
} from "@/features/dashboard/dashboard-result";
import {
  acceptsChartInput,
  acceptScreenRequest,
  adoptWidgetBundles,
  beginScreenRequest,
  displayedWidgets,
  initialScreen,
  rejectScreenRequest,
  type DashboardWidgetBundle,
} from "@/pages/dashboard/viewer/screen-state";
import { PageShareActions } from "./PageShareActions";
import { Button } from "@/components/ui/Button";
import { StateBlock } from "@/components/ui/StateBlock";

export function PublicUnavailable({ retry }: { retry?: () => void }) {
  const authenticated = useAuthStore(state => state.status === "authenticated");
  return (
    <main className="flex min-h-[60vh] flex-col">
      <StateBlock
        kind="error"
        layout="page"
        title={<span role="heading" aria-level={1}>Page unavailable</span>}
        description={authenticated ? "This Page is unavailable or you no longer have access." : "This Page is private, has been removed, or could not be loaded."}
        action={(retry || !authenticated) && <>
          {retry && (
            <Button variant="secondary" onClick={retry}>
              Retry
            </Button>
          )}
          {!authenticated && <a
            className="type-body underline underline-offset-4"
            href={`/login?redirect=${encodeURIComponent(window.location.pathname + window.location.search)}`}
          >
            Sign in
          </a>}
        </>}
      />
    </main>
  );
}
type PublicPagePrefetch = {
  workspaceId: string;
  pageId?: string;
  controller?: AbortController;
  tree?: Promise<PublicTree>;
  entry?: { sessionId?: string; response: Promise<PublicEntry> };
};

function discardPrefetch(prefetch: PublicPagePrefetch) {
  prefetch.controller?.abort();
  delete prefetch.controller;
  delete prefetch.tree;
  delete prefetch.entry;
}
function prefetchReader(prefetch: PublicPagePrefetch, sessionId?: string) {
  if (!prefetch.pageId || prefetch.controller) return;
  const controller = new AbortController();
  prefetch.controller = controller;
  prefetch.tree = publicPagesAPI.tree(prefetch.workspaceId, controller.signal);
  prefetch.entry = { sessionId, response: publicPagesAPI.entry(prefetch.workspaceId, prefetch.pageId, sessionId, controller.signal) };
  void prefetch.tree.catch(() => undefined);
  void prefetch.entry.response.catch(() => undefined);
}

export function PagesAccessGate({
  workspaceId,
  children,
}: {
  workspaceId: string;
  children: ReactNode;
}) {
  const status = useAuthStore((state) => state.status);
  const epoch = useAuthStore((state) => state.authEpoch);
  const params = useParams({ strict: false });
  const pageId = "pageId" in params ? params.pageId : undefined;
  const search = useSearch({ strict: false }) as { session?: string };
  const prefetch = useMemo<PublicPagePrefetch>(() => {
    // A credential change must discard prefetched data even on the same Page.
    void epoch;
    return { workspaceId, pageId };
  }, [workspaceId, pageId, epoch]);
  const requestedSession = useRef(search.session);
  requestedSession.current = search.session;
  useEffect(() => {
    if (status === "unauthenticated" && z.string().uuid().safeParse(workspaceId).success) {
      prefetchReader(prefetch, requestedSession.current);
    }
    return () => discardPrefetch(prefetch);
  }, [workspaceId, status, prefetch]);
  const query = useQuery({
    queryKey: ["pages-access", workspaceId, epoch, status],
    queryFn: async ({ signal }) => {
      try {
        const access = await publicPagesAPI.access(workspaceId, signal);
        signal.throwIfAborted();
        if (access.mode === "MEMBER") discardPrefetch(prefetch);
        else if (!query.data && status === "authenticated") prefetchReader(prefetch, requestedSession.current);
        return access;
      } catch (error) {
        discardPrefetch(prefetch);
        throw error;
      }
    },
    enabled:
      z.string().uuid().safeParse(workspaceId).success &&
      status !== "idle" &&
      status !== "hydrating",
    retry: false,
    staleTime: 0,
    refetchInterval: 15_000,
  });
  if ((query.isError && (!query.data || isPublicAccessDenied(query.error))) || !z.string().uuid().safeParse(workspaceId).success)
    return <PublicUnavailable retry={() => void query.refetch()} />;
  if (!query.data || !query.isFetchedAfterMount)
    return (
      <StateBlock kind="loading" layout="page" title="Opening Pages…" />
    );
  return query.data.mode === "MEMBER" ? (
    children
  ) : (
    <PublicPagesExperience
      key={`${workspaceId}:${epoch}`}
      workspaceId={workspaceId}
      readerMode={query.data.mode}
      prefetch={prefetch}
    />
  );
}
export function PublicPagesExperience({
  workspaceId,
  readerMode,
  prefetch,
}: {
  workspaceId: string;
  readerMode: "VIEWER" | "PUBLIC";
  prefetch?: PublicPagePrefetch;
}) {
  const scrollbar = useTransientScrollbar();
  const params = useParams({ strict: false });
  const pageId = "pageId" in params ? params.pageId : undefined;
  const search = useSearch({ strict: false }) as {
    session?: string;
    run?: string;
    scheduleKey?: string;
    scheduledAt?: string;
  };
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const tree = useQuery({
    queryKey: [...publicPagesKey(workspaceId), "tree"],
    queryFn: (context): Promise<PublicTree> => {
      const response = !tree.data ? prefetch?.tree : undefined;
      if (prefetch) delete prefetch.tree;
      return response ?? publicPagesAPI.tree(workspaceId, context.signal);
    },
    retry: false,
    staleTime: 0,
    refetchInterval: 15_000,
  });
  const source = useReaderPageSource(workspaceId, tree.data?.workspaceName ?? "", readerMode);
  const reportingTimeZone = useReaderReportingTimeZone(workspaceId, readerMode);
  const setLandingPreference = useAuthStore((state) => state.setLandingPreference);
  // A Viewer's workspace is their Pages, so opening them counts as opening the workspace.
  useEffect(() => {
    if (readerMode === "VIEWER" && tree.data) setLandingPreference(workspaceId);
  }, [readerMode, setLandingPreference, tree.data, workspaceId]);
  useEffect(() => {
    if (search.run || search.scheduleKey || search.scheduledAt)
      void navigate({
        to: "/workspaces/$workspaceId/pages/$pageId",
        params: { workspaceId, pageId: pageId! },
        replace: true,
        search: search.session ? { session: search.session } : {},
      });
  }, [
    navigate,
    pageId,
    search.run,
    search.scheduleKey,
    search.scheduledAt,
    search.session,
    workspaceId,
  ]);
  useEffect(() => {
    if (isPublicAccessDenied(tree.error)) {
      prefetch?.controller?.abort();
      if (prefetch) delete prefetch.entry;
      queryClient.removeQueries({ queryKey: publicPagesKey(workspaceId) });
    }
  }, [prefetch, queryClient, tree.error, workspaceId]);
  if (tree.isError && (!tree.data || isPublicAccessDenied(tree.error)))
    return <PublicUnavailable retry={() => void tree.refetch()} />;
  if (!tree.data || !tree.isFetchedAfterMount)
    return (
      <StateBlock kind="loading" layout="page" title="Loading Pages…" />
    );
  const page = tree.data.pages.find((candidate) => candidate.pageId === pageId);
  const link = (id: string) =>
    `/workspaces/${encodeURIComponent(workspaceId)}/pages/${encodeURIComponent(id)}`;
  const navigation = (
    <nav aria-label="Pages" className="space-y-1 p-4">
      {tree.data.pages.map((item) => (
        <a
          key={item.pageId}
          href={link(item.pageId)}
          aria-current={item.pageId === pageId ? "page" : undefined}
          className={`block rounded-[4px] px-3 py-2 type-control hover:bg-surface ${item.pageId === pageId ? "bg-surface text-heading" : "text-foreground-secondary"}`}
        >
          {item.displayTitle}
        </a>
      ))}
    </nav>
  );
  return (
    <PublicPagesShell workspaceId={workspaceId} pageId={pageId} tree={tree.data} source={source} readerMode={readerMode} onRetry={() => void tree.refetch()}>
      {!pageId ? (
        <main onScroll={scrollbar.onScroll} className={`mx-auto w-full max-w-[1080px] overflow-y-auto px-6 py-8 ${scrollbar.className}`}>
          <h1 className="mb-6 type-display">Pages</h1>
          {navigation}
        </main>
      ) : !page ? (
        <PublicUnavailable />
      ) : page.kind === "DASHBOARD" ? (
        <DashboardInputTimeZoneProvider timeZone={reportingTimeZone}><PublicDashboardPage
          key={pageId}
          workspaceId={workspaceId}
          source={source}
          page={page}
          sessionId={search.session}
          prefetch={prefetch}
          onSession={(session) => {
            void navigate({
              to: "/workspaces/$workspaceId/pages/$pageId",
              params: { workspaceId, pageId: pageId! },
              replace: true,
              search: { session },
            });
          }}
        /></DashboardInputTimeZoneProvider>
      ) : page.kind === "DOCUMENT" ? (
        <PublicDocument key={pageId} workspaceId={workspaceId} page={page} />
      ) : (
        <main onScroll={scrollbar.onScroll} className={`mx-auto w-full max-w-[1080px] overflow-y-auto px-6 py-8 ${scrollbar.className}`}>
          <h1 className="type-display">{page.displayTitle}</h1>
          <p className="mt-2 type-body text-foreground-secondary">
            {page.description}
          </p>
          {navigation}
        </main>
      )}
    </PublicPagesShell>
  );
}
function PublicDocument({
  workspaceId,
  page,
}: {
  workspaceId: string;
  page: PublicPage;
}) {
  const scrollbar = useTransientScrollbar();
  const content = useQuery({
    queryKey: [...publicPagesKey(workspaceId), page.pageId, "content"],
    queryFn: ({ signal }) =>
      publicPagesAPI.content(workspaceId, page.pageId, signal),
    refetchInterval: 15_000,
    retry: false,
  });
  const bytes = useMemo(
    () => new TextEncoder().encode(content.data?.source ?? ""),
    [content.data?.source],
  );
  if (content.isError)
    return <PublicUnavailable retry={() => void content.refetch()} />;
  if (!content.data)
    return (
      <StateBlock kind="loading" layout="page" title="Loading document…" />
    );
  const download = () => {
    const objectUrl = URL.createObjectURL(
      new Blob([bytes], { type: "text/plain;charset=utf-8" }),
    );
    const anchor = document.createElement("a");
    anchor.href = objectUrl;
    anchor.download = `${page.displayTitle}.${content.data!.format === "NOTEBOOK" ? "ipynb" : content.data!.format === "HTML" ? "html" : "md"}`;
    anchor.click();
    window.setTimeout(() => URL.revokeObjectURL(objectUrl), 0);
  };
  return (
    <main onScroll={scrollbar.onScroll} className={`min-h-0 flex-1 overflow-y-auto ${scrollbar.className}`}>
      <div className="mx-auto max-w-[1080px] px-4 py-6 sm:px-8">
        <header className="mb-6 flex flex-wrap items-start justify-between gap-4 border-b border-border pb-5">
          <div>
            <h1 className="type-display">{page.displayTitle}</h1>
            <p className="mt-2 type-body text-foreground-secondary">
              {page.description}
            </p>
          </div>
          <PageShareActions />
        </header>
        <DocumentRenderer
          bytes={bytes}
          format={content.data.format}
          title={content.data.title}
          downloading={false}
          onDownload={download}
        />
      </div>
    </main>
  );
}
function PublicDashboardPage({
  workspaceId,
  source,
  page,
  sessionId,
  onSession,
  prefetch,
}: {
  workspaceId: string;
  source: string;
  page: PublicPage;
  sessionId?: string;
  prefetch?: PublicPagePrefetch;
  onSession: (id: string) => void;
}) {
  const [entryVisit] = useState(() => crypto.randomUUID());
  const entry = useQuery({
    queryKey: [
      ...publicPagesKey(workspaceId),
      page.pageId,
      "entry",
      entryVisit,
      sessionId ?? "default",
    ],
    queryFn: () => {
      const prepared = prefetch?.entry;
      if (prefetch) delete prefetch.entry;
      return prepared?.sessionId === sessionId && prepared
        ? prepared.response
        : publicPagesAPI.entry(workspaceId, page.pageId, sessionId);
    },
    retry: false,
    staleTime: Infinity,
    gcTime: 0,
    refetchOnWindowFocus: false,
  });
  const queryClient = useQueryClient();
  useEffect(() => {
    const selected = entry.data?.session;
    if (selected && !sessionId) {
      queryClient.setQueryData(
        [
          ...publicPagesKey(workspaceId),
          page.pageId,
          "entry",
          entryVisit,
          selected.sessionId,
        ],
        entry.data,
      );
      onSession(selected.sessionId);
    }
  }, [
    entry.data,
    entryVisit,
    onSession,
    page.pageId,
    queryClient,
    sessionId,
    workspaceId,
  ]);
  if (entry.isError)
    return <PublicUnavailable retry={() => void entry.refetch()} />;
  if (!entry.data)
    return (
      <StateBlock kind="loading" layout="page" title="Finding prepared results…" />
    );
  if (!entry.data.session)
    return (
      <main className="p-8">
        <h1 className="type-display">{page.displayTitle}</h1>
        <StateBlock kind="empty" layout="page" icon={LayoutDashboard} title="No results have been published yet. Check back later." />
      </main>
    );
  return (
    <PublicSessionViewer
      key={entry.data.session.sessionId}
      workspaceId={workspaceId}
      source={source}
      page={page}
      entry={entry.data as PublicEntry & { session: PublicSession }}
      onSession={onSession}
    />
  );
}
function PublicSessionViewer({
  workspaceId,
  source,
  page,
  entry,
  onSession,
}: {
  workspaceId: string;
  source: string;
  page: PublicPage;
  entry: PublicEntry & { session: PublicSession };
  onSession: (id: string) => void;
}) {
  const { session } = entry;
  const scope = [
    ...publicPagesKey(workspaceId),
    page.pageId,
    session.sessionId,
  ];
  const [screen, setScreen] = useState(() => ({
    ...initialScreen(session.initialVariables),
    queryRuns: Object.fromEntries(Object.entries(entry.initialQueryRuns).map(([name, run]) => [name, run.queryRunId])),
  }));
  const screenRef = useRef(screen);
  screenRef.current = screen;
  const [bundles, setBundles] = useState<Record<string, DashboardWidgetBundle>>(
    {},
  );
  const [available, setAvailable] = useState<
    Record<string, NamedDashboardResult>
  >({});
  const [collapsed, setCollapsed] = useState(true);
  const [mobileOpen, setMobileOpen] = useState(false);
  const [activityView, setActivityView] = useState<"queries" | "sessions">("queries");
  const [expandedGroups, setExpandedGroups] = useState<Record<string, readonly string[]>>({});
  const [failedRequest, setFailedRequest] = useState<{
    variables: DashboardJsonObject;
    key: string;
    order: number;
  } | null>(null);
  const [accessRevoked, setAccessRevoked] = useState(false);
  const canvasRef = useRef<HTMLDivElement>(null);
  const alive = useRef(true);
  useEffect(() => {
    alive.current = true;
    return () => {
      alive.current = false;
    };
  }, []);
  const queryClient = useQueryClient();
  const sessions = useInfiniteQuery({
    queryKey: [...publicPagesKey(workspaceId), page.pageId, "sessions"],
    queryFn: ({ pageParam, signal }) =>
      publicPagesAPI.sessions(workspaceId, page.pageId, pageParam, signal),
    initialPageParam: null as string | null,
    getNextPageParam: (last) => last.nextCursor ?? undefined,
    enabled: !accessRevoked && mobileOpen && activityView === "sessions",
    refetchInterval: 15_000,
    retry: false,
  });
  const runs = useInfiniteQuery({
    queryKey: [...scope, "runs"],
    queryFn: ({ pageParam, signal }) =>
      publicPagesAPI.runs(
        workspaceId,
        page.pageId,
        session.sessionId,
        pageParam,
        signal,
      ),
    initialPageParam: null as string | null,
    getNextPageParam: (last) => last.nextCursor ?? undefined,
    enabled: !accessRevoked && mobileOpen && activityView === "queries",
    refetchInterval: 5_000,
    retry: false,
  });
  const observed = [
    ...new Set([
      ...Object.values(screen.queryRuns),
      ...Object.values(bundles).flatMap((bundle) =>
        Object.values(bundle.queryRuns),
      ),
    ]),
  ];
  const runQueries = useQueries({
    queries: observed.map((id) => ({
      queryKey: [...scope, "run", id],
      initialData: () => Object.values(entry.initialQueryRuns).find(run => run.queryRunId === id),
      queryFn: ({ signal }: { signal: AbortSignal }) =>
        publicPagesAPI.run(
          workspaceId,
          page.pageId,
          session.sessionId,
          id,
          signal,
        ),
      ...immutableResultOptions,
      enabled: !accessRevoked,
      refetchInterval: runPollInterval,
    })),
  });
  const resultIds = observed.filter(
    (_, index) =>
      runQueries[index].data?.state === "SUCCEEDED" &&
      runQueries[index].data?.resultAvailable,
  );
  const resultQueries = useQueries({
    queries: resultIds.map((id) => ({
      queryKey: [...scope, "result", id],
      queryFn: ({ signal }: { signal: AbortSignal }) =>
        publicPagesAPI.result(
          workspaceId,
          page.pageId,
          session.sessionId,
          id,
          signal,
        ),
      select: validateDashboardResult,
      ...immutableResultOptions,
      enabled: !accessRevoked,
    })),
  });
  const revoked =
    accessRevoked ||
    [sessions, runs, ...runQueries, ...resultQueries].some((query) =>
      isPublicAccessDenied(query.error),
    );
  const signature = JSON.stringify([
    screen.inputOrder,
    screen.queryRuns,
    runQueries.map((query) => [query.dataUpdatedAt, query.errorUpdatedAt]),
    resultQueries.map((query) => [query.dataUpdatedAt, query.errorUpdatedAt]),
  ]);
  useEffect(() => {
    if (!accessRevoked) return;
    setAvailable({});
    setBundles({});
    queryClient.removeQueries({
      queryKey: [...publicPagesKey(workspaceId), page.pageId, session.sessionId],
    });
  }, [accessRevoked, page.pageId, queryClient, session.sessionId, workspaceId]);
  useEffect(() => {
    if (revoked) {
      setAccessRevoked(true);
      return;
    }
    const next = { ...available };
    resultIds.forEach((id, index) => {
      const query = resultQueries[index];
      if (query.isSuccess) next[id] = query.data;
    });
    setAvailable(next);
    setBundles((current) =>
      adoptWidgetBundles(session.display, screen, next, current, new Set()),
    );
    // Result revisions and input order are the adoption boundary.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [signature, revoked]);
  async function send(request: {
    variables: DashboardJsonObject;
    key: string;
    order: number;
  }) {
    setFailedRequest(null);
    try {
      const response = await publicPagesAPI.resolve(
        workspaceId,
        page.pageId,
        session.sessionId,
        request.variables,
        request.key,
      );
      if (!alive.current) return;
      setScreen((current) =>
        acceptScreenRequest(current, request.order, response),
      );
      void queryClient.invalidateQueries({ queryKey: [...scope, "runs"] });
    } catch (error) {
      if (!alive.current || request.order !== screenRef.current.latestRequest)
        return;
      if (isPublicAccessDenied(error)) {
        setAccessRevoked(true);
      }
      setScreen((current) =>
        rejectScreenRequest(
          current,
          request.order,
          "Inputs could not be applied. Previous results remain visible.",
        ),
      );
      setFailedRequest(request);
    }
  }
  function submit(changes: DashboardJsonObject) {
    const next = beginScreenRequest(screenRef.current, changes);
    screenRef.current = next;
    setScreen(next);
    void send({
      variables: next.candidate,
      key: crypto.randomUUID(),
      order: next.latestRequest,
    });
  }
  const initialized = useRef(false);
  useEffect(() => {
    if (initialized.current) return;
    initialized.current = true;
    const required = session.display.queryNames;
    if (!required.every((name) => entry.initialQueryRuns[name])) submit({});
    // Entry is consumed once; polling never resolves or switches this session.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);
  if (revoked) return <PublicUnavailable />;
  const selectedRuns = runQueries.flatMap((query) =>
    query.data ? [query.data] : [],
  );
  const unavailable = new Set([
    ...observed.filter(
      (_, index) =>
        runQueries[index].isError ||
        (runQueries[index].data?.state === "SUCCEEDED" &&
          !runQueries[index].data?.resultAvailable),
    ),
    ...resultIds.filter((_, index) => resultQueries[index].isError),
  ]);
  const display = displayedWidgets(
    session.display,
    screen,
    selectedRuns,
    available,
    bundles,
    unavailable,
    new Set(),
    false,
  );
  const inputs = dashboardInputs(session.parameterBindings, session.display.parameters);
  const resultsAt = latestDisplayedResultAt(display.resultsByWidgetId, selectedRuns);
  const resultsTimeZone = displayedResultsTimeZoneLabel(display.resultsByWidgetId);
  const runHistory = runs.data?.pages.flatMap((part) => part.items) ?? [];
  const sessionHistory =
    sessions.data?.pages.flatMap((part) => part.items) ?? [session];
  const activity = <DashboardActivitySidebar
    principalId={null} drawerOnly readOnly queryNames={session.display.queryNames}
    expandedGroups={expandedGroups} onExpandedGroupsChange={setExpandedGroups}
    sessions={sessionHistory.map(item => ({ dashboardSessionId: item.sessionId, createdAt: item.createdAt, updatedAt: item.updatedAt,
      ...(item.sessionId === session.sessionId ? { parameterBindings: session.parameterBindings } : {}),
    }))}
    selectedSessionId={session.sessionId} selectedSession={{ definition: { parameters: session.display.parameters } }}
    queryRuns={runHistory} targetQueryRuns={screen.queryRuns}
    queryRunsLoading={runs.isPending} queryRunsError={runs.isError ? "Query runs could not be loaded." : null}
    hasNextQueryRuns={runs.hasNextPage} loadingNextQueryRuns={runs.isFetchingNextPage}
    onLoadMoreQueryRuns={() => void (runs.isError ? runs.refetch() : runs.fetchNextPage())}
    onRefreshQuery={() => undefined} refreshingQueryNames={[]}
    hasNextSessions={sessions.hasNextPage} loadingNextSessions={sessions.isFetchingNextPage}
    sessionsError={sessions.isError ? "History could not be loaded." : null}
    onLoadMoreSessions={() => void (sessions.isError ? sessions.refetch() : sessions.fetchNextPage())}
    openingSession={false} onNewSession={() => undefined}
    onSelectSession={id => { setActivityView("queries"); if (id !== session.sessionId) onSession(id); }}
    view={activityView} onViewChange={setActivityView}
    collapsed={collapsed} onCollapsedChange={setCollapsed}
    mobileOpen={mobileOpen} onMobileOpenChange={setMobileOpen}
  />;
  return (
    <PageViewerFrame title={page.displayTitle} description={page.description}
      actions={<PageViewerActions captureTarget={canvasRef} provenance={{ title: page.displayTitle, description: page.description, inputs, resultsAt, source }} sessionId={session.sessionId} historyOpen={mobileOpen} onHistory={() => setMobileOpen(true)} />}
      aside={activity}>
      <PageViewerContext inputs={inputs} resultsAt={resultsAt} timeZone={resultsTimeZone} />
          {screen.error && (
            <p role="alert" className="mb-3 type-body text-error">
              {screen.error}
              {failedRequest && (
                <Button
                  variant="secondary"
                  className="ml-3"
                  onClick={() => {
                    const request = failedRequest;
                    setScreen((current) => ({
                      ...current,
                      pending: true,
                      error: null,
                    }));
                    void send(request);
                  }}
                >
                  Retry inputs
                </Button>
              )}
            </p>
          )}
          <div ref={canvasRef} className="relative mx-auto w-full max-w-[1380px]">
            <DashboardLayout
              definition={session.display}
              queryTypes={session.display.queryTypes}
              parameters={session.parameterBindings}
              variables={screen.candidate}
              variablesByWidgetId={display.variablesByWidgetId}
              variableRevision={screen.inputOrder}
              variableRevisionsByWidgetId={display.variableRevisionsByWidgetId}
              variableDefinitions={session.display.variables}
              resultsByWidgetId={display.resultsByWidgetId}
              displayedWidgetIds={display.widgetIds}
              refreshingWidgetIds={display.refreshingIds}
              failureWidgetIds={display.failureIds}
              errorMessagesByWidgetId={display.errorMessagesByWidgetId}
              loadingMessagesByWidgetId={display.loadingMessagesByWidgetId}
              onInputVariablesSet={submit}
              onVariablesSet={(changes, order) => {
                if (acceptsChartInput(screenRef.current, order))
                  submit(changes);
              }}
            />
          </div>
    </PageViewerFrame>
  );
}
