import "ses";
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import type { ChartWorkerRequest, ChartWorkerResponse } from "../worker-protocol";

const renderer = vi.hoisted(() => ({
  listeners: new Map<string, (payload: unknown) => void>(),
  setOption: vi.fn(),
  resize: vi.fn(),
  theme: undefined as unknown,
}));
vi.mock("echarts", () => ({
  setPlatformAPI: vi.fn(),
  init: (_canvas: unknown, theme: unknown) => {
    renderer.theme = theme;
    return {
    on: (name: string, queryOrListener: unknown, listener?: (payload: unknown) => void) => {
      renderer.listeners.set(name, listener ?? queryOrListener as (payload: unknown) => void);
    },
    off: vi.fn(),
    dispose: vi.fn(),
    setOption: renderer.setOption,
    resize: renderer.resize,
    getZr: () => ({ handler: { dispatch: () => renderer.listeners.get("click")?.({ name: "KR" }) } }),
    };
  },
}));
import { MONOMER_CHART_THEME } from "./chart-theme";
import { startChartWidgetWorkerRuntime } from "./runtime";

const responses: ChartWorkerResponse[] = [];
const pendingTasks: (() => void)[] = [];
function flushTasks() { for (const task of pendingTasks.splice(0)) task(); }
let receive: ((event: MessageEvent<ChartWorkerRequest>) => void) | null;
const frame = {
  queries: {}, parameters: { entryCountry: "US" }, variables: { country: "US", city: "NYC", amount: "1" },
  locale: "en-US", viewport: { width: 320, height: 200 },
};

beforeAll(() => {
  lockdown({ errorTaming: "unsafe-debug" });
  vi.stubGlobal("OffscreenCanvas", class { getContext() { return null; } });
});
beforeEach(() => {
  pendingTasks.length = 0;
  vi.stubGlobal("setTimeout", (task: () => void) => { pendingTasks.push(task); return pendingTasks.length; });
  responses.length = 0;
  renderer.listeners.clear();
  renderer.setOption.mockReset();
  vi.stubGlobal("self", {
    postMessage: (message: ChartWorkerResponse) => responses.push(message),
    set onmessage(handler: typeof receive) { receive = handler; },
  });
  startChartWidgetWorkerRuntime();
});

afterEach(() => { pendingTasks.length = 0; });

function send(message: ChartWorkerRequest) {
  receive?.({ data: message } as MessageEvent<ChartWorkerRequest>);
  flushTasks();
}
function initialize(handler: string, render = 'return { title: { text: variables.country } };') {
  send({
    type: "initialize", generation: 1, canvas: {} as OffscreenCanvas,
    moduleSource: `let saved; export default defineChart({
      setup({ chart }) { chart.on("click", (event, frame) => { ${handler} }); },
      render({ variables }) { ${render} }
    });`,
    ...frame,
    variables: {
      country: { value: "US", type: "TEXT", nullable: false },
      city: { value: "NYC", type: "TEXT", nullable: true },
      amount: { value: "1", type: "NUMBER", nullable: false },
    },
    density: 1,
    reducedMotion: false,
  });
  expect(responses.filter((response) => response.type === "failed")).toEqual([]);
}
function click() { renderer.listeners.get("click")?.({ name: "KR" }); flushTasks(); }
function submissions() { return responses.filter((response) => response.type === "variables_set"); }
function failures() { return responses.filter((response) => response.type === "failed"); }

