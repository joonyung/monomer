import { useEffect, useMemo, useRef, useState } from "react";
import { ChartWidgetRuntime } from "@/features/chart-widget";
import { MONOMER_DIVERGING_RAMP, MONOMER_SEQUENTIAL_RAMP } from "@/features/chart-widget/runtime/worker/chart-theme";
import { CATEGORICAL_CHART_COLORS } from "@/lib/chart-color-scale";
import { themeFixtureCharts, themeFixtureViewport, type ThemeFixtureChart } from "./theme-fixtures";

const PALETTE_NAMES = ["Blue", "Teal", "Coral", "Indigo", "Violet", "Green", "Rose", "Sky"];

function PaletteStrip() {
  return (
    <section aria-label="Chart palette" className="flex flex-wrap items-end gap-x-8 gap-y-4">
      <div className="flex gap-2">
        {CATEGORICAL_CHART_COLORS.map((color, index) => (
          <div key={color} className="flex w-[56px] flex-col gap-1">
            <span className="h-8 rounded-[4px]" style={{ background: color }} />
            <span className="text-[11px] text-foreground">{index + 1} · {PALETTE_NAMES[index]}</span>
            <span className="font-mono text-[10px] text-foreground-secondary">{color}</span>
          </div>
        ))}
      </div>
      {[["Sequential", MONOMER_SEQUENTIAL_RAMP], ["Diverging", MONOMER_DIVERGING_RAMP]].map(([label, ramp]) => (
        <div key={label as string} className="flex flex-col gap-1">
          <div className="flex overflow-hidden rounded-[4px]">
            {(ramp as readonly string[]).map((color) => <span key={color} className="h-8 w-7" style={{ background: color }} />)}
          </div>
          <span className="text-[11px] text-foreground">{label as string}</span>
        </div>
      ))}
    </section>
  );
}

type FixtureState = Readonly<{ refresh: number; reversed: boolean }>;

function ThemeChartCard({ chart, state }: Readonly<{ chart: ThemeFixtureChart; state: FixtureState }>) {
  const containerRef = useRef<HTMLDivElement>(null);
  const runtimeRef = useRef<ChartWidgetRuntime | null>(null);
  const [status, setStatus] = useState("idle");
  const [error, setError] = useState<string | null>(null);
  const inputs = useMemo(() => chart.inputs(state), [chart, state]);
  const latestInputsRef = useRef(inputs);
  latestInputsRef.current = inputs;

  useEffect(() => {
    const container = containerRef.current;
    if (!container) return;
    const canvas = document.createElement("canvas");
    canvas.className = "block h-[260px] w-full";
    canvas.setAttribute("aria-label", chart.title);
    container.appendChild(canvas);
    const runtime = new ChartWidgetRuntime();
    runtimeRef.current = runtime;
    const unsubscribe = runtime.subscribe((response) => {
      if (response.type === "rendered") setStatus("rendered");
      if (response.type === "failed") {
        setStatus("failed");
        setError(response.message);
      }
    });
    try {
      runtime.mount(canvas, latestInputsRef.current);
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
  }, [chart]);

  useEffect(() => {
    const runtime = runtimeRef.current;
    if (!runtime || runtime.snapshot.lastGoodOperationId === null) return;
    setStatus("rendering");
    runtime.render({
      queries: inputs.queries,
      parameters: inputs.parameters,
      variables: {},
      locale: inputs.locale,
      viewport: inputs.viewport,
    });
  }, [inputs]);

  return (
    <article
      data-chart-id={chart.id}
      data-chart-status={status}
      className="flex flex-col gap-3 rounded-[6px] border border-border bg-background p-4"
    >
      <header className="flex flex-col gap-1">
        <h2 className="text-[15px] font-normal leading-[1.35] text-foreground">{chart.title}</h2>
        <p className="text-xs text-foreground-secondary">{chart.description}</p>
      </header>
      <div ref={containerRef} style={{ width: themeFixtureViewport.width }} className="max-w-full" />
      {error ? <p role="alert" className="break-all text-sm text-error">{error}</p> : null}
    </article>
  );
}

export function ThemeFixture() {
  const [state, setState] = useState<FixtureState>({ refresh: 0, reversed: false });
  return (
    <main className="min-h-screen bg-background" style={{ fontFeatureSettings: '"ss01"' }}>
      <section className="mx-auto flex w-full max-w-[1432px] flex-col gap-6 p-8">
        <header className="flex flex-wrap items-end justify-between gap-4">
          <div className="flex flex-col gap-2">
            <span className="text-[10px] font-medium uppercase tracking-[0.08em] text-foreground-secondary">
              Development fixture
            </span>
            <h1 className="text-[32px] font-light leading-[1.1] tracking-[-0.64px] text-heading">Chart theme</h1>
          </div>
          <div className="flex gap-2">
            <button
              type="button"
              className="h-9 rounded-[4px] border border-border bg-background px-4 text-[13px] text-foreground hover:bg-surface"
              onClick={() => setState((current) => ({ ...current, refresh: current.refresh + 1 }))}
            >
              Refresh data
            </button>
            <button
              type="button"
              aria-pressed={state.reversed}
              className="h-9 rounded-[4px] border border-border bg-background px-4 text-[13px] text-foreground hover:bg-surface"
              onClick={() => setState((current) => ({ ...current, reversed: !current.reversed }))}
            >
              Reorder channels
            </button>
          </div>
        </header>
        <PaletteStrip />
        <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-3">
          {themeFixtureCharts.map((chart) => <ThemeChartCard key={chart.id} chart={chart} state={state} />)}
        </div>
      </section>
    </main>
  );
}
