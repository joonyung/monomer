import { expect, test } from "./support/test";
import { mkdir, writeFile } from "node:fs/promises";
import type { ChartWidgetRuntime } from "../../src/features/chart-widget/runtime/chart-widget-runtime";
import type { ChartWorkerResponse } from "../../src/features/chart-widget/runtime/worker-protocol";
import { evidencePath } from "./evidence";

type Diagnostic = {
  type: "resize_diagnostic";
  generation: number;
  root: number[];
  bitmap: number[];
  layers: { instanceId: number; key: string; width: number; height: number; virtual: boolean; hover: boolean }[];
  hoveredElements: number;
};

type Harness = {
  runtime: ChartWidgetRuntime;
  responses: (ChartWorkerResponse | Diagnostic)[];
};

declare global {
  interface Window {
    offscreenResize: Harness;
  }
}

const barSource = `export default defineChart({ render() { return {
  animation: false,
  grid: { left: 40, right: 20, top: 20, bottom: 35 },
  xAxis: { type: "category", data: ["North", "South", "East", "West"] },
  yAxis: { type: "value" },
  series: [{ type: "bar", data: [12, 20, 16, 24] }]
}; } });`;

const heatmapSource = `export default defineChart({ render() { return {
  animation: false,
  grid: { left: 40, right: 20, top: 20, bottom: 35 },
  xAxis: { type: "category", data: Array.from({ length: 24 }, (_, hour) => String(hour)) },
  yAxis: { type: "category", data: ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"] },
  visualMap: { show: false, min: 0, max: 10 },
  series: [{ type: "heatmap", data: Array.from({ length: 168 }, (_, index) =>
    [index % 24, Math.floor(index / 24), index % 11]) }]
}; } });`;

