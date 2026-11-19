import { useEffect, useMemo, useRef, useState } from "react";
import type { DashboardJsonObject, DashboardJsonValue, DashboardVariableDefinition } from "@/api/dashboard";
import { ChartWidgetRuntime, type ChartWidgetRuntimeState } from "@/features/chart-widget";
import type { ChartVariableInput } from "@/features/chart-widget/runtime/worker-protocol";
import type { ChartParameters, ChartQueryResults, ChartValue, ChartVariableValues } from "@/features/chart-widget/contract/abi";
import type { NamedDashboardResult } from "@/features/dashboard/dashboard-result";
import { toChartQueryResult } from "@/features/dashboard/dashboard-result";
import { StateBlock } from "@/components/ui/StateBlock";
import { useDashboardWidgetEnvironment } from "./DashboardWidgetEnvironment";

interface DashboardChartWidgetProps {
  accessibleName: string;
  moduleSource: string;
  queryNames: string[];
  parameterNames: string[];
  variableNames: string[];
  parameters: Record<string, DashboardJsonValue>;
  variables: Record<string, DashboardJsonValue>;
  variableDefinitions: Record<string, DashboardVariableDefinition>;
  results: Record<string, NamedDashboardResult>;
  variableRevision: number;
  onVariablesSet: (values: DashboardJsonObject, expectedRevision: number) => void;
  readOnly?: boolean;
}

function chartValue(value: unknown): ChartValue {
  if (value === null || typeof value === "string" || typeof value === "number" || typeof value === "boolean") return value;
  if (Array.isArray(value)) return value.map(chartValue);
  if (typeof value === "object") return Object.fromEntries(Object.entries(value).map(([key, nested]) => [key, chartValue(nested)]));
  return null;
}

type ChartQueriesSnapshot = Readonly<{
  queryNames: readonly string[];
  queryResults: readonly (NamedDashboardResult | undefined)[];
  chartQueries: ChartQueryResults;
}>;

function sameQueryResults(
  snapshot: ChartQueriesSnapshot,
  queryNames: readonly string[],
  queryResults: readonly (NamedDashboardResult | undefined)[],
): boolean {
  return snapshot.queryNames.length === queryNames.length
    && queryNames.every((queryName, index) => snapshot.queryNames[index] === queryName && snapshot.queryResults[index] === queryResults[index]);
}

function declaredValues(
  names: string[],
  values: Record<string, DashboardJsonValue>,
): Record<string, DashboardJsonValue> {
  return Object.fromEntries(names.map((name) => [name, values[name] ?? null]));
}

