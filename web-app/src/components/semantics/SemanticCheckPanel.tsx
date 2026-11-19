import { useCallback, useEffect, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { CircleAlert, CircleCheck, TriangleAlert } from "lucide-react";
import { checkSemanticDefinition, isSemanticCheckWarning, semanticCheckProblemMessage, semanticCheckResultSchema, type SemanticCheckDraft, type SemanticCheckProblem, type SemanticCheckResult } from "@/api/semantic-check";
import { StateBlock } from "@/components/ui/StateBlock";
import { SEMANTIC_LINK_CLASS } from "@/components/resource-detail/semantic-links";
import { semanticModelAPI } from "@/api/semantic-model";
import { metricAPI } from "@/api/metric";
import { Link } from "@/lib/navigation";
import { webPaths } from "@/lib/web-paths";
import { Button } from "@/components/ui/Button";
import { usePrincipalId, useWorkspaceId } from "@/hooks/use-workspace";
import { partitionFilterCondition } from "./source-partition";
import { queryKeys } from "@/lib/query/keys";

const CHECK_DEBOUNCE_MS = 600;

export interface SemanticCheckState {
  result: SemanticCheckResult | null;
  checking: boolean;
  failed: boolean;
  // Runs the check for the current draft now, e.g. right before saving; never blocks the save.
  checkNow: () => void;
}

/** Checks the draft against the saved definitions after typing pauses. `null` means the draft is incomplete. */
export function useSemanticCheck(draft: SemanticCheckDraft | null): SemanticCheckState {
  const workspaceId = useWorkspaceId();
  const principalId = usePrincipalId();
  const draftKey = draft ? JSON.stringify(draft) : "";
  const [checkedKey, setCheckedKey] = useState(draftKey);

  useEffect(() => {
    const timer = window.setTimeout(() => setCheckedKey(draftKey), CHECK_DEBOUNCE_MS);
    return () => window.clearTimeout(timer);
  }, [draftKey]);

  const query = useQuery({
    queryKey: queryKeys.semanticCheck(workspaceId ?? "", principalId ?? "", checkedKey),
    queryFn: ({ signal }) => checkSemanticDefinition(workspaceId, JSON.parse(checkedKey) as SemanticCheckDraft, signal),
    enabled: Boolean(workspaceId && principalId && checkedKey),
    placeholderData: (previous) => previous,
    retry: false,
    staleTime: 0,
  });
  const { refetch } = query;
  const checkNow = useCallback(() => {
    if (!draftKey || !workspaceId || !principalId) return;
    if (checkedKey === draftKey) void refetch();
    else setCheckedKey(draftKey);
  }, [checkedKey, draftKey, principalId, refetch, workspaceId]);

  return {
    result: checkedKey ? query.data ?? null : null,
    checking: query.isFetching || checkedKey !== draftKey,
    failed: query.isError,
    checkNow,
  };
}

export interface SemanticCheckPanelProps {
  mode: "read" | "edit";
  state: SemanticCheckState;
  connectors: ReadonlyArray<{ id: string; name: string }>;
  incompleteMessage: string;
  // Moves focus to the editor control for a definition path such as `measure` or `measures[2]`; the
  // problem's code can pick a control within that element.
  onGoToField: (field: string, code: SemanticCheckProblem["code"]) => void;
  canGoToField: (field: string) => boolean;
}

export function SemanticCheckPanel({ mode, state, connectors, incompleteMessage, onGoToField, canGoToField }: SemanticCheckPanelProps) {
  const parsed = state.result == null ? null : semanticCheckResultSchema.safeParse(state.result);
  const unavailable = state.failed || parsed?.success === false;
  const result = !unavailable && parsed?.success ? parsed.data : null;
  const connectorName = (id: string) => connectors.find((connector) => connector.id === id)?.name ?? "an unknown connector";
  const ownProblems = result?.problems.filter((problem) => problem.definition === null) ?? [];
  const otherProblems = result?.problems.filter((problem) => problem.definition !== null) ?? [];
  const { definitionTarget, models } = useDefinitionTargets();
  const runsOnLabel = (connectorId: string) => {
    const conditions = [...new Set((result?.partitionFilters ?? []).filter((filter) => filter.connectorId === connectorId).map((filter) => partitionFilterCondition(filter, models)))];
    return [connectorName(connectorId), ...conditions].join(" · ");
  };
  return (
    <div role={mode === "edit" ? "region" : undefined} aria-label={mode === "read" ? "Definition status" : undefined} aria-labelledby={mode === "edit" ? "semantic-check-heading" : undefined} aria-busy={state.checking} className={mode === "read" ? "min-w-0 space-y-3" : "min-w-0 rounded-[6px] border border-border bg-background p-4"}>
      {mode === "edit" && <div className="flex items-baseline justify-between gap-3">
        <h2 id="semantic-check-heading" className="type-item text-heading">Check</h2>
        <span role="status" className="type-status text-foreground-secondary">
          {state.checking ? "Checking…" : result && !result.ok ? problemCount(result) : ""}
        </span>
      </div>}
      <div className={mode === "read" ? "space-y-3" : "mt-3 space-y-3"}>
        {mode === "read" && state.checking && <p role="status" className="type-meta text-foreground-secondary">Checking…</p>}
        {!result && !unavailable ? (
          <p className="type-meta text-foreground-secondary">{state.checking ? "Checking against the saved definitions…" : incompleteMessage}</p>
        ) : null}
        {unavailable ? (
          <StateBlock kind="error" layout="inline" title="Check unavailable" description={mode === "edit" ? "You can still save." : "The definition is still available. Try the check again."} action={<Button variant="secondary" size="sm" pending={state.checking} onClick={state.checkNow}>Retry</Button>} />
        ) : null}
        {result?.ok ? (
          <p className="flex items-start gap-2 type-meta text-foreground">
            <CircleCheck className="mt-0.5 h-4 w-4 flex-none text-success-text" aria-hidden="true" />
            <span>Runs on {result.runsOn.map(runsOnLabel).join(", ")}</span>
          </p>
        ) : null}
        {result && !result.ok && result.runsOn.length > 0 ? (
          <p className="type-meta text-foreground-secondary">Runs on {result.runsOn.map(runsOnLabel).join(", ")}</p>
        ) : null}
        {ownProblems.length > 0 ? (
          <ul className="space-y-2">
            {ownProblems.map((problem, index) => (
              <li key={`${problem.code}-${problem.field ?? ""}-${problem.connectorId ?? ""}-${index}`} className="flex items-start gap-2 type-meta">
                <ProblemIcon problem={problem} />
                <span className="min-w-0">
                  {problem.connectorId ? <span className="text-foreground-secondary">{connectorName(problem.connectorId)}: </span> : null}
                  <span className="text-foreground">{semanticCheckProblemMessage(problem)}</span>
                  {problem.field && canGoToField(problem.field) ? (
                    <>
                      {" "}
                      <button type="button" className="text-foreground underline focus-visible:outline focus-visible:outline-[2px] focus-visible:outline-focus-ring" onClick={() => onGoToField(problem.field as string, problem.code)}>Go to field</button>
                    </>
                  ) : null}
                </span>
              </li>
            ))}
          </ul>
        ) : null}
        {otherProblems.length > 0 ? (
          <section aria-label="Problems in other definitions" className="space-y-2">
            <h3 className="type-group text-label">Other definitions <span className="tabular-nums">{otherProblems.length}</span></h3>
            <ul className="space-y-2.5">
              {otherProblems.map((problem, index) => {
                const definition = problem.definition!;
                const target = definitionTarget(definition);
                return <li key={`${definition.kind}-${definition.name}-${index}`} className="flex items-start gap-2 type-meta">
                  <ProblemIcon problem={problem} />
                  <span className="min-w-0 space-y-0.5">
                    <span className="block">
                      {target.href ? <Link href={target.href} className={`text-foreground ${SEMANTIC_LINK_CLASS}`}>{target.label}</Link> : <span className="text-foreground">{target.label}</span>}
                      <span className="text-foreground-secondary"> · {definition.kind === "METRIC" ? "Metric" : "Model"}</span>
                    </span>
                    <span className="block break-words text-foreground-secondary">{semanticCheckProblemMessage(problem)}</span>
                  </span>
                </li>;
              })}
            </ul>
          </section>
        ) : null}
        {result?.preview ? <details><summary className="type-control cursor-pointer text-foreground underline underline-offset-4">Show SQL</summary><pre aria-label="SQL preview" className="mt-3 max-h-64 overflow-y-auto whitespace-pre-wrap rounded-[6px] border border-border-light bg-surface p-3 type-mono text-foreground [overflow-wrap:anywhere]">{result.preview.sql}</pre></details> : null}
        {mode === "edit" && result && !result.ok && <p className="type-meta text-foreground-secondary">You can still save with problems.</p>}
      </div>
    </div>
  );
}

/** A warning still runs, so it reads as a caution rather than an error. */
function ProblemIcon({ problem }: { problem: SemanticCheckProblem }) {
  return isSemanticCheckWarning(problem)
    ? <><TriangleAlert className="mt-0.5 h-4 w-4 flex-none text-warning" aria-hidden="true" /><span className="sr-only">Warning: </span></>
    : <CircleAlert className="mt-0.5 h-4 w-4 flex-none text-error" aria-hidden="true" />;
}

/** Where a problem's other definition lives, by its saved name; a name no longer saved stays as plain text. */
function useDefinitionTargets() {
  const workspaceId = useWorkspaceId();
  const principalId = usePrincipalId();
  const models = useQuery({ queryKey: queryKeys.semanticModels.all(workspaceId ?? "", principalId ?? ""), queryFn: () => semanticModelAPI.list(workspaceId), enabled: Boolean(workspaceId && principalId) });
  const metrics = useQuery({ queryKey: queryKeys.metrics.all(workspaceId ?? "", principalId ?? ""), queryFn: () => metricAPI.list(workspaceId), enabled: Boolean(workspaceId && principalId) });
  const definitionTarget = (definition: { kind: "SEMANTIC_MODEL" | "METRIC"; name: string }): { label: string; href: string | null } => {
    if (definition.kind === "SEMANTIC_MODEL") {
      const model = models.data?.find((candidate) => candidate.name === definition.name);
      return { label: model?.label || definition.name, href: model ? webPaths.semanticModel(workspaceId, model.id) : null };
    }
    const metric = metrics.data?.find((candidate) => candidate.name === definition.name);
    return { label: metric?.label || definition.name, href: metric ? webPaths.metric(workspaceId, metric.id) : null };
  };
  return { definitionTarget, models: models.data ?? [] };
}

function problemCount(result: SemanticCheckResult): string {
  const count = result.problems.length;
  if (count === 0) return "Doesn't run on any connector";
  return `${count} ${count === 1 ? "problem" : "problems"}`;
}
