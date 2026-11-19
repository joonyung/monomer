import { isLoadErrorRetryable, loadErrorMessage } from "@/lib/api/load-error";
import { useMemo, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { CalendarClock, ChevronRight, LayoutDashboard, RefreshCw, Search } from "lucide-react";
import { dashboardAPI, type DashboardListItem } from "@/api/dashboard";
import { useOptionalWorkspaceRouteContext } from "@/components/auth/TenantRouteContext";
import { useWorkspaceAccess } from "@/components/auth/WorkspaceAccessProvider";
import { sessionCreatedLabel, sessionExactTime } from "@/components/dashboard/session-time";
import { MainLayout } from "@/components/layout/MainLayout";
import { WorkspacePageHeader } from "@/components/layout/WorkspacePageHeader";
import { ActorProvenance } from "@/components/ui/ActorProvenance";
import { Button, buttonClassName } from "@/components/ui/Button";
import { Input } from "@/components/ui/Input";
import { StateBlock } from "@/components/ui/StateBlock";
import { readerTimeZone, scheduleListLabel, scheduleSummary, scheduleZoneLabel } from "@/features/dashboard/schedule-summary";
import { usePrincipalId, useWorkspaceId } from "@/hooks/use-workspace";
import { verifiedClientNames } from "@/lib/agent-clients";
import { Link } from "@/lib/navigation";
import { publicDocsUrls } from "@/lib/public-docs";
import { queryKeys } from "@/lib/query/keys";
import { webPaths } from "@/lib/web-paths";

const ROW_COLUMNS = "lg:grid-cols-[minmax(0,1fr)_136px_168px_88px_16px]";
const FOCUS_OUTLINE = "focus-visible:outline focus-visible:outline-[2px] focus-visible:outline-offset-2 focus-visible:outline-[var(--focus-ring)]";

function pagePlacementLabel(count: number): string {
  if (count === 0) return "—";
  return count === 1 ? "1 page" : `${count} pages`;
}

export default function DashboardListPage() {
  const workspaceId = useWorkspaceId();
  const principalId = usePrincipalId();
  const workspaceAccess = useWorkspaceAccess();
  const canReviewDraft = workspaceAccess.hasRole("EXPLORER");
  const [searchTerm, setSearchTerm] = useState("");
  const query = useQuery({
    queryKey: queryKeys.dashboards.list(workspaceId, principalId ?? ""),
    queryFn: ({ signal }) => dashboardAPI.list(workspaceId, signal),
    enabled: Boolean(principalId),
    retry: false,
  });

  const dashboards = query.data ?? [];
  const normalizedSearchTerm = searchTerm.trim().toLocaleLowerCase();
  const filteredDashboards = useMemo(
    () => (query.data ?? []).filter((dashboard) => {
      if (!normalizedSearchTerm) return true;
      return `${dashboard.title} ${dashboard.description ?? ""}`
        .toLocaleLowerCase()
        .includes(normalizedSearchTerm);
    }),
    [query.data, normalizedSearchTerm],
  );
  const now = new Date();
  const viewerTimeZone = readerTimeZone();

  return (
    <MainLayout>
      <div className="flex h-full min-h-0 flex-col bg-background" style={{ fontFeatureSettings: '"ss01"' }}>
        <WorkspacePageHeader
          section="Analyze"
          title="Dashboards"
          actions={query.isSuccess && dashboards.length > 0 ? (
            <div className="relative w-full sm:w-[280px]">
              <Search className="pointer-events-none absolute left-3 top-1/2 h-3.5 w-3.5 -translate-y-1/2 text-foreground-secondary" aria-hidden="true" />
              <Input
                value={searchTerm}
                onChange={(event) => setSearchTerm(event.target.value)}
                placeholder="Search dashboards"
                aria-label="Search dashboards"
                className="pl-8"
              />
            </div>
          ) : undefined}
        />
        <main className="min-h-0 flex-1 overflow-y-auto px-5 py-6 sm:px-8">
          <section className="mx-auto w-full max-w-[1080px]" aria-label="Dashboard library">
            {query.isPending ? (
              <StateBlock kind="loading" layout="page" title={<span role="heading" aria-level={2}>Dashboards loading</span>} description="Reading dashboards in this workspace…" />
            ) : null}

            {query.isError ? (
              <StateBlock
                kind="error"
                layout="page"
                title={<span role="heading" aria-level={2}>Dashboards unavailable</span>}
                description={loadErrorMessage(query.error, "The dashboard list could not be loaded.")}
                action={isLoadErrorRetryable(query.error) ? (
                  <Button type="button" variant="secondary" onClick={() => void query.refetch()}>
                    <RefreshCw aria-hidden="true" />
                    Retry
                  </Button>
                ) : undefined}
              />
            ) : null}

            {query.isSuccess && dashboards.length === 0 ? <EmptyLibrary /> : null}

            {query.isSuccess && dashboards.length > 0 ? (
              filteredDashboards.length === 0 ? (
                <StateBlock kind="empty" layout="inline" icon={Search} title={`No dashboards match “${searchTerm.trim()}”.`} />
              ) : (
                <>
                  <div aria-hidden="true" className={`hidden gap-4 px-4 pb-2 type-group text-foreground-secondary lg:grid ${ROW_COLUMNS}`}>
                    <span>Dashboard</span>
                    <span>Latest results</span>
                    <span>Schedule</span>
                    <span>In Pages</span>
                    <span />
                  </div>
                  <ul className="divide-y divide-border-light border-y border-border" aria-label="Dashboards">
                    {filteredDashboards.map((dashboard) => (
                      <DashboardRow
                        key={dashboard.dashboardId}
                        dashboard={dashboard}
                        workspaceId={workspaceId}
                        canReviewDraft={canReviewDraft}
                        principalId={principalId ?? null}
                        now={now}
                        viewerTimeZone={viewerTimeZone}
                      />
                    ))}
                  </ul>
                </>
              )
            ) : null}
          </section>
        </main>
      </div>
    </MainLayout>
  );
}

function DashboardRow({ dashboard, workspaceId, canReviewDraft, principalId, now, viewerTimeZone }: {
  dashboard: DashboardListItem;
  workspaceId: string;
  canReviewDraft: boolean;
  principalId: string | null;
  now: Date;
  viewerTimeZone: string;
}) {
  const sampleWorkspace = Boolean(useOptionalWorkspaceRouteContext()?.workspace.sample);
  const latestResults = dashboard.latestResultsAt === null ? null : sessionCreatedLabel(dashboard.latestResultsAt, now.getTime());
  const schedule = scheduleListLabel(dashboard.schedules, viewerTimeZone, now);
  const pages = pagePlacementLabel(dashboard.pagePlacementCount);
  const compactMeta = [
    latestResults ?? "No results yet",
    dashboard.schedules.length > 0 ? schedule : null,
    dashboard.pagePlacementCount > 0 ? `In ${pages}` : null,
  ].filter(Boolean).join(" · ");
  const draftLabel = (
    <>
      <span aria-hidden="true" className="h-1.5 w-1.5 shrink-0 rounded-full bg-accent" />
      Draft has changes
    </>
  );
  return (
    <li className={`relative grid min-h-[72px] grid-cols-[minmax(0,1fr)_16px] items-center gap-4 px-3 py-3.5 transition-colors hover:bg-surface sm:px-4 ${ROW_COLUMNS}`}>
      <div className="min-w-0">
        <div className="flex min-w-0 flex-wrap items-center gap-x-3 gap-y-1">
          <Link
            href={webPaths.dashboard(workspaceId, dashboard.dashboardId)}
            className="min-w-0 break-words type-item sm:truncate text-foreground before:absolute before:inset-0 before:content-[''] focus-visible:outline-none focus-visible:before:outline focus-visible:before:outline-[2px] focus-visible:before:outline-offset-[-3px] focus-visible:before:outline-[var(--focus-ring)]"
            title={dashboard.title}
          >
            {dashboard.title}
          </Link>
          {dashboard.draftHasChanges ? (canReviewDraft ? (
            <Link
              href={webPaths.dashboardBuilder(workspaceId, dashboard.dashboardId)}
              aria-label={`Review draft changes to ${dashboard.title}`}
              className={`relative z-10 inline-flex shrink-0 items-center gap-1.5 rounded-[4px] type-meta text-label underline-offset-4 hover:text-foreground hover:underline ${FOCUS_OUTLINE}`}
            >
              {draftLabel}
            </Link>
          ) : (
            <span className="inline-flex shrink-0 items-center gap-1.5 type-meta text-label">{draftLabel}</span>
          )) : null}
        </div>
        {dashboard.description ? (
          <p className="mt-0.5 truncate type-meta text-foreground-secondary" title={dashboard.description}>{dashboard.description}</p>
        ) : null}
        <p data-testid="dashboard-updated" className="mt-0.5 type-meta tabular-nums text-foreground-secondary">
          <ActorProvenance
            verb="Updated"
            actor={dashboard.updatedBy}
            surface={dashboard.credentialSurface}
            agentApp={dashboard.agentApp}
            principalId={principalId}
            sampleDefinition={sampleWorkspace}
            time={<time dateTime={dashboard.updatedAt} title={sessionExactTime(dashboard.updatedAt)}>{sessionCreatedLabel(dashboard.updatedAt, now.getTime())}</time>}
          />
        </p>
        <p className="mt-1 type-meta tabular-nums text-foreground-secondary lg:hidden">{compactMeta}</p>
      </div>
      <span className="hidden type-meta tabular-nums lg:block">
        <span className="sr-only">Latest results: </span>
        {dashboard.latestResultsAt === null ? (
          <span className="text-foreground-secondary">No results yet</span>
        ) : (
          <time data-testid="dashboard-latest-results" dateTime={dashboard.latestResultsAt} title={sessionExactTime(dashboard.latestResultsAt)} className="text-label">{latestResults}</time>
        )}
      </span>
      <span className="hidden min-w-0 type-meta tabular-nums lg:block">
        <span className="sr-only">Schedule: </span>
        {dashboard.schedules.length === 0 ? (
          <span className="text-foreground-secondary">—</span>
        ) : (
          <span className="inline-flex min-w-0 max-w-full items-center gap-1.5 text-label" title={dashboard.schedules.map((item) => `${scheduleSummary(item.cron)} · ${scheduleZoneLabel(item.timeZone)}`).join("\n")}>
            <CalendarClock className="h-3.5 w-3.5 shrink-0 text-foreground-secondary" aria-hidden="true" />
            <span className="truncate">{schedule}</span>
          </span>
        )}
      </span>
      <span className="hidden type-meta tabular-nums text-foreground-secondary lg:block">
        <span className="sr-only">In Pages: </span>
        {pages}
      </span>
      <ChevronRight className="h-4 w-4 text-foreground-secondary" aria-hidden="true" />
    </li>
  );
}

function EmptyLibrary() {
  return (
    <section aria-labelledby="dashboards-empty-title" className="flex flex-col items-center px-6 py-16 text-center">
      <LayoutDashboard className="h-6 w-6 text-foreground-secondary" aria-hidden="true" />
      <h2 id="dashboards-empty-title" className="mt-3 type-item text-heading">Your agent builds dashboards</h2>
      <p className="mt-1.5 max-w-[520px] type-body text-foreground-secondary">
        Connect {verifiedClientNames()}, then ask for a dashboard from your metrics. It appears here as soon as the agent creates it.
      </p>
      <p className="mt-4 w-full max-w-[520px] rounded-[6px] border border-border px-3.5 py-3 text-left type-body text-label">
        <span className="sr-only">Example request: </span>
        “Build a dashboard of weekly revenue and churn by region from our metrics.”
      </p>
      <div className="mt-4 flex flex-wrap justify-center gap-2">
        <Link href={webPaths.userAgents} className={buttonClassName("primary", "md", FOCUS_OUTLINE)}>
          Connect an agent
        </Link>
        <a href={publicDocsUrls.dashboards} target="_blank" rel="noreferrer" className={buttonClassName("secondary", "md", FOCUS_OUTLINE)}>
          How dashboards work<span className="sr-only"> (opens in a new tab)</span>
        </a>
      </div>
    </section>
  );
}
