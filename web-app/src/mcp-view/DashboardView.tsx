import { Component, useEffect, useLayoutEffect, useMemo, useRef, useState, useSyncExternalStore, type CSSProperties, type MouseEvent, type ReactNode } from "react";
import { ChartColumn } from "lucide-react";
import { DashboardLayout } from "@/components/dashboard/DashboardLayout";
import { DashboardWidgetEnvironmentContext, type DashboardWidgetEnvironment } from "@/components/dashboard/DashboardWidgetEnvironment";
import { Button } from "@/components/ui/Button";
import { StateBlock } from "@/components/ui/StateBlock";
import type { WidgetRuntimePlan } from "./capability";
import type { DashboardViewController, DashboardViewSnapshot } from "./controller";
import type { ViewDisplayMode, ViewHostStore } from "./host";
import type { DashboardLayoutContent } from "./presentation";
import { statusLine, SUPERSEDED_MESSAGE } from "./status";
import type { ChartWidgetWorkerFactory } from "@/features/chart-widget";
import type { TableWorkerFactory } from "@/features/table-widget/runtime/table-widget-runtime";

export const INLINE_MAX_HEIGHT_PX = 520;
const TABLE_FALLBACK_MESSAGE = "This chat can't run table formatting.";

export interface ViewHostActions {
  openLink(url: string): void;
  requestDisplayMode(mode: ViewDisplayMode): void;
}

export interface ViewWorkerFactories {
  chart: ChartWidgetWorkerFactory;
  table: TableWorkerFactory;
}

const unavailableTableWorker: TableWorkerFactory = () => {
  throw new Error(TABLE_FALLBACK_MESSAGE);
};

function useNow(intervalMs: number): number {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const timer = window.setInterval(() => setNow(Date.now()), intervalMs);
    return () => window.clearInterval(timer);
  }, [intervalMs]);
  return now;
}

function OpenInMonomerButton({ webUrl, actions, variant = "secondary" }: { webUrl: string | null; actions: ViewHostActions; variant?: "primary" | "secondary" }) {
  if (!webUrl) return null;
  return <Button type="button" variant={variant} size="sm" onClick={() => actions.openLink(webUrl)}>Open in Monomer</Button>;
}

interface LayoutBoundaryProps {
  resetKey: unknown;
  fallback: ReactNode;
  children: ReactNode;
}

// A definition the shared layout cannot render closes the view instead of blanking the iframe.
class LayoutBoundary extends Component<LayoutBoundaryProps, { failed: boolean }> {
  state = { failed: false };
  static getDerivedStateFromError() {
    return { failed: true };
  }
  componentDidUpdate(previous: LayoutBoundaryProps) {
    if (this.state.failed && previous.resetKey !== this.props.resetKey) this.setState({ failed: false });
  }
  render() {
    return this.state.failed ? this.props.fallback : this.props.children;
  }
}

// Inline, the layout is capped and fades out instead of scrolling inside the chat; fullscreen shows
// it whole. The same element tree serves both so switching modes keeps running charts.
function LayoutFrame({ capped, hint, children }: { capped: boolean; hint: string; children: ReactNode }) {
  const frameRef = useRef<HTMLDivElement>(null);
  const [overflowing, setOverflowing] = useState(false);
  useLayoutEffect(() => {
    const frame = frameRef.current;
    const content = frame?.firstElementChild;
    if (!frame || !content || !capped) {
      setOverflowing(false);
      return;
    }
    const measure = () => setOverflowing(content.scrollHeight > frame.clientHeight + 1);
    const observer = new ResizeObserver(measure);
    observer.observe(content);
    observer.observe(frame);
    measure();
    return () => observer.disconnect();
  }, [capped]);
  return <>
    <div ref={frameRef} className={capped ? "mcp-view-inline-frame" : undefined} style={capped ? { maxHeight: INLINE_MAX_HEIGHT_PX } : undefined} data-overflowing={overflowing || undefined}>
      <div>{children}</div>
    </div>
    {overflowing ? <p className="type-meta text-foreground-secondary">{hint}</p> : null}
  </>;
}

function Layout({ content }: { content: DashboardLayoutContent }) {
  return <DashboardLayout
    readOnly
    definition={content.definition}
    queryTypes={content.queryTypes}
    parameters={content.parameters}
    parametersByWidgetId={content.parametersByWidgetId}
    variables={content.variables}
    variablesByWidgetId={content.variablesByWidgetId}
    variableRevision={content.variableRevision}
    variableRevisionsByWidgetId={content.variableRevisionsByWidgetId}
    variableDefinitions={content.definition.variables}
    resultsByWidgetId={content.resultsByWidgetId}
    displayedWidgetIds={content.displayedWidgetIds}
    refreshingWidgetIds={content.refreshingWidgetIds}
    failureWidgetIds={content.failureWidgetIds}
    errorMessagesByWidgetId={content.errorMessagesByWidgetId}
    loadingMessagesByWidgetId={content.loadingMessagesByWidgetId}
    onVariablesSet={() => undefined}
  />;
}

