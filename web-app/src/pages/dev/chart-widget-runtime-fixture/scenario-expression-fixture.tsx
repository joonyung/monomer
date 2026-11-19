import { useEffect, useRef, useState } from "react";
import { ChartWidgetRuntime } from "@/features/chart-widget";
import type { ChartRenderFrame, ChartVariableValues } from "@/features/chart-widget/contract/abi";
import type { ChartWidgetInputs } from "@/features/chart-widget/runtime/worker-protocol";
import {
  expressionScenarioKeys,
  expressionScenarios,
  expressionVariableValues,
} from "./scenario-expression";

function initialFrame(inputs: ChartWidgetInputs): ChartRenderFrame {
  return {
    queries: inputs.queries,
    parameters: inputs.parameters,
    variables: expressionVariableValues(inputs) as ChartVariableValues,
    locale: inputs.locale,
    viewport: inputs.viewport,
  };
}

function emptySalesFrame(frame: ChartRenderFrame): ChartRenderFrame {
  const source = frame.queries.sales;
  return { ...frame, queries: { ...frame.queries, sales: { columns: source.columns, rows: [] } } };
}

function frameWithVariable(frame: ChartRenderFrame, name: string, value: unknown): ChartRenderFrame {
  return { ...frame, variables: { ...frame.variables, [name]: value } as ChartVariableValues };
}

function hostInputFingerprint(inputs: ChartWidgetInputs): string {
  return JSON.stringify({ queries: inputs.queries, parameters: inputs.parameters, variables: inputs.variables });
}

