import { draftStatusPathOf, resultPathOf, type DraftBody, type DraftStatusBody, type SessionBody, type ShowTarget } from "./contract";
import {
  adoptBundles,
  bundleFromReadyResult,
  draftLayoutContent,
  jsonValuesEqual,
  reconcileBundles,
  runningQueryCount,
  runPathsInLayoutOrder,
  sessionLayoutContent,
  type DashboardLayoutContent,
  type ResultEntry,
  type RunState,
  type TargetRun,
  type WidgetBundle,
} from "./presentation";
import type { PollOutcome } from "./polling";
import { ResourceReadError, ViewContractMismatchError, type ResourceReader } from "./reader";
import { namedResultFromAgent } from "./result";
import type { DashboardJsonValue } from "@/api/dashboard";

export interface SourcePresentation {
  content: DashboardLayoutContent;
  runningQueries: number;
}

export interface DashboardSource {
  load(): Promise<void>;
  poll(): Promise<PollOutcome>;
  present(): SourcePresentation | null;
  readonly needsPolling: boolean;
}

export interface SourceContext {
  reader: ResourceReader;
  results: Map<string, ResultEntry>;
  resultAttempts: Map<string, number>;
  onUpdate(): void;
  isCancelled(): boolean;
}

export const RESULT_READ_ATTEMPTS = 3;

class SourceCancelledError extends Error {
  constructor() {
    super("The view stopped reading.");
    this.name = "SourceCancelledError";
  }
}

export function isSourceCancelled(error: unknown): boolean {
  return error instanceof SourceCancelledError;
}

function checkCancelled(context: SourceContext): void {
  if (context.isCancelled()) throw new SourceCancelledError();
}

export const READ_CONCURRENCY = 4;

// Starts tasks in the given order with at most `limit` in flight. The first failure stops new
// starts and is rethrown once the started tasks settle.
export async function runInOrderBounded<Item>(items: readonly Item[], limit: number, task: (item: Item) => Promise<void>): Promise<void> {
  const failures: unknown[] = [];
  let next = 0;
  const lane = async () => {
    while (failures.length === 0 && next < items.length) {
      const item = items[next];
      next += 1;
      try {
        await task(item);
      } catch (error) {
        failures.push(error);
      }
    }
  };
  await Promise.all(Array.from({ length: Math.min(limit, items.length) }, lane));
  if (failures.length > 0) throw failures[0];
}

// A Problem other than a timeout or rate limit in the 4xx range, or a result that fails validation,
// will not change on retry. Everything else may succeed on a later read.
function transientReadFailure(error: unknown): boolean {
  if (!(error instanceof ResourceReadError)) return false;
  const status = error.problem?.status ?? null;
  return status === null || status >= 500 || status === 408 || status === 429;
}

// Results are immutable per run, so each is read once. A result that cannot be read or shown closes
// only its widgets; a transient failure leaves it pending for a later poll, up to three attempts.
// A contract mismatch still stops the whole view.
async function loadResults(context: SourceContext, runPaths: readonly string[]): Promise<boolean> {
  const pending = runPaths.filter((runPath) => !context.results.has(runPath));
  let landed = false;
  await runInOrderBounded(pending, READ_CONCURRENCY, async (runPath) => {
    checkCancelled(context);
    let entry: ResultEntry | null;
    try {
      entry = { kind: "loaded", result: namedResultFromAgent(runPath, await context.reader.queryResult(resultPathOf(runPath))) };
    } catch (error) {
      if (error instanceof ViewContractMismatchError) throw error;
      const attempts = (context.resultAttempts.get(runPath) ?? 0) + 1;
      context.resultAttempts.set(runPath, attempts);
      entry = transientReadFailure(error) && attempts < RESULT_READ_ATTEMPTS ? null : { kind: "unavailable" };
    }
    checkCancelled(context);
    if (!entry) return;
    context.results.set(runPath, entry);
    context.resultAttempts.delete(runPath);
    landed = true;
    context.onUpdate();
  });
  return landed;
}