function Body({ snapshot, controller, displayMode, fullscreenAvailable }: {
  snapshot: DashboardViewSnapshot;
  controller: DashboardViewController;
  displayMode: ViewDisplayMode;
  fullscreenAvailable: boolean;
}) {
  switch (snapshot.stage) {
    case "out-of-date":
      return <StateBlock kind="error" layout="inline" title="This view is out of date." description="Reconnect the Monomer connector, or open the dashboard in Monomer." />;
    case "show-failed":
      return <StateBlock kind="error" layout="inline" title="This dashboard couldn't be shown." description={snapshot.message} />;
    case "error":
      return <StateBlock kind="error" layout="inline" title={snapshot.message ?? "The dashboard couldn't be loaded here."} action={<Button type="button" variant="secondary" size="sm" onClick={() => controller.retry()}>Retry</Button>} />;
    case "waiting":
    case "loading":
    case "ready":
      break;
  }
  if (!snapshot.content) return <StateBlock kind="loading" layout="inline" title="Loading dashboard…" />;
  const fallback = <StateBlock kind="error" layout="inline" title="This dashboard couldn't be displayed here." description="Open it in Monomer to see it." />;
  const layout = <LayoutBoundary resetKey={snapshot.content.definition} fallback={fallback}><Layout content={snapshot.content} /></LayoutBoundary>;
  const superseded = snapshot.live === "superseded";
  return <div className={superseded ? "mcp-view-superseded" : undefined} inert={superseded} aria-hidden={superseded || undefined}>
    <LayoutFrame capped={displayMode === "inline"} hint={fullscreenAvailable ? "Expand to see the whole dashboard." : "Open in Monomer to see the whole dashboard."}>{layout}</LayoutFrame>
  </div>;
}

export function DashboardView({ controller, hostStore, actions, workerFactories, runtimePlan }: {
  controller: DashboardViewController;
  hostStore: ViewHostStore;
  actions: ViewHostActions;
  workerFactories: ViewWorkerFactories;
  runtimePlan: WidgetRuntimePlan | null;
}) {
  const snapshot = useSyncExternalStore(controller.subscribe, controller.getSnapshot, controller.getSnapshot);
  const host = useSyncExternalStore(hostStore.subscribe, hostStore.getSnapshot, hostStore.getSnapshot);
  const now = useNow(30_000);
  const webUrl = snapshot.webUrl;
  const environment = useMemo<DashboardWidgetEnvironment>(() => {
    const open = <OpenInMonomerButton webUrl={webUrl} actions={actions} />;
    return {
      chartWorkerFactory: workerFactories.chart,
      tableWorkerFactory: runtimePlan?.tables === "run" ? workerFactories.table : unavailableTableWorker,
      chartPlaceholder: runtimePlan?.charts === "run" ? undefined : (
        <div className="dashboard-widget-state flex h-full min-h-0 flex-1 flex-col" data-testid="mcp-view-chart-placeholder">
          <StateBlock kind="empty" layout="inline" icon={ChartColumn} title="This chart can't run in this chat." action={open} />
        </div>
      ),
      failureAction: open,
    };
  }, [actions, runtimePlan, webUrl, workerFactories]);

  const title = snapshot.content?.definition.meta.title ?? snapshot.show?.title ?? "Monomer dashboard";
  const status = statusLine({
    targetKind: snapshot.show?.target.kind ?? null,
    stage: snapshot.stage,
    live: snapshot.live,
    runningQueries: snapshot.runningQueries,
    updatedAt: snapshot.updatedAt,
    now,
  });
  const fullscreen = host.displayMode === "fullscreen";
  const insets = host.safeAreaInsets;
  const openExternalLink = (event: MouseEvent<HTMLElement>) => {
    const anchor = (event.target as Element | null)?.closest?.("a[href]");
    if (!(anchor instanceof HTMLAnchorElement) || !/^https?:$/.test(anchor.protocol)) return;
    event.preventDefault();
    actions.openLink(anchor.href);
  };

  return <div
    className="mcp-view"
    data-display-mode={host.displayMode}
    data-host-theme={host.theme ?? undefined}
    data-stage={snapshot.stage}
    data-live={snapshot.live}
    style={{ "--safe-area-top": `${insets.top}px`, "--safe-area-right": `${insets.right}px`, "--safe-area-bottom": `${insets.bottom}px`, "--safe-area-left": `${insets.left}px` } as CSSProperties}
    onClickCapture={openExternalLink}
  >
    <header className="mcp-view-header">
      <div className="min-w-0 flex-1">
        <h2 className="type-section truncate text-heading" title={title}>{title}</h2>
        <p className="type-meta truncate text-foreground-secondary" role="status" aria-live="polite" data-testid="mcp-view-status">{status}</p>
      </div>
      <div className="mcp-view-actions">
        {snapshot.live === "paused" ? <Button type="button" variant="secondary" size="sm" onClick={() => controller.resume()}>Resume</Button> : null}
        {snapshot.live === "failed" && snapshot.stage === "ready" ? <Button type="button" variant="secondary" size="sm" onClick={() => controller.retry()}>Retry</Button> : null}
        {!fullscreen && host.fullscreenAvailable && snapshot.content ? <Button type="button" variant="secondary" size="sm" onClick={() => actions.requestDisplayMode("fullscreen")}>Expand</Button> : null}
        {fullscreen ? <Button type="button" variant="secondary" size="sm" onClick={() => actions.requestDisplayMode("inline")}>Exit full screen</Button> : null}
        <OpenInMonomerButton webUrl={webUrl} actions={actions} variant="primary" />
      </div>
    </header>
    {snapshot.live === "superseded" ? <p className="mcp-view-notice type-meta">{SUPERSEDED_MESSAGE}</p> : null}
    <DashboardWidgetEnvironmentContext.Provider value={environment}>
      {snapshot.content && !runtimePlan ? <StateBlock kind="loading" layout="inline" title="Loading dashboard…" /> : <Body snapshot={snapshot} controller={controller} displayMode={host.displayMode} fullscreenAvailable={host.fullscreenAvailable} />}
    </DashboardWidgetEnvironmentContext.Provider>
  </div>;
}
