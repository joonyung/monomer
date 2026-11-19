import { useState, type ReactNode } from "react";
import { Check, ChevronRight, ExternalLink, FilePen, LayoutDashboard } from "lucide-react";
import type { DashboardListItem } from "@/api/dashboard";
import type { SemanticModelResponse } from "@/api/semantic-model";
import { useWorkspaceRouteContext } from "@/components/auth/TenantRouteContext";
import { formatRefreshTime } from "@/components/catalog/catalogCopy";
import { useWorkspaceOwnersQuery } from "@/components/settings/workspaceSettingsQueries";
import { ActorProvenance } from "@/components/ui/ActorProvenance";
import { CopyButton } from "@/components/settings/SettingsPrimitives";
import { buttonClassName } from "@/components/ui/Button";
import { WorkStatus } from "@/components/ui/WorkStatus";
import { hasWorkspaceRole } from "@/lib/auth/roles";
import { sampleDashboardsInSeedOrder } from "@/lib/auth/sample-workspace";
import { verifiedClientNames } from "@/lib/agent-clients";
import { useAuthStore } from "@/lib/auth/store";
import type { WorkspaceOwner, WorkspaceRole } from "@/lib/auth/types";
import { catalogHref } from "@/lib/catalog/catalog-links";
import { Link } from "@/lib/navigation";
import type { Metric } from "@/lib/types/metric";
import { webPaths } from "@/lib/web-paths";
import { exploreRunTitle } from "@/pages/explore/run-presentation";
import { LiveBoard, RecentActivity } from "./OverviewActivity";
import {
  askOwnersLabel, attentionItems, currentStep, generatedNames, getStartedHiddenKey, getStartedSteps, hasUsedAgentConnection,
  readGetStartedHidden, writeGetStartedHidden,
  type AttentionItem, type GetStartedStep,
} from "./overview-model";
import { useActivity } from "./use-activity";
import { useOverviewSources, type OverviewSources } from "./use-overview-sources";
import "./style.css";
import { SampleWorkspaceAction } from "./SampleWorkspaceAction";

function runHref(workspaceId: string, runId: string): string {
  return `${webPaths.explore(workspaceId)}?run=${encodeURIComponent(runId)}`;
}

function AttentionRow({ icon, children, action }: { icon: ReactNode; children: ReactNode; action: ReactNode }) {
  return <li className="ov-attention-row"><span className="ov-attention-icon">{icon}</span><span className="ov-attention-text">{children}</span><span className="ov-attention-action">{action}</span></li>;
}

function NeedsAttention({ workspaceId, principalId, items, canFixConnectors, owners, incomplete, onRetry, metrics, models }: {
  workspaceId: string;
  principalId: string | null;
  items: AttentionItem[];
  canFixConnectors: boolean;
  owners: WorkspaceOwner[] | null;
  incomplete: boolean;
  onRetry: () => void;
  metrics: Metric[];
  models: SemanticModelResponse[];
}) {
  if (items.length === 0 && !incomplete) return null;
  return <section className="ov-attention" aria-labelledby="overview-attention-title">
    <h2 id="overview-attention-title" className="ov-group-label">Needs attention</h2>
    <ul>{items.map((item) => {
      if (item.kind === "connector") {
        return <AttentionRow key={item.key} icon={<WorkStatus state="FAILED" labelDisplay="accessible-name" size="md" />}
          action={canFixConnectors
            ? <Link href={`${webPaths.connectors(workspaceId)}?connector=${encodeURIComponent(item.connectorId)}`} className="ov-link">Fix connector</Link>
            : <span className="ov-meta">{askOwnersLabel(owners)}</span>}>
          <span className="ov-attention-name">{item.connectorName}</span> couldn’t refresh · {item.cause} · <time dateTime={item.at}>{formatRefreshTime(item.at)}</time>
        </AttentionRow>;
      }
      if (item.kind === "draft") {
        return <AttentionRow key={item.key} icon={<FilePen className="h-4 w-4 text-foreground-secondary" aria-hidden="true" />}
          action={<Link href={webPaths.dashboardBuilder(workspaceId, item.dashboardId)} className="ov-link" aria-label={`Review draft changes to ${item.title}`}>Review draft</Link>}>
          <span className="ov-attention-name">{item.title}</span> has draft changes waiting to be applied
          {item.editedBy ? <> · <ActorProvenance verb={null} actor={item.editedBy} surface={item.editSurface} agentApp={item.editAgentApp} principalId={principalId}
            time={item.editedAt ? <time dateTime={item.editedAt}>{formatRefreshTime(item.editedAt)}</time> : null} /></> : null}
        </AttentionRow>;
      }
      return <AttentionRow key={item.key} icon={<WorkStatus state="FAILED" labelDisplay="accessible-name" size="md" />}
        action={<Link href={runHref(workspaceId, item.run.runId)} className="ov-link">Open in Explore</Link>}>
        <span className="ov-attention-name">{exploreRunTitle(item.run, metrics, models)}</span> failed · {item.cause} · <time dateTime={item.at}>{formatRefreshTime(item.at)}</time>
      </AttentionRow>;
    })}</ul>
    {incomplete && <p className="ov-meta ov-attention-incomplete" role="status">Some checks couldn’t load. <button type="button" className="ov-link" onClick={onRetry}>Retry</button></p>}
  </section>;
}