export function DashboardChartWidget({
  accessibleName,
  moduleSource,
  queryNames,
  parameterNames,
  variableNames,
  parameters,
  variables,
  variableDefinitions,
  results,
  onVariablesSet,
  variableRevision,
  readOnly = false,
}: DashboardChartWidgetProps) {
  const { chartWorkerFactory } = useDashboardWidgetEnvironment();
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const containerRef = useRef<HTMLDivElement>(null);
  const runtimeRef = useRef<ChartWidgetRuntime | null>(null);
  const runtimeModuleSourceRef = useRef<string | null>(null);
  const disposeTimerRef = useRef<number | null>(null);
  const mutationFramesRef = useRef(new Map<number, {
    variableRevision: number;
    submit: typeof onVariablesSet;
    readOnly: boolean;
  }>());
  const [runtimeState, setRuntimeState] = useState<ChartWidgetRuntimeState>({
    status: "idle",
    generation: 0,
    lastGoodOperationId: null,
    lastError: null,
  });
  const [viewport, setViewport] = useState({ width: 320, height: 240 });

  // Hosts rebuild the per-widget results map on every render. A Worker render replaces the displayed
  // frame and cancels an in-progress chart gesture, so only a changed query result may start one.
  const queryResults = queryNames.map((queryName) => results[queryName]);
  const chartQueriesRef = useRef<ChartQueriesSnapshot | null>(null);
  if (!chartQueriesRef.current || !sameQueryResults(chartQueriesRef.current, queryNames, queryResults)) {
    chartQueriesRef.current = {
      queryNames,
      queryResults,
      chartQueries: Object.fromEntries(queryNames.map((queryName, index) => {
        const result = queryResults[index];
        return [queryName, result ? toChartQueryResult(result) : { columns: [], rows: [] }];
      })),
    };
  }
  const chartQueries = chartQueriesRef.current.chartQueries;
  const chartParameters = useMemo<ChartParameters>(() => declaredValues(
    parameterNames,
    parameters,
  ) as ChartParameters, [parameterNames, parameters]);
  const moduleVariables = useMemo<Record<string, ChartVariableInput>>(() => Object.fromEntries(
    variableNames.map((name) => [name, {
      value: chartValue(variables[name] ?? null),
      nullable: variableDefinitions[name]?.nullable === true,
      type: variableDefinitions[name].type,
    }]),
  ), [variableDefinitions, variableNames, variables]);
  const chartVariableValues = useMemo<ChartVariableValues>(() => Object.fromEntries(
    Object.entries(moduleVariables).map(([name, input]) => [name, input.value]),
  ) as ChartVariableValues, [moduleVariables]);
  const latestMutationFrameRef = useRef({ variableRevision, submit: onVariablesSet, readOnly });
  latestMutationFrameRef.current = { variableRevision, submit: onVariablesSet, readOnly };
  const latestFrameRef = useRef({
    queries: chartQueries,
    parameters: chartParameters,
    variables: chartVariableValues,
    locale: typeof navigator === "undefined" ? "en-US" : navigator.language,
    viewport,
  });
  latestFrameRef.current = {
    queries: chartQueries,
    parameters: chartParameters,
    variables: chartVariableValues,
    locale: typeof navigator === "undefined" ? "en-US" : navigator.language,
    viewport,
  };

  useEffect(() => {
    const container = containerRef.current;
    if (!container) return;
    const updateViewport = () => {
      const width = Math.max(1, Math.floor(container.clientWidth));
      const height = Math.max(1, Math.floor(container.clientHeight));
      setViewport((current) => current.width === width && current.height === height ? current : { width, height });
    };
    updateViewport();
    const observer = new ResizeObserver(updateViewport);
    observer.observe(container);
    return () => observer.disconnect();
  }, []);

  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;
    if (disposeTimerRef.current !== null) {
      window.clearTimeout(disposeTimerRef.current);
      disposeTimerRef.current = null;
    }
    let runtime = runtimeRef.current;
    if (!runtime || runtimeModuleSourceRef.current !== moduleSource) {
      runtime?.dispose();
      runtime = new ChartWidgetRuntime(chartWorkerFactory);
      runtimeRef.current = runtime;
      runtimeModuleSourceRef.current = moduleSource;
      mutationFramesRef.current.clear();
    }
    const mutationFrames = mutationFramesRef.current;
    const unsubscribe = runtime.subscribe((response) => {
      if (response.type === "variables_set") {
        const frame = mutationFrames.get(response.renderOperationId);
        if (frame && !frame.readOnly) {
          frame.submit(chartValue(response.variables) as DashboardJsonObject, frame.variableRevision);
        }
      }
      if (response.type === "rendered" && response.operationId === 0) {
        const operationId = runtime.render(latestFrameRef.current);
        mutationFrames.set(operationId, latestMutationFrameRef.current);
      }
      setRuntimeState(runtime.snapshot);
    });
    setRuntimeState(runtime.snapshot);
    if (runtime.snapshot.status === "idle") {
      try {
        mutationFrames.set(0, latestMutationFrameRef.current);
        runtime.mount(canvas, {
          density: canvas.closest("[data-capture-layout]") ? 4 : undefined,
          moduleSource,
          queries: chartQueries,
          parameters: chartParameters,
          variables: moduleVariables,
          locale: typeof navigator === "undefined" ? "en-US" : navigator.language,
          viewport,
        });
      } catch (error) {
        setRuntimeState({ ...runtime.snapshot, status: "failed", lastError: error instanceof Error ? error.message : "Chart could not start." });
      }
    }
    return () => {
      unsubscribe();
      // React StrictMode replays effects on the same canvas. Delay disposal so
      // the replay can reuse the transferred OffscreenCanvas; a real unmount
      // still disposes the Worker on the next task.
      disposeTimerRef.current = window.setTimeout(() => {
        if (runtimeRef.current !== runtime) return;
        runtime.dispose();
        runtimeRef.current = null;
        runtimeModuleSourceRef.current = null;
        mutationFrames.clear();
        disposeTimerRef.current = null;
      }, 0);
    };
    // Source changes receive a fresh canvas: OffscreenCanvas transfer is one-shot.
    // Query and Variable frames reuse the current runtime through render below.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [moduleSource]);

  useEffect(() => {
    const runtime = runtimeRef.current;
    if (!runtime || runtime.snapshot.lastGoodOperationId === null || runtime.snapshot.status === "disposed") return;
    try {
      const operationId = runtime.render({
        queries: chartQueries,
        parameters: chartParameters,
        variables: chartVariableValues,
        locale: typeof navigator === "undefined" ? "en-US" : navigator.language,
        viewport,
      });
      mutationFramesRef.current.set(operationId, latestMutationFrameRef.current);
    } catch {
      // The runtime listener owns the bounded error message.
    }
  }, [chartParameters, chartQueries, chartVariableValues, variableRevision, viewport, readOnly]);

  const failure = runtimeState.status === "failed";
  const hasLastGoodCanvas = runtimeState.lastGoodOperationId !== null;
  return (
    <div className="relative h-full min-h-0 overflow-hidden rounded-[4px] bg-background" ref={containerRef} data-testid="dashboard-chart-widget" data-rendered-revision={runtimeState.lastGoodOperationId === null ? undefined : mutationFramesRef.current.get(runtimeState.lastGoodOperationId)?.variableRevision} data-chart-status={runtimeState.status} data-chart-viewport-width={viewport.width} data-chart-viewport-height={viewport.height}>
      <canvas key={moduleSource} ref={canvasRef} className="block h-full w-full" role="img" aria-label={accessibleName} />
      {failure && hasLastGoodCanvas ? (
        <div className="absolute inset-x-2 top-2 rounded-[4px] bg-background/90 p-2 shadow-ambient">
          <p role="alert" className="type-meta text-error">The latest chart frame could not be displayed. Previous results are shown. {runtimeState.lastError ?? "Chart worker error"}</p>
        </div>
      ) : null}
      {failure && !hasLastGoodCanvas ? (
        <div className="dashboard-widget-state absolute inset-0 flex flex-col bg-background/90">
          <StateBlock kind="error" layout="inline" title="The chart could not be displayed." description={<span className="dashboard-widget-state-description" tabIndex={0} title={runtimeState.lastError ?? "Chart worker error"}>{runtimeState.lastError ?? "Chart worker error"}</span>}
          />
        </div>
      ) : null}
    </div>
  );
}
