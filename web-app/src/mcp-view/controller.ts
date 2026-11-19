import { parseShowResult, type ShowContext } from "./contract";
import { createPoller, POLL_FAILURE_LIMIT, POLL_IDLE_LIMIT_MS, POLL_INTERVAL_MS, type Poller, type PollingClock, type PollingPhase } from "./polling";
import type { DashboardLayoutContent, ResultEntry } from "./presentation";
import { createResourceReader, ResourceReadError, ViewContractMismatchError, type CallServerTool, type ToolCallResult } from "./reader";
import { createDraftSource, createSessionSource, isSourceCancelled, type DashboardSource } from "./sources";
import { supersessionChannelName, watchSupersession, type SupersessionChannel } from "./supersession";

export type ViewStage = "waiting" | "loading" | "ready" | "error" | "out-of-date" | "show-failed";
export type LiveState = "loading" | "live" | "paused" | "failed" | "complete" | "superseded" | "stopped";

export interface DashboardViewSnapshot {
  readonly stage: ViewStage;
  readonly show: ShowContext | null;
  readonly webUrl: string | null;
  readonly message: string | null;
  readonly content: DashboardLayoutContent | null;
  readonly live: LiveState;
  readonly runningQueries: number;
  readonly updatedAt: number | null;
}

export interface DashboardViewControllerOptions {
  callServerTool: CallServerTool;
  clock: PollingClock;
  instanceId: string;
  isViewActive(): boolean;
  createChannel: ((name: string) => SupersessionChannel) | null;
}

export interface DashboardViewController {
  subscribe(listener: () => void): () => void;
  getSnapshot(): DashboardViewSnapshot;
  acceptToolResult(result: ToolCallResult): void;
  closeBeforeShow(message: string): void;
  connected(): void;
  retry(): void;
  resume(): void;
  teardown(): void;
}

const INITIAL_SNAPSHOT: DashboardViewSnapshot = {
  stage: "waiting",
  show: null,
  webUrl: null,
  message: null,
  content: null,
  live: "loading",
  runningQueries: 0,
  updatedAt: null,
};

function loadFailureMessage(error: unknown): string {
  const status = error instanceof ResourceReadError ? error.problem?.status : null;
  if (status === 401 || status === 403) return "You don't have access to this dashboard.";
  if (status === 404) return "This dashboard is no longer available.";
  return "The dashboard couldn't be loaded here.";
}

function liveStateOf(phase: PollingPhase): LiveState {
  switch (phase) {
    case "running": return "live";
    case "paused": return "paused";
    case "failed": return "failed";
    case "complete": return "complete";
    case "stopped": return "stopped";
    case "idle": return "live";
  }
}