interface StepAction { label: string; href: string; external: boolean; hint: string | null }
const SAMPLE_AGENT_PROMPT = "Build a dashboard of weekly active accounts by plan from the shared metrics.";

function stepAction(step: GetStartedStep, workspaceId: string, sources: OverviewSources): StepAction {
  const connectors = sources.connectors.connectors;
  const summaries = sources.summaries.data ?? [];
  switch (step.key) {
    case "connect":
      return { label: "Open Connectors", href: webPaths.connectors(workspaceId), external: false, hint: "Add the warehouse this workspace reads from." };
    case "tables": {
      const empty = connectors.find((connector) => (summaries.find((summary) => summary.connectorId === connector.id)?.tableCount ?? 0) === 0) ?? connectors[0];
      return { label: "Choose tables", href: catalogHref(workspaceId, { connector: empty.id, choose: true }), external: false, hint: null };
    }
    case "model":
      return { label: "Open Catalog", href: webPaths.catalog(workspaceId), external: false, hint: "Start from a table in Catalog — its columns become fields." };
    case "metric": {
      const model = sources.models.data?.[0];
      return model
        ? { label: "Open model", href: webPaths.semanticModel(workspaceId, model.id), external: false, hint: "Choose Create metric on one of its measures." }
        : { label: "Open Semantics", href: webPaths.semantics(workspaceId), external: false, hint: null };
    }
    case "question":
      return { label: "Open Explore", href: webPaths.explore(workspaceId), external: false, hint: null };
    case "agent":
      return { label: "Connect an agent", href: webPaths.userAgents, external: false, hint: `Work with this Workspace from ${verifiedClientNames()}.` };
  }
}

