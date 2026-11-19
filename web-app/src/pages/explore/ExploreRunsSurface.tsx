import { RunsRail, RunsStatusDot } from "@/components/runs";
import type { RunPresentation } from "@/components/runs";
import type { ExploreRun } from "@/api/explore";
import type { Connector } from "@/lib/types/connector";
import type { Metric } from "@/lib/types/metric";
import type { SemanticModelResponse } from "@/api/semantic-model";
import { exploreRunSubtitle, exploreRunTitle } from "./run-presentation";

export { RunsStatusDot };

export interface ExploreRunsRailProps {
  className: string;
  runs: ExploreRun[];
  principalId: string | null;
  connectors: Connector[] | null;
  metrics: Metric[];
  semanticModels: SemanticModelResponse[];
  newlyAddedRunId: string | null;
  selectedRunId: string | null;
  collapsed: boolean;
  onCollapsedChange?: (collapsed: boolean) => void;
  onInspect: (runId: string) => void;
  onClose?: () => void;
}

export function ExploreRunsRail({
  className,
  runs,
  principalId,
  connectors,
  metrics,
  semanticModels,
  newlyAddedRunId,
  selectedRunId,
  collapsed,
  onCollapsedChange,
  onInspect,
  onClose,
}: ExploreRunsRailProps) {
  return (
    <RunsRail
      className={className}
      runs={runs.map((run) => withoutOwnStarter(toExploreRunPresentation(run, connectors, metrics, semanticModels), principalId))}
      principalId={principalId}
      newlyAddedRunId={newlyAddedRunId}
      selectedRunId={selectedRunId}
      collapsed={collapsed}
      onCollapsedChange={onCollapsedChange}
      onInspect={onInspect}
      onClose={onClose}
      ariaLabel="Explore runs"
      emptyState={{ title: "No runs yet", description: "Runs you start appear here with their status and results." }}
    />
  );
}

/**
 * The rail lists only the viewer's runs, so a row names the starter only when an agent acted for them;
 * "You" on every row would spend the width the subtitle needs to tell runs apart.
 */
export function withoutOwnStarter(run: RunPresentation, principalId: string | null): RunPresentation {
  const own = principalId !== null && run.startedBy?.id === principalId;
  return own && run.credentialSurface !== "AGENT" ? { ...run, startedBy: null } : run;
}

export function toExploreRunPresentation(
  run: ExploreRun,
  connectors: Connector[] | null,
  metrics: Metric[],
  semanticModels: SemanticModelResponse[],
): RunPresentation {
  const totalRowCount = run.resultMetadata ? Number(run.resultMetadata.rowCount) : null;
  return {
    id: run.runId,
    title: exploreRunTitle(run, metrics, semanticModels),
    subtitle: exploreRunSubtitle(run, connectors, semanticModels),
    state: run.state,
    createdAt: run.createdAt,
    updatedAt: run.updatedAt,
    resultAvailable: run.resultAvailable ?? Boolean(run.result),
    failureMessage: run.state === "FAILED" ? run.failureDetail || failureLabel(run.failureCode) : null,
    isCurrentTarget: false,
    result: totalRowCount != null && Number.isFinite(totalRowCount)
      ? { columns: [], rows: [], returnedRowCount: 0, totalRowCount }
      : null,
    startedBy: run.startedBy,
    credentialSurface: run.credentialSurface,
    agentApp: run.agentApp,
  };
}

export function failureLabel(code: string | null): string {
  if (code === "EXECUTION_ORPHANED") return "The execution stopped reporting progress and was closed.";
  if (code === "CANCELLED_BY_ACTOR") return "This run was cancelled.";
  return "The warehouse execution failed before a result was available.";
}