describe("Chart event variable submissions", () => {
  it("submits one complete immutable map without changing the event frame", () => {
    initialize(`
      const changes = { country: event.name, city: null };
      frame.setVariables(changes);
      changes.country = "JP";
      if (frame.variables.country !== "US" || frame.variables.city !== "NYC") throw Error("changed frame");
      if (!Object.isFrozen(frame.variables) || Object.getPrototypeOf(frame.variables) !== null) throw Error("mutable frame");
    `);
    click();
    expect(submissions()).toEqual([{ type: "variables_set", generation: 1, renderOperationId: 0, variables: { country: "KR", city: null } }]);
    expect(Object.isFrozen(submissions()[0].variables)).toBe(true);
    expect(failures()).toEqual([]);
  });

  it.each([
    '{}', '[]', 'null', '{ country: "KR", missing: "x" }',
    '{ country: "KR", entryCountry: "JP" }', '{ country: "KR", amount: 2 }',
    '{ country: "KR", amount: "01" }', '{ country: null }', '{ country: undefined }',
    '{ country: "KR", expectedRevision: 99 }',
  ])("rejects the entire invalid map %s before posting", (changes) => {
    initialize(`frame.setVariables(${changes});`);
    click();
    expect(submissions()).toEqual([]);
    expect(failures()).toHaveLength(1);
  });

  it("keeps two calls and two rapid events as four independent submissions from the same frame", () => {
    initialize(`frame.setVariables({ country: "KR" }); frame.setVariables({ country: frame.variables.country });`);
    click(); click();
    expect(submissions().map((response) => [response.renderOperationId, response.variables.country])).toEqual([
      [0, "KR"], [0, "US"], [0, "KR"], [0, "US"],
    ]);
  });

  it("does not retract a submission on a later throw and posts nothing on an earlier throw", () => {
    initialize(`frame.setVariables({ country: "KR" }); throw Error("after");`);
    click();
    expect(submissions()).toHaveLength(1);
    expect(failures()[0]).toMatchObject({ phase: "event", message: "after" });
    responses.length = 0;
    initialize(`throw Error("before"); frame.setVariables({ country: "KR" });`);
    click();
    expect(submissions()).toEqual([]);
    expect(failures()[0]).toMatchObject({ phase: "event", message: "before" });
  });

  it("binds events to the displayed render and preserves it on failed renders and resize", () => {
    initialize(`frame.setVariables({ country: frame.variables.country });`, 'if (variables.country === "FAIL") throw Error("render failure"); return { title: { text: variables.country } };');
    click();
    send({ type: "render", generation: 1, operationId: 7, frame: { ...frame, variables: { ...frame.variables, country: "KR" } } });
    click();
    send({ type: "resize", generation: 1, operationId: 8, viewport: { width: 640, height: 200 }, density: 1 });
    click();
    send({ type: "render", generation: 1, operationId: 9, frame: { ...frame, variables: { ...frame.variables, country: "FAIL" } } });
    click();
    expect(submissions().map((response) => [response.renderOperationId, response.variables.country])).toEqual([
      [0, "US"], [7, "KR"], [7, "KR"], [7, "KR"],
    ]);
  });

  it("drops input tied to an older displayed frame after a newer render, then accepts the matching frame", () => {
    initialize(`frame.setVariables({ country: frame.variables.country });`);
    send({ type: "render", generation: 1, operationId: 7, frame: { ...frame, variables: { ...frame.variables, country: "KR" } } });
    const input = {
      kind: "pointer" as const, eventType: "click" as const, x: 10, y: 10, button: 0, buttons: 0,
      pointerId: 1, altKey: false, ctrlKey: false, metaKey: false, shiftKey: false, trusted: true,
    };
    send({ type: "input", generation: 1, operationId: 8, renderOperationId: 0, input });
    expect(submissions()).toEqual([]);
    expect(responses.at(-1)).toMatchObject({ type: "input_dispatched", operationId: 8 });
    send({ type: "input", generation: 1, operationId: 9, renderOperationId: 7, input });
    expect(submissions()).toEqual([{ type: "variables_set", generation: 1, renderOperationId: 7, variables: { country: "KR" } }]);
  });

  it("rejects a setter retained from an earlier handler even during another handler", () => {
    initialize(`if (!saved) { saved = frame.setVariables; return; } saved({ country: "KR" });`);
    click(); click();
    expect(submissions()).toEqual([]);
    expect(failures()[0]).toMatchObject({ phase: "event", message: expect.stringContaining("current synchronous event handler") });
  });

  it("does not deliver events emitted while an option is being applied", () => {
    initialize(`frame.setVariables({ country: "KR" });`);
    renderer.setOption.mockImplementationOnce(click);
    send({ type: "render", generation: 1, operationId: 3, frame });
    expect(submissions()).toEqual([]);
  });

  it("keeps render frames free of write capabilities", () => {
    initialize(``, 'if ("setVariables" in arguments[0] || typeof variables.country !== "string") throw Error("bad ABI"); return { series: [] };');
    expect(failures()).toEqual([]);
  });
});


describe("Chart event handler isolation", () => {
  it("reports only bounded string messages from thrown values", () => {
    initialize(`const error = new Error("x"); Object.defineProperty(error, "message", { value: { a: 1 } }); throw error;`);
    click();
    expect(failures()[0]).toMatchObject({ phase: "event", message: "Chart Widget source failed" });
    responses.length = 0;
    initialize(`throw "y".repeat(100_000);`);
    click();
    expect((failures()[0] as { message: string }).message).toHaveLength(1_000);
  });

  it("observes a handler as a timed callback and calls it without a receiver", () => {
    send({
      type: "initialize", generation: 1, canvas: {} as OffscreenCanvas,
      moduleSource: `export default defineChart({
        setup({ chart }) { chart.on("click", function () { if (this !== undefined) throw Error("receiver leaked"); }); },
        render() { return {}; }
      });`,
      ...frame,
      variables: { country: { value: "US", type: "TEXT", nullable: false } },
      density: 1,
      reducedMotion: false,
    });
    responses.length = 0;
    renderer.listeners.get("click")?.({ name: "KR" });
    expect(responses.map((response) => response.type)).toEqual(["callback_started"]);
    flushTasks();
    expect(responses.map((response) => response.type)).toEqual(["callback_started", "callback_completed"]);
    expect(failures()).toEqual([]);
  });
});

it("acknowledges completion only in the next Worker task", () => {
  receive?.({ data: {
    type: "initialize", generation: 2, canvas: {} as OffscreenCanvas,
    moduleSource: "export default defineChart({ render() { return {}; } });",
    ...frame, variables: {}, density: 1, reducedMotion: false,
  } } as unknown as MessageEvent<ChartWorkerRequest>);
  expect(responses).toEqual([]);
  expect(pendingTasks).toHaveLength(2);
  flushTasks();
  expect(responses.map((response) => response.type)).toEqual(["initialized", "rendered"]);
});