function GetStarted({ workspaceId, principalId, role, sources, owners }: {
  workspaceId: string;
  principalId: string;
  role: WorkspaceRole | null;
  sources: OverviewSources;
  owners: WorkspaceOwner[] | null;
}) {
  const { workspace, organizationId } = useWorkspaceRouteContext();
  const membership = useAuthStore((state) => state.organizationMemberships.find((candidate) => candidate.organization.id === organizationId) ?? null);
  const hiddenKey = getStartedHiddenKey(workspaceId, principalId);
  const [hidden, setHidden] = useState(() => readGetStartedHidden(hiddenKey));
  const { summaries, models, metrics, myRuns, agentConnections } = sources;
  if (hidden || sources.connectors.loading || sources.connectors.error || !summaries.data || !models.data || !metrics.data
    || !myRuns.data || !agentConnections.data) return null;
  const steps = getStartedSteps({
    connectors: sources.connectors.connectors, summaries: summaries.data, models: models.data,
    metrics: metrics.data, myRuns: myRuns.data, agentUsed: hasUsedAgentConnection(agentConnections.data),
  });
  const current = currentStep(steps);
  if (!current) return null;
  const doneCount = steps.filter((step) => step.done).length;
  const names = generatedNames(workspace.name, membership?.organization.displayName ?? null);
  const renameHref = names.workspace && role === "OWNER" ? webPaths.workspaceSettings(workspaceId)
    : names.organization && membership?.role === "ADMIN" ? webPaths.organizationSettings(organizationId) : null;
  const hide = () => { writeGetStartedHidden(hiddenKey); setHidden(true); };
  return <section className="ov-start" aria-labelledby="overview-start-title">
    <div className="ov-section-heading"><h2 id="overview-start-title">Get started</h2><span className="ov-meta">{doneCount} of {steps.length} · <button type="button" className="ov-link" onClick={hide}>Hide</button></span></div>
    <ol className="ov-steps">{steps.map((step) => {
      const isCurrent = step === current;
      const allowed = hasWorkspaceRole(role ? { granted: true, organizationId, role } : null, step.role);
      const action = isCurrent ? stepAction(step, workspaceId, sources) : null;
      const startChoices = isCurrent && step.key === "connect" && membership?.role === "ADMIN" && !workspace.sample;
      return <li key={step.key} data-step={step.key} aria-current={isCurrent ? "step" : undefined} className={isCurrent ? "ov-step ov-step-current" : "ov-step"}>
        <span className={step.done ? "ov-step-mark ov-step-done" : "ov-step-mark"} aria-hidden="true">{step.done && <Check className="h-3.5 w-3.5" />}</span>
        {startChoices ? <div className="ov-start-choices">
          <div className="ov-start-choice">
            {allowed ? <Link href={webPaths.connectors(workspaceId)} className={buttonClassName("ghost", "sm", "ov-start-choice-action")}>Connect your warehouse</Link>
              : <span className="ov-step-title">Connect your warehouse</span>}
            <p className="ov-meta">Read your own tables with read-only warehouse access.</p>
            {!allowed ? <span className="ov-meta">{askOwnersLabel(owners)}</span> : null}
          </div>
          <div className="ov-start-choice">
            <SampleWorkspaceAction key={workspaceId} organizationId={organizationId} principalId={principalId} />
            <p className="ov-meta">A ready workspace with a fictional SaaS company&apos;s metrics and dashboards. No setup.</p>
          </div>
        </div> : <div className="ov-step-body">
          <span className={step.done || isCurrent ? "ov-step-title" : "ov-step-title ov-step-pending"}>{step.title}{step.done && <span className="sr-only"> (done)</span>}</span>
          {step.done && step.evidence && <span className="ov-meta">{step.evidence}</span>}
          {action?.hint && allowed && <span className="ov-meta">{action.hint}</span>}
          {step.key === "agent" && !step.done && workspace.sample && <div className="ov-agent-prompt">
            <span className="ov-meta">{SAMPLE_AGENT_PROMPT}</span>
            <CopyButton value={SAMPLE_AGENT_PROMPT} label="Copy example prompt" copiedAnnouncement="Example prompt copied" failedAnnouncement="Couldn't copy. Select the prompt and copy it." />
          </div>}
        </div>}
        {!startChoices && action && (allowed
          ? action.external
            ? <a href={action.href} target="_blank" rel="noreferrer" className={buttonClassName("primary", "sm", "ov-step-action")}>
                {action.label}<ExternalLink className="h-4 w-4" aria-hidden="true" /><span className="sr-only"> (opens in a new tab)</span>
              </a>
            : <Link href={action.href} className={buttonClassName("primary", "sm", "ov-step-action")}>{action.label}</Link>
          : <span className="ov-meta ov-step-ask">{askOwnersLabel(owners)}</span>)}
      </li>;
    })}</ol>
    {(names.workspace || names.organization) && <p className="ov-meta ov-start-names">
      “{workspace.name}” in “{membership?.organization.displayName ?? "your organization"}”{renameHref && <> · <Link href={renameHref} className="ov-link">Rename</Link></>}
    </p>}
  </section>;
}