function ExpressionView({ scenarioKey }: { scenarioKey: string }) {
  const scenario = expressionScenarios[scenarioKey] ?? expressionScenarios[expressionScenarioKeys[0]];
  const inputs = scenario.inputs;
  const containerRef = useRef<HTMLDivElement>(null);
  const siblingContainerRef = useRef<HTMLDivElement>(null);
  const runtimeRef = useRef<ChartWidgetRuntime | null>(null);
  const siblingRuntimeRef = useRef<ChartWidgetRuntime | null>(null);
  const frameRef = useRef<ChartRenderFrame>(initialFrame(inputs));
  const hostInputBaselineRef = useRef(hostInputFingerprint(inputs));
  const [status, setStatus] = useState("idle");
  const [siblingStatus, setSiblingStatus] = useState("idle");
  const [error, setError] = useState("");
  const [renderCount, setRenderCount] = useState(0);
  const [siblingRenderCount, setSiblingRenderCount] = useState(0);
  const [hostInputIntegrity, setHostInputIntegrity] = useState("pending");
  const [pingCount, setPingCount] = useState(0);
  const [frame, setFrame] = useState<ChartRenderFrame>(frameRef.current);
  const [hidden, setHidden] = useState(false);

  useEffect(() => {
    const container = containerRef.current;
    if (!container) return;
    const canvas = document.createElement("canvas");
    canvas.className = "block h-[320px] w-full";
    canvas.setAttribute("aria-label", "Expression chart");
    container.append(canvas);
    const runtime = new ChartWidgetRuntime();
    runtimeRef.current = runtime;
    const siblingContainer = scenario.id === "F08" ? siblingContainerRef.current : null;
    const siblingCanvas = siblingContainer ? document.createElement("canvas") : null;
    const siblingRuntime = siblingCanvas ? new ChartWidgetRuntime() : null;
    if (siblingCanvas && siblingContainer && siblingRuntime) {
      siblingCanvas.className = "block h-[320px] w-full";
      siblingCanvas.setAttribute("aria-label", "Expression sibling chart");
      siblingContainer.append(siblingCanvas);
      siblingRuntimeRef.current = siblingRuntime;
    }
    const start = initialFrame(inputs);
    frameRef.current = start;
    setFrame(start);
    setStatus("idle");
    setSiblingStatus("idle");
    setError("");
    setRenderCount(0);
    setSiblingRenderCount(0);
    setHostInputIntegrity("pending");
    const unsubscribe = runtime.subscribe(response => {
      if (response.type === "rendered") {
        setStatus("rendered");
        setRenderCount(count => count + 1);
        setHostInputIntegrity(hostInputFingerprint(inputs) === hostInputBaselineRef.current ? "unchanged" : "changed");
      } else if (response.type === "resized") {
        setStatus("resized");
        setRenderCount(count => count + 1);
      } else if (response.type === "failed") {
        setStatus("failed");
        setError(response.message);
      }
    });
    try {
      runtime.mount(canvas, inputs);
    } catch (mountError) {
      setStatus("failed");
      setError(mountError instanceof Error ? mountError.message : String(mountError));
    }
    const unsubscribeSibling = siblingRuntime?.subscribe(response => {
      if (response.type === "rendered") {
        setSiblingStatus("rendered");
        setSiblingRenderCount(count => count + 1);
        setHostInputIntegrity(hostInputFingerprint(inputs) === hostInputBaselineRef.current ? "unchanged" : "changed");
      } else if (response.type === "failed") {
        setSiblingStatus("failed");
      }
    });
    if (siblingRuntime && siblingCanvas) {
      siblingRuntime.mount(siblingCanvas, inputs);
      siblingRuntime.render(frameWithVariable(start, "mode", "sibling"));
    }
    return () => {
      unsubscribe();
      unsubscribeSibling?.();
      runtime.dispose();
      siblingRuntime?.dispose();
      runtimeRef.current = null;
      siblingRuntimeRef.current = null;
      canvas.remove();
      siblingCanvas?.remove();
    };
  }, [inputs, scenario.id, scenarioKey]);

  const renderFrame = (next: ChartRenderFrame) => {
    frameRef.current = next;
    setFrame(next);
    setStatus("idle");
    setError("");
    runtimeRef.current?.render(next);
  };

  const setVariable = (name: string, value: unknown) => renderFrame(frameWithVariable(frameRef.current, name, value));

  const controls = (() => {
    const buttons: Array<{ name: string; action: () => void }> = [];
    if (scenario.id === "A05") buttons.push({ name: "Render empty result", action: () => renderFrame(emptySalesFrame(frameRef.current)) });
    if (scenario.id === "F02") {
      for (const kind of ["undefined", "null", "array", "string", "number", "date", "promise"]) {
        buttons.push({ name: `Invalid ${kind}`, action: () => setVariable("mode", kind) });
      }
      buttons.push({ name: "Valid empty option", action: () => setVariable("mode", "success") });
    }
    if (scenario.id === "F03") buttons.push({ name: "Submit async source", action: () => setVariable("mode", "async") });
    if (scenario.id === "F06") {
      for (const mode of ["text", "external-image", "external-symbol", "callback-symbol", "title-link", "html-tooltip", "toolbox-save"]) {
        buttons.push({ name: mode, action: () => setVariable("mode", mode) });
      }
    }
    if (scenario.id === "F07") {
      for (const format of ["png", "jpeg", "webp", "svg", "corrupt"]) {
        buttons.push({ name: `Raster ${format}`, action: () => setVariable("format", format) });
      }
    }
    if (scenario.id === "G01") {
      buttons.push({ name: "Option under budget", action: () => setVariable("mode", "under") });
      buttons.push({ name: "Option over budget", action: () => setVariable("mode", "over") });
      buttons.push({ name: "Frame over budget", action: () => renderFrame({ ...frameRef.current, variables: { ...frameRef.current.variables, framePayload: "x".repeat(17 * 1024 * 1024) } as ChartVariableValues }) });
    }
    if (scenario.id === "G02") {
      for (const mode of ["output", "input", "depth"]) buttons.push({ name: `Callback ${mode}`, action: () => setVariable("mode", mode) });
    }
    if (scenario.id === "G04") {
      buttons.push({ name: "Resize to 390", action: () => { canvasResize(390); } });
      buttons.push({ name: "Resize to 1440", action: () => { canvasResize(1440); } });
      buttons.push({ name: "Toggle hidden", action: () => setHidden(value => !value) });
    }
    if (scenario.id === "G05") {
      buttons.push({ name: "Trigger error", action: () => setVariable("mode", "error") });
      buttons.push({ name: "Recover", action: () => setVariable("mode", "ok") });
    }
    if (scenario.id === "F08") buttons.push({ name: "Rerender immutable input", action: () => renderFrame(frameRef.current) });
    return buttons;

    function canvasResize(width: number) {
      const next = { ...frameRef.current, viewport: { width, height: 320 } };
      frameRef.current = next;
      setFrame(next);
      setError("");
      runtimeRef.current?.resize(next.viewport);
    }
  })();

  return (
    <main className="mx-auto flex min-h-screen w-full max-w-[1040px] flex-col gap-4 bg-background p-6 text-foreground" style={{ fontFeatureSettings: '"ss01"' }}>
      <header className="flex flex-col gap-2">
        <span className="text-[10px] font-medium uppercase tracking-[0.08em] text-foreground-secondary">Expression scenario</span>
        <h1 className="text-[22px] font-light tracking-[-0.22px] text-heading">{scenario.id} · {scenario.subcase}</h1>
        <p className="text-sm text-foreground-secondary">{scenario.description}</p>
      </header>
      <label className="flex items-center gap-3 text-sm" htmlFor="expression-scenario">
        Scenario
        <select id="expression-scenario" aria-label="Expression scenario" value={scenarioKey} className="h-8 rounded border border-border bg-background px-3" onChange={event => { window.location.search = `?expression=${encodeURIComponent(event.target.value)}`; }}>
          {expressionScenarioKeys.map(key => <option key={key} value={key}>{key}</option>)}
        </select>
      </label>
      <div className="grid gap-4 md:grid-cols-[1fr_240px]">
        <section className="overflow-hidden rounded-md border border-border bg-background p-3">
          <div ref={containerRef} hidden={hidden} />
          {error ? <p role="alert" className="mt-3 break-all text-sm text-error">{error}</p> : null}
        </section>
        <aside className="flex flex-col gap-3 rounded-md border border-border bg-surface p-4 text-sm">
          <div><div className="text-[10px] font-medium uppercase tracking-[0.08em] text-foreground-secondary">Status</div><output data-testid="expression-status" className="mt-1 block font-mono text-xs">{status}</output></div>
          <output data-testid="expression-render-count" className="font-mono text-xs">renders={renderCount}</output>
          {scenario.id === "F08" ? <>
            <output data-testid="expression-sibling-status" className="font-mono text-xs">sibling={siblingStatus}</output>
            <output data-testid="expression-sibling-render-count" className="font-mono text-xs">sibling-renders={siblingRenderCount}</output>
            <output data-testid="expression-host-input-integrity" className="font-mono text-xs">host-input={hostInputIntegrity}</output>
          </> : null}
          <output data-testid="expression-ping-count" className="font-mono text-xs">pings={pingCount}</output>
          {scenario.expectedText?.length ? <output data-testid="expression-expected" className="whitespace-pre-wrap break-words font-mono text-[10px]">{scenario.expectedText.join("\n")}</output> : null}
          <output data-testid="expression-frame" className="max-h-32 overflow-auto break-all font-mono text-[10px]">{JSON.stringify({ locale: frame.locale, viewport: frame.viewport, variables: frame.variables, rows: Object.fromEntries(Object.entries(frame.queries).map(([name, query]) => [name, query.rows.length])) })}</output>
          <button type="button" className="h-9 rounded border border-border bg-background px-3 text-left text-xs hover:bg-surface-hover" onClick={() => setPingCount(count => count + 1)}>Ping sibling control</button>
          <button type="button" className="h-9 rounded border border-border bg-background px-3 text-left text-xs hover:bg-surface-hover" onClick={() => renderFrame(frameRef.current)}>Render current frame</button>
          <div className="flex flex-col gap-2">
            {controls.map(control => <button key={control.name} type="button" className="min-h-8 rounded border border-border bg-background px-3 py-1 text-left text-xs hover:bg-surface-hover" onClick={control.action}>{control.name}</button>)}
          </div>
        </aside>
      </div>
      {scenario.id === "F08" ? <section className="rounded-md border border-border bg-background p-3">
        <h2 className="mb-2 text-sm font-medium">Sibling chart using the same host input</h2>
        <div ref={siblingContainerRef} />
      </section> : null}
    </main>
  );
}

export function ScenarioExpressionFixture() {
  const initial = new URLSearchParams(window.location.search).get("expression") ?? expressionScenarioKeys[0];
  const [scenarioKey] = useState(initial);
  return <ExpressionView scenarioKey={scenarioKey} />;
}