it.each(["legendselectchanged", "datazoom"])("reprojects the accepted frame after %s even when submission is rejected", async eventName => {
  send({
    type: "initialize", generation: 1, canvas: {} as OffscreenCanvas,
    moduleSource: `export default defineChart({
      setup({chart}) { chart.on("${eventName}", (event, frame) => frame.setVariables({country: 99})); },
      render({variables}) { return {legend:{selected:{Sales:variables.country === "US"}},dataZoom:[{start:25,end:75}]}; }
    });`,
    ...frame, variables: { country: { value: "US", type: "TEXT" } }, density: 1, reducedMotion: false,
  });
  renderer.listeners.get(eventName)!({});
  await Promise.resolve();
  flushTasks();
  expect(submissions()).toEqual([]);
  expect(failures()[0]).toMatchObject({ phase: "event" });
  expect(renderer.setOption).toHaveBeenLastCalledWith(
    { legend: { selected: { Sales: true } }, dataZoom: [{ start: 25, end: 75 }] },
    { notMerge: true, lazyUpdate: false, silent: true },
  );
});

it("does not replay an old event option over a newer accepted frame", async () => {
  send({
    type: "initialize", generation: 1, canvas: {} as OffscreenCanvas,
    moduleSource: `export default defineChart({ setup({chart}) { chart.on("legendselectchanged", () => {}); }, render({variables}) { return { title:{text:variables.country} }; } });`,
    ...frame, variables: { country: { value: "US", type: "TEXT" } }, density: 1, reducedMotion: false,
  });
  renderer.listeners.get("legendselectchanged")!({});
  send({ type: "render", generation: 1, operationId: 3, frame: { ...frame, variables: { country: "KR" } } });
  const applications = renderer.setOption.mock.calls.length;
  await Promise.resolve();
  flushTasks();
  expect(renderer.setOption).toHaveBeenCalledTimes(applications);
  expect(renderer.setOption.mock.lastCall?.[0]).toMatchObject({ title: { text: "KR" } });
});

describe("Host presentation policy", () => {
  const source = `export default defineChart({
    setup({ chart }) { chart.on("click", (event, frame) => frame.setVariables({ country: "KR" })); },
    render({ variables }) { return { animation: true, title: { text: variables.country } }; }
  });`;
  function initializeWithMotion(reducedMotion: boolean) {
    send({
      type: "initialize", generation: 1, canvas: {} as OffscreenCanvas, moduleSource: source,
      ...frame, variables: { country: { value: "US", type: "TEXT", nullable: false } }, density: 1, reducedMotion,
    });
    expect(failures()).toEqual([]);
  }

  it("initializes ECharts with the immutable Monomer theme", () => {
    initializeWithMotion(false);
    expect(renderer.theme).toBe(MONOMER_CHART_THEME);
    expect(Object.isFrozen(renderer.theme)).toBe(true);
  });

  it("keeps the source animation choice when motion is allowed", () => {
    initializeWithMotion(false);
    expect(renderer.setOption.mock.lastCall?.[0]).toMatchObject({ animation: true, title: { text: "US" } });
  });

  it("forces animation off under reduced motion without changing the source option", () => {
    initializeWithMotion(true);
    expect(renderer.setOption.mock.lastCall?.[0]).toMatchObject({ animation: false, title: { text: "US" } });
    send({ type: "render", generation: 1, operationId: 1, frame: { ...frame, variables: { country: "JP" } } });
    expect(renderer.setOption.mock.lastCall?.[0]).toMatchObject({ animation: false, title: { text: "JP" } });
  });

  it("reapplies the current frame on a preference change without replacing the render operation", () => {
    initializeWithMotion(false);
    send({ type: "render", generation: 1, operationId: 1, frame: { ...frame, variables: { country: "JP" } } });
    send({ type: "presentation", generation: 1, operationId: 2, reducedMotion: true });
    expect(renderer.setOption.mock.lastCall).toEqual([
      { animation: false, axisPointer: { animation: false }, title: { text: "JP" } },
      { notMerge: true, lazyUpdate: false, silent: true },
    ]);
    expect(responses.at(-1)).toEqual({ type: "presented", generation: 1, operationId: 2 });
    click();
    expect(submissions().at(-1)).toMatchObject({ renderOperationId: 1 });
    send({ type: "presentation", generation: 1, operationId: 3, reducedMotion: false });
    expect(renderer.setOption.mock.lastCall?.[0]).toMatchObject({ animation: true });
  });

  it("ignores a preference change from another generation", () => {
    initializeWithMotion(false);
    const applications = renderer.setOption.mock.calls.length;
    send({ type: "presentation", generation: 7, operationId: 2, reducedMotion: true });
    expect(renderer.setOption).toHaveBeenCalledTimes(applications);
    expect(responses.some((response) => response.type === "presented")).toBe(false);
  });
});
