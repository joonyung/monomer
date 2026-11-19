import { useEffect, useRef, useState } from "react";
import { ChartWidgetRuntime } from "@/features/chart-widget";
import { chartWidgetFixtureScenarios } from "./fixture";

export function RuntimeFixtureHost() {
  const [scenario, setScenario] = useState("events");
  const inputs = chartWidgetFixtureScenarios[scenario];
  const canvasContainerRef = useRef<HTMLDivElement>(null);
  const runtimeRef = useRef<ChartWidgetRuntime | null>(null);
  const [selectedRegion, setSelectedRegion] = useState<string | null>(null);
  const [status, setStatus] = useState("idle");
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    const container = canvasContainerRef.current;
    if (!container) return;
    const canvas = document.createElement("canvas");
    canvas.className = "block h-[360px] w-full";
    canvas.setAttribute("aria-label", "ChartWidget runtime fixture");
    container.appendChild(canvas);
    const runtime = new ChartWidgetRuntime();
    runtimeRef.current = runtime;
    const unsubscribe = runtime.subscribe((response) => {
      setStatus(response.type);
      if (response.type === "variables_set" && Object.hasOwn(response.variables, "selectedRegion")) {
        setSelectedRegion(typeof response.variables.selectedRegion === "string" && response.variables.selectedRegion ? response.variables.selectedRegion : null);
        runtime.render({
          queries: inputs.queries,
          parameters: inputs.parameters,
          variables: { selectedRegion: response.variables.selectedRegion as string | null },
          locale: inputs.locale,
          viewport: inputs.viewport,
        });
      }
      if (response.type === "failed") setError(response.message);
    });
    try {
      runtime.mount(canvas, inputs);
    } catch (mountError) {
      setStatus("failed");
      setError(mountError instanceof Error ? mountError.message : String(mountError));
    }
    return () => {
      unsubscribe();
      runtime.dispose();
      runtimeRef.current = null;
      canvas.remove();
    };
  }, [inputs]);

  return (
    <section className="mx-auto flex w-full max-w-[960px] flex-col gap-4 p-8">
      <header className="flex flex-col gap-2">
        <span className="text-[10px] font-medium uppercase tracking-[0.08em] text-foreground-secondary">
          Development fixture
        </span>
        <h1 className="text-[22px] font-light tracking-[-0.22px] text-heading">
          ChartWidget setup + render
        </h1>
        <p className="text-sm text-foreground-secondary">
          setup registers events once, and render recomputes the ECharts option for the current frame.
        </p>
      </header>
      <label className="flex items-center gap-3 text-sm">
        Scenario
        <select aria-label="Scenario" value={scenario} className="h-8 rounded border border-border bg-background px-3" onChange={(event) => {
          setStatus("idle");
          setError(null);
          setSelectedRegion(null);
          setScenario(event.target.value);
        }}>
          {Object.keys(chartWidgetFixtureScenarios).map((name) => <option key={name} value={name}>{name}</option>)}
        </select>
      </label>
      <div className="grid gap-4 md:grid-cols-[1fr_220px]">
        <div className="overflow-hidden rounded-md border border-border bg-background p-3">
          <div ref={canvasContainerRef} />
          {error ? <p role="alert" className="mt-3 break-all text-sm text-error">{error}</p> : null}
        </div>
        <aside className="flex flex-col gap-3 rounded-md border border-border bg-surface p-4 text-sm">
          <div>
            <div className="text-[10px] font-medium uppercase tracking-[0.08em] text-foreground-secondary">Status</div>
            <div className="mt-1 font-mono text-xs text-foreground">{status}</div>
          </div>
          <div>
            <div className="text-[10px] font-medium uppercase tracking-[0.08em] text-foreground-secondary">Variable</div>
            <div className="mt-1 font-mono text-xs text-foreground">selectedRegion = {selectedRegion ?? "null"}</div>
          </div>
          <button
            type="button"
            disabled={scenario !== "events"}
            className="h-9 rounded border border-border bg-background px-3 text-left text-xs text-foreground hover:bg-surface-hover"
            onClick={() => {
              setSelectedRegion(null);
              runtimeRef.current?.render({
                queries: inputs.queries,
                parameters: inputs.parameters,
                variables: { selectedRegion: null },
                locale: inputs.locale,
                viewport: inputs.viewport,
              });
            }}
          >
            Clear selection
          </button>
        </aside>
      </div>
    </section>
  );
}