function SampleDashboards({ workspaceId, dashboards }: { workspaceId: string; dashboards: DashboardListItem[] }) {
  if (dashboards.length === 0) return null;
  return <section aria-labelledby="overview-sample-dashboards-title">
    <div className="ov-section-heading">
      <h2 id="overview-sample-dashboards-title">Start with a dashboard</h2>
      <Link href={webPaths.dashboards(workspaceId)} className="ov-link">All dashboards</Link>
    </div>
    <ul className="ov-run-list">{sampleDashboardsInSeedOrder(dashboards).map((dashboard) => <li key={dashboard.dashboardId}>
      <Link href={webPaths.dashboard(workspaceId, dashboard.dashboardId)} className="ov-recent-link">
        <span className="ov-resource-icon" aria-hidden="true"><LayoutDashboard className="h-4 w-4" /></span>
        <span className="ov-sample-dashboard"><span className="ov-resource-title">{dashboard.title}</span>{dashboard.description ? <span className="ov-meta">{dashboard.description}</span> : null}</span>
        <ChevronRight className="h-3.5 w-3.5" aria-hidden="true" />
      </Link>
    </li>)}</ul>
  </section>;
}

export function OverviewHome({ workspaceId, principalId }: { workspaceId: string; principalId: string }) {
  const { access, workspace } = useWorkspaceRouteContext();
  const role = access.role;
  const sources = useOverviewSources(workspaceId, principalId);
  const activity = useActivity(workspaceId, principalId);
  const connectors = sources.connectors.connectors;
  const summaries = sources.summaries.data ?? [];
  const dashboards = sources.dashboards.data ?? [];
  const myRuns = sources.myRuns.data ?? [];
  const metrics = sources.metrics.data ?? [];
  const models = sources.models.data ?? [];
  const attention = attentionItems({ connectors, summaries, dashboards, myRuns, now: activity.now });
  const isOwner = role === "OWNER";
  const steps = getStartedSteps({ connectors, summaries, models, metrics, myRuns, agentUsed: hasUsedAgentConnection(sources.agentConnections.data ?? []) });
  const blockedStep = currentStep(steps);
  const stepsLoaded = !sources.connectors.loading && sources.summaries.isSuccess && sources.models.isSuccess && sources.metrics.isSuccess
    && sources.myRuns.isSuccess && sources.agentConnections.isSuccess;
  const needsOwners = !isOwner && (attention.some((item) => item.kind === "connector")
    || (stepsLoaded && blockedStep !== null && !hasWorkspaceRole(access, blockedStep.role)));
  const owners = useWorkspaceOwnersQuery(workspaceId, needsOwners);
  const attentionIncomplete = Boolean(sources.connectors.error) || sources.summaries.isError || sources.dashboards.isError || sources.myRuns.isError;
  const retryAttention = () => {
    if (sources.connectors.error) sources.connectors.retry();
    for (const query of [sources.summaries, sources.dashboards, sources.myRuns]) if (query.isError) void query.refetch();
  };
  return <section className="overview-activity ov-main" aria-label="Workspace overview">
    <NeedsAttention workspaceId={workspaceId} principalId={principalId} items={attention} canFixConnectors={isOwner} owners={owners.data ?? null}
      incomplete={attentionIncomplete} onRetry={retryAttention} metrics={metrics} models={models} />
    <LiveBoard activity={activity} />
    {workspace.sample ? <SampleDashboards workspaceId={workspaceId} dashboards={dashboards} /> : null}
    <GetStarted workspaceId={workspaceId} principalId={principalId} role={role} sources={sources} owners={owners.data ?? null} />
    <RecentActivity activity={activity} workspaceId={workspaceId} principalId={principalId} sampleWorkspace={Boolean(workspace.sample)} />
  </section>;
}