export function createDashboardViewController(options: DashboardViewControllerOptions): DashboardViewController {
  let snapshot = INITIAL_SNAPSHOT;
  const listeners = new Set<() => void>();
  let show: ShowContext | null = null;
  let isConnected = false;
  let loadStarted = false;
  let generation = 0;
  let source: DashboardSource | null = null;
  let poller: Poller | null = null;
  let superseded = false;
  let stopped = false;
  let stopSupersession: (() => void) | null = null;

  const update = (patch: Partial<DashboardViewSnapshot>) => {
    snapshot = { ...snapshot, ...patch };
    for (const listener of listeners) listener();
  };

  const present = () => {
    const presentation = source?.present();
    if (!presentation) return;
    update({ content: presentation.content, runningQueries: presentation.runningQueries });
  };

  const stopReading = () => {
    generation += 1;
    poller?.stop();
    poller = null;
  };

  const outOfDate = () => {
    stopReading();
    stopped = true;
    stopSupersession?.();
    stopSupersession = null;
    update({ stage: "out-of-date", live: "stopped" });
  };

  const startPolling = (current: DashboardSource, readGeneration: number) => {
    if (!current.needsPolling) {
      update({ live: "complete" });
      return;
    }
    poller = createPoller({
      intervalMs: POLL_INTERVAL_MS,
      idleLimitMs: POLL_IDLE_LIMIT_MS,
      failureLimit: POLL_FAILURE_LIMIT,
      clock: options.clock,
      isActive: () => !superseded && options.isViewActive(),
      onPhaseChange: (phase) => {
        if (readGeneration === generation && !superseded) update({ live: liveStateOf(phase) });
      },
      poll: async () => {
        try {
          const outcome = await current.poll();
          if (readGeneration !== generation) return "unchanged";
          if (outcome !== "unchanged") update({ updatedAt: options.clock.now() });
          return outcome;
        } catch (error) {
          if (error instanceof ViewContractMismatchError) outOfDate();
          throw error;
        }
      },
    });
    poller.start();
  };

  const load = async () => {
    if (!show || stopped) return;
    stopReading();
    const readGeneration = generation;
    const context = {
      reader: createResourceReader(options.callServerTool, show.workspaceId),
      results: new Map<string, ResultEntry>(),
      resultAttempts: new Map<string, number>(),
      onUpdate: () => {
        if (readGeneration === generation) present();
      },
      isCancelled: () => readGeneration !== generation || stopped,
    };
    const current = show.target.kind === "DRAFT" ? createDraftSource(show.target, context) : createSessionSource(show.target, context);
    source = current;
    update({ stage: "loading", message: null, content: null, live: "loading", runningQueries: 0 });
    try {
      await current.load();
    } catch (error) {
      if (readGeneration !== generation || isSourceCancelled(error)) return;
      if (error instanceof ViewContractMismatchError) {
        outOfDate();
        return;
      }
      update({ stage: "error", message: loadFailureMessage(error), live: "stopped" });
      return;
    }
    if (readGeneration !== generation) return;
    present();
    update({ stage: "ready", updatedAt: options.clock.now(), live: superseded ? "superseded" : "live" });
    if (!superseded) startPolling(current, readGeneration);
  };

  const beginWhenReady = () => {
    if (!show || !isConnected || loadStarted || stopped) return;
    loadStarted = true;
    void load();
  };

  const supersede = () => {
    superseded = true;
    poller?.stop();
    update({ live: "superseded" });
  };

  return {
    subscribe(listener) {
      listeners.add(listener);
      return () => listeners.delete(listener);
    },
    getSnapshot() {
      return snapshot;
    },
    acceptToolResult(result) {
      if (show || stopped || snapshot.stage !== "waiting") return;
      const outcome = parseShowResult(result);
      if (outcome.kind === "out-of-date") {
        stopped = true;
        update({ stage: "out-of-date", webUrl: outcome.webUrl, live: "stopped" });
        return;
      }
      if (outcome.kind === "failed") {
        stopped = true;
        update({ stage: "show-failed", message: outcome.message, webUrl: outcome.webUrl, live: "stopped" });
        return;
      }
      show = outcome.show;
      update({ stage: "loading", show, webUrl: show.webUrl });
      stopSupersession = watchSupersession({
        channelName: supersessionChannelName(show.workspaceId, show.target.path),
        self: { instanceId: options.instanceId, shownAt: show.shownAt, requestId: show.requestId },
        onSuperseded: supersede,
        createChannel: options.createChannel,
      });
      beginWhenReady();
    },
    closeBeforeShow(message) {
      if (snapshot.stage !== "waiting") return;
      stopped = true;
      update({ stage: "show-failed", message, live: "stopped" });
    },
    connected() {
      isConnected = true;
      beginWhenReady();
    },
    retry() {
      if (stopped || superseded) return;
      if (snapshot.stage === "error") void load();
      else if (snapshot.live === "failed") poller?.resume();
    },
    resume() {
      if (stopped || superseded || snapshot.live !== "paused") return;
      poller?.resume();
    },
    teardown() {
      stopReading();
      stopped = true;
      stopSupersession?.();
      stopSupersession = null;
      listeners.clear();
    },
  };
}