for (const density of [1, 2]) {
  for (const scenario of ["bar", "heatmap", "hover"] as const) {
    test(`OffscreenCanvas ${scenario} resizes every layer at DPR ${density}`, async ({ page }) => {
      const pageErrors: string[] = [];
      page.on("pageerror", (error) => pageErrors.push(error.message));
      let instrumentedSites = 0;
      // Instrument only the served test response. The installed painter and runtime resize paths run unchanged.
      await page.route("**/*", async (route) => {
        const pathname = new URL(route.request().url()).pathname;
        if (pathname === "/offscreen-resize-test") {
          await route.fulfill({
            contentType: "text/html",
            body: '<!doctype html><html lang="en"><title>Chart resize regression</title><body style="background:white"><canvas aria-label="Chart resize regression" style="width:320px;height:240px"></canvas></body></html>',
          });
          return;
        }
        if (pathname.endsWith("/features/chart-widget/runtime/worker/runtime.ts")) {
          const response = await route.fetch();
          const body = await response.text();
          const marker = /chart\.setOption\(option,\s*\{\s*notMerge:\s*true,\s*lazyUpdate:\s*false,\s*silent:\s*false\s*\}\);/g;
          instrumentedSites = [...body.matchAll(marker)].length;
          await route.fulfill({ response, body: "const resizeTestLayerIds = new WeakMap(); let resizeTestLayerId = 0;\n" + body.replace(marker, (statement) => `${statement}
            setTimeout(() => {
              const zr = chart.getZr();
              ${scenario === "hover" ? `
              const element = zr.storage.getDisplayList().find(element => element.type === "rect");
              if (!element) throw new Error("Hover regression requires a rendered bar");
              element.ensureState("resize-test-hover").hoverLayer = true;
              element.useState("resize-test-hover", false, true);
              zr.refreshImmediately();
              zr.refreshHoverImmediately();` : ""}
              const painter = zr.painter;
              const layers = [];
              painter.eachLayer((layer, zlevel) => {
                if (!resizeTestLayerIds.has(layer)) resizeTestLayerIds.set(layer, ++resizeTestLayerId);
                layers.push({
                instanceId: resizeTestLayerIds.get(layer),
                key: String(zlevel) + ":" + String(layer.id),
                width: layer.dom.width, height: layer.dom.height,
                virtual: !!layer.virtual, hover: zlevel === 100000
                });
              });
              self.postMessage({
                type: "resize_diagnostic", generation,
                root: [painter.getWidth(), painter.getHeight()],
                bitmap: [painter.root.width, painter.root.height],
                layers,
                hoveredElements: zr.storage.getDisplayList().filter(element => element.__inHover).length
              });
            }, 100);
          `) });
          return;
        }
        await route.continue();
      });
      await page.goto("/offscreen-resize-test");
      await page.evaluate(async ({ moduleSource, density }) => {
        const runtimePath = "/src/features/chart-widget/runtime/chart-widget-runtime.ts";
        const { ChartWidgetRuntime } = await import(/* @vite-ignore */ runtimePath);
        const runtime: Harness["runtime"] = new ChartWidgetRuntime();
        window.offscreenResize = { runtime, responses: [] };
        runtime.subscribe((response) => window.offscreenResize.responses.push(response));
        runtime.mount(document.querySelector("canvas")!, {
          moduleSource, density, queries: {}, parameters: {}, variables: {}, locale: "en-US",
          viewport: { width: 320, height: 240 },
        });
      }, {
        moduleSource: scenario === "heatmap" ? heatmapSource
          : scenario === "hover" ? barSource.replace("xAxis: {", "xAxis: { zlevel: 1,") : barSource,
        density,
      });

      const evidenceDirectory = evidencePath("../screenshots/joo-52");
      await mkdir(evidenceDirectory, { recursive: true });
      const samples: { kind: string; width: number; height: number; diagnostic: Diagnostic }[] = [];
      try {
        for (const [kind, width, height] of [
          ["cold", 320, 240], ["render", 400, 408], ["resize", 600, 460], ["render", 360, 300],
        ] as const) {
          const operationId = kind === "cold" ? 0 : await page.evaluate(({ kind, width, height }) => {
            const { runtime } = window.offscreenResize;
            const canvas = document.querySelector("canvas")!;
            canvas.style.width = `${width}px`;
            canvas.style.height = `${height}px`;
            return kind === "resize" ? runtime.resize({ width, height }) : runtime.render({
              queries: {}, parameters: {}, variables: {}, locale: "en-US", viewport: { width, height },
            });
          }, { kind, width, height });
          await expect.poll(() => page.evaluate(({ kind, operationId }) =>
            window.offscreenResize.responses.some((response) =>
              response.type === (kind === "resize" ? "resized" : "rendered")
              && "operationId" in response && response.operationId === operationId),
          { kind, operationId }), { timeout: 15_000 }).toBe(true);
          await expect.poll(() => page.evaluate(() => window.offscreenResize.responses.filter(
            (response) => response.type === "resize_diagnostic",
          ).length)).toBe(samples.length + 1);
          const diagnostic = await page.evaluate(() => window.offscreenResize.responses.filter(
            (response): response is Diagnostic => response.type === "resize_diagnostic",
          ).at(-1)!);
          samples.push({ kind, width, height, diagnostic });
          await page.locator("canvas").screenshot({
            path: `${evidenceDirectory}/${scenario}-dpr${density}-${kind}-${width}x${height}.png`,
          });
          expect(diagnostic.root).toEqual([width, height]);
          expect(diagnostic.bitmap).toEqual([width * density, height * density]);
          expect(diagnostic.layers.length).toBeGreaterThan(0);
          for (const layer of diagnostic.layers) {
            expect([layer.width, layer.height], `Layer ${layer.key} at ${kind}`).toEqual([
              width * density, height * density,
            ]);
          }
          if (scenario === "bar") expect(diagnostic.layers).toHaveLength(1);
          if (scenario === "heatmap") {
            expect(diagnostic.layers.length).toBeGreaterThan(1);
            expect(diagnostic.layers.some((layer) => layer.virtual)).toBe(true);
          }
          if (scenario === "hover") {
            expect(diagnostic.hoveredElements).toBeGreaterThan(0);
            const hoverLayer = diagnostic.layers.find((layer) => layer.hover);
            expect(hoverLayer).toBeDefined();
            expect(hoverLayer?.instanceId).toBe(samples[0].diagnostic.layers.find((layer) => layer.hover)?.instanceId);
          }
        }
        expect(instrumentedSites).toBe(2);
        expect(pageErrors).toEqual([]);
        expect(await page.evaluate(() => window.offscreenResize.responses.filter(
          (response) => response.type === "failed",
        ))).toEqual([]);
      } finally {
        await writeFile(`${evidenceDirectory}/${scenario}-dpr${density}.json`, JSON.stringify({
          density, scenario, instrumentedSites, samples, pageErrors,
          failures: await page.evaluate(() => window.offscreenResize.responses.filter(
            (response) => response.type === "failed",
          )),
        }, null, 2));
        await page.evaluate(() => window.offscreenResize.runtime.dispose());
      }
    });
  }
}
