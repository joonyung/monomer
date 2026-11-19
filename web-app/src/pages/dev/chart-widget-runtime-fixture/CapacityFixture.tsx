import { useEffect, useRef, useState } from "react";
import { ChartWidgetRuntime } from "@/features/chart-widget";
import { capacityFrames, capacitySource, rasterCapacitySource } from "./capacity-fixtures";

export function CapacityFixture({ rasterBytes }: { rasterBytes?: number }) {
  const containerRef = useRef<HTMLDivElement>(null);
  const runtimeRef = useRef<ChartWidgetRuntime | null>(null);
  const startedRef = useRef(0);
  const [status, setStatus] = useState("idle");
  const [completions, setCompletions] = useState(0);
  const [metrics, setMetrics] = useState<Record<string, number>>({});
  const [error, setError] = useState("");
  useEffect(() => {
    const canvas = document.createElement("canvas");
    canvas.style.width = "720px"; canvas.style.height = "360px";
    canvas.setAttribute("aria-label", "Capacity chart");
    containerRef.current!.append(canvas);
    const runtime = new ChartWidgetRuntime();
    runtimeRef.current = runtime;
    const unsubscribe = runtime.subscribe(response => {
      if (response.type === "rendered") {
        setStatus(runtime.snapshot.status === "failed" ? "failed" : "rendered");
        setCompletions(count => count + 1);
        if (startedRef.current) setMetrics(current => ({ ...current, workerRoundTripMs: performance.now() - startedRef.current }));
      }
      if (response.type === "failed") { setStatus("failed"); setError(response.message); }
    });
    runtime.mount(canvas, { ...capacityFrames().empty, moduleSource: rasterBytes ? rasterCapacitySource(rasterBytes) : capacitySource, variables: {} });
    return () => { unsubscribe(); runtime.dispose(); canvas.remove(); };
  }, [rasterBytes]);
  return <main className="flex flex-col gap-4 bg-background p-8 text-foreground">
    <h1>C06 input capacity</h1><div ref={containerRef} />
    <output data-testid="capacity-status">{status}</output>
    <output data-testid="capacity-completions">{completions}</output>
    <output data-testid="capacity-metrics">{JSON.stringify(metrics)}</output>
    {error ? <p role="alert">{error}</p> : null}
    <div className="flex gap-4">{["5000x10", "wide-short", "multiple-queries", "large-strings", "narrow-100000", "near-byte-limit", "empty", "over-budget", "overlap-over-budget"].map(name => <button key={name} onClick={() => {
      const frames = capacityFrames();
      const frame = name.endsWith("over-budget") ? { ...frames.empty,
        parameters: Object.fromEntries(Array.from({ length: 36 }, (_, index) => [`p${index}`, "x".repeat(256 * 1024)])),
        variables: Object.fromEntries(Array.from({ length: 36 }, (_, index) => [`v${index}`, "x".repeat(256 * 1024)])),
      } : frames[name];
      const begin = performance.now(); const clone = structuredClone(frame); const copied = performance.now();
      const option = { series: [{ type: "bar", data: clone.queries.sales.rows.map(row => Number(row[clone.queries.sales.columns[0]?.name]) || 0) }] };
      const generated = performance.now();
      setMetrics({ frameJsonBytes: new TextEncoder().encode(JSON.stringify(frame)).byteLength,
        copyMs: copied - begin, optionGenerationMs: generated - copied, optionJsonBytes: new TextEncoder().encode(JSON.stringify(option)).byteLength });
      setStatus("rendering"); setError(""); startedRef.current = performance.now();
      if (name === "overlap-over-budget") runtimeRef.current!.render(frames["5000x10"]);
      runtimeRef.current!.render(frame);
    }}>{name}</button>)}</div>
  </main>;
}