export function createDraftSource(target: Extract<ShowTarget, { kind: "DRAFT" }>, context: SourceContext): DashboardSource {
  let revision: string | null = null;
  let draft: DraftBody | null = null;
  let status: DraftStatusBody | null = null;
  let readyBundles: Record<string, WidgetBundle> = {};
  let adopted: Record<string, WidgetBundle> = {};
  const failureDetails = new Map<string, string | null>();

  const targets = (): Record<string, TargetRun> => {
    const entries: [string, string, RunState | null][] = status
      ? Object.entries(status.targetQueryRuns).map(([name, run]) => [name, run.path, run.state])
      : Object.entries(draft?.targetQueryRuns ?? {}).map(([name, path]) => [name, path, null]);
    return Object.fromEntries(entries.map(([name, path, state]) => [name, { path, state, failureDetail: failureDetails.get(path) ?? null, unavailable: false }]));
  };

  async function readDraft(): Promise<void> {
    const next = await context.reader.draft(target.path);
    checkCancelled(context);
    revision = next.revision;
    draft = next.body;
    if (!status) readyBundles = reconcileBundles(readyBundles, Object.fromEntries(Object.entries(next.body.readyResults).map(([widgetId, ready]) => [widgetId, bundleFromReadyResult(ready)])));
    context.onUpdate();
  }

  async function readFailureDetails(): Promise<void> {
    for (const run of Object.values(status?.targetQueryRuns ?? {})) {
      if ((run.state !== "FAILED" && run.state !== "CANCELLED") || failureDetails.has(run.path)) continue;
      checkCancelled(context);
      let detail: string | null = null;
      try {
        const body = await context.reader.queryRun(run.path);
        detail = body.state === "FAILED" ? body.failureDetail ?? null : null;
      } catch (error) {
        if (error instanceof ViewContractMismatchError) throw error;
      }
      checkCancelled(context);
      failureDetails.set(run.path, detail);
      context.onUpdate();
    }
  }

  async function sync(): Promise<boolean> {
    const next = await context.reader.draftStatus(draftStatusPathOf(target.path));
    checkCancelled(context);
    let changed = false;
    if (next.draftRevision !== revision) {
      await readDraft();
      changed = true;
    }
    if (!status || !jsonValuesEqual(status as unknown as DashboardJsonValue, next as unknown as DashboardJsonValue)) {
      status = next;
      readyBundles = reconcileBundles(readyBundles, Object.fromEntries(Object.entries(next.readyResults).map(([widgetId, ready]) => [widgetId, bundleFromReadyResult(ready)])));
      changed = true;
      context.onUpdate();
    }
    await readFailureDetails();
    const definition = draft!.definition;
    const loaded = await loadResults(context, runPathsInLayoutOrder(definition, (widgetId, names) => names.map((name) => readyBundles[widgetId]?.queryRuns[name])));
    pruneUnreferencedRuns();
    return changed || loaded;
  }

  // A long editing session produces many runs; only those the Draft still points at are kept.
  function pruneUnreferencedRuns(): void {
    const referenced = new Set([
      ...[...Object.values(readyBundles), ...Object.values(adopted)].flatMap((bundle) => Object.values(bundle.queryRuns)),
      ...Object.values(targets()).map((target) => target.path),
    ]);
    for (const store of [context.results, context.resultAttempts, failureDetails]) {
      for (const runPath of store.keys()) if (!referenced.has(runPath)) store.delete(runPath);
    }
  }

  return {
    needsPolling: true,
    async load() {
      await readDraft();
      await sync();
    },
    async poll() {
      return await sync() ? "changed" : "unchanged";
    },
    present() {
      if (!draft || revision === null) return null;
      adopted = adoptBundles(draft.definition, readyBundles, context.results, adopted);
      const currentTargets = targets();
      return {
        content: draftLayoutContent({
          definition: draft.definition,
          parameterBindings: draft.parameterBindings,
          variables: draft.variables,
          revision: Number(revision),
          readyBundles,
          adoptedBundles: adopted,
          targets: currentTargets,
          results: context.results,
        }),
        runningQueries: runningQueryCount(currentTargets),
      };
    },
  };
}

type RunObservation = { state: RunState; failureDetail: string | null } | "unavailable";

export function createSessionSource(target: Extract<ShowTarget, { kind: "SESSION" }>, context: SourceContext): DashboardSource {
  let session: SessionBody | null = null;
  const runs = new Map<string, RunObservation>();

  async function observe(runPath: string, failClosed: boolean): Promise<boolean> {
    checkCancelled(context);
    let observation: RunObservation;
    try {
      const body = await context.reader.queryRun(runPath);
      observation = { state: body.state, failureDetail: body.state === "FAILED" ? body.failureDetail ?? null : null };
    } catch (error) {
      if (error instanceof ViewContractMismatchError || !failClosed) throw error;
      observation = "unavailable";
    }
    checkCancelled(context);
    const previous = runs.get(runPath);
    runs.set(runPath, observation);
    const changed = previous === undefined || previous === "unavailable" || observation === "unavailable" || previous.state !== observation.state;
    if (changed) context.onUpdate();
    if (observation !== "unavailable" && observation.state === "SUCCEEDED") await loadResults(context, [runPath]);
    return changed;
  }

  const runningPaths = () => [...runs.entries()].flatMap(([path, observation]) => observation !== "unavailable" && observation.state === "RUNNING" ? [path] : []);
  const pendingResultPaths = () => [...runs.entries()].flatMap(([path, observation]) => observation !== "unavailable" && observation.state === "SUCCEEDED" && !context.results.has(path) ? [path] : []);

  const targets = (): Record<string, TargetRun> => Object.fromEntries(Object.entries(target.queryRuns).map(([name, path]) => {
    const observation = runs.get(path);
    return [name, {
      path,
      state: observation === undefined || observation === "unavailable" ? null : observation.state,
      failureDetail: observation === undefined || observation === "unavailable" ? null : observation.failureDetail,
      unavailable: observation === "unavailable",
    }];
  }));

  return {
    get needsPolling() {
      return runningPaths().length > 0 || pendingResultPaths().length > 0;
    },
    async load() {
      session = await context.reader.session(target.path);
      checkCancelled(context);
      context.onUpdate();
      const ordered = runPathsInLayoutOrder(session.definition, (_, names) => names.map((name) => target.queryRuns[name]));
      await runInOrderBounded([...new Set([...ordered, ...Object.values(target.queryRuns)])], READ_CONCURRENCY, async (runPath) => {
        await observe(runPath, true);
      });
    },
    async poll() {
      const retrying = pendingResultPaths();
      let changed = false;
      await runInOrderBounded(runningPaths(), READ_CONCURRENCY, async (runPath) => {
        if (await observe(runPath, false)) changed = true;
      });
      if (await loadResults(context, retrying)) changed = true;
      if (runningPaths().length === 0 && pendingResultPaths().length === 0) return "complete";
      return changed ? "changed" : "unchanged";
    },
    present() {
      if (!session) return null;
      const currentTargets = targets();
      return {
        content: sessionLayoutContent({
          definition: session.definition,
          parameterBindings: session.parameterBindings,
          variables: target.variables,
          targets: currentTargets,
          results: context.results,
        }),
        runningQueries: runningQueryCount(currentTargets),
      };
    },
  };
}
