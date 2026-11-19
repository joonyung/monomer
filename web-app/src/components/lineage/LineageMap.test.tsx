import type { ComponentType, ReactNode } from "react";
import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { Node } from "@xyflow/react";
import type { LineageFocusCone } from "@/api/lineage";
import { makeLineageFixtureGraph } from "./LineageAccessFixture";
import { LineageMap } from "./LineageMap";

const captured = vi.hoisted(() => ({
  nodes: [] as Node[],
  flowProps: {} as Record<string, unknown>,
  effects: [] as Array<() => void | (() => void)>,
  canvas: { clientWidth: 1000, clientHeight: 480 },
  initialized: true,
  viewportInitialized: true,
  viewport: { x: 0, y: 0, zoom: 1 },
  readinessOptions: undefined as { includeHiddenNodes?: boolean } | undefined,
  desktop: true,
  fitView: vi.fn(),
  setCenter: vi.fn(),
  getNode: vi.fn(),
  resize: null as (() => void) | null,
  fitAction: null as (() => void) | null,
  observe: vi.fn(),
  disconnect: vi.fn(),
  addMediaListener: vi.fn(),
  removeMediaListener: vi.fn(),
  frames: new Map<number, () => void>(),
  frameId: 0,
  requestFrame: vi.fn(),
  cancelFrame: vi.fn(),
  refIndex: 0,
  observedEffects: [] as Array<readonly unknown[] | undefined>,
}));
vi.mock("react", async (importOriginal) => ({
  ...(await importOriginal<typeof import("react")>()),
  useRef: (initial: unknown) => ({ current: captured.refIndex++ === 0 ? captured.canvas : initial }),
  useEffect: (effect: () => void | (() => void), dependencies?: readonly unknown[]) => { captured.effects.push(effect); captured.observedEffects.push(dependencies); },
}));
vi.mock("@/lib/navigation", () => ({ Link: ({ href, children, ...props }: { href: string; children: ReactNode }) => <a href={href} {...props}>{children}</a> }));
vi.mock("@xyflow/react", async (importOriginal) => {
  const original = await importOriginal<typeof import("@xyflow/react")>();
  return {
  ...original,
  ReactFlowProvider: ({ children }: { children: ReactNode }) => <>{children}</>,
  ReactFlow: ({ nodes, nodeTypes, children, ...props }: { nodes: Node[]; nodeTypes: Record<string, ComponentType<{ data: object }>>; children: ReactNode }) => {
    captured.nodes = nodes;
    captured.flowProps = props;
    return <div data-testid="flow">{nodes.map((node) => <div key={node.id}>{createElement(nodeTypes[node.type!], { data: node.data })}</div>)}{children}</div>;
  },
  MiniMap: () => <div data-testid="minimap" />,
  Controls: ({ children }: { children: ReactNode }) => <>{children}</>,
  ControlButton: ({ children, onClick, ...props }: { children: ReactNode; onClick: () => void }) => {
    captured.fitAction = onClick;
    return <button onClick={onClick} {...props}>{children}</button>;
  },
  useNodesInitialized: (options?: { includeHiddenNodes?: boolean }) => {
    captured.readinessOptions = options;
    return options?.includeHiddenNodes ? captured.initialized : false;
  },
  useReactFlow: () => ({ fitView: captured.fitView, getNode: captured.getNode, getNodesBounds: (nodes: Node[]) => original.getNodesBounds(nodes, { nodeLookup: new Map(nodes.map((node) => [node.id, { ...node, measured: { width: node.width, height: node.height }, internals: { positionAbsolute: node.position, z: 0, userNode: node } }])), nodeOrigin: [0, 0] }), setCenter: captured.setCenter, viewportInitialized: captured.viewportInitialized }),
  useStore: (selector: (state: { width: number; height: number }) => unknown) => selector({ width: captured.canvas.clientWidth, height: captured.canvas.clientHeight }),
  useViewport: () => captured.viewport,
  Handle: () => null,
  Position: { Left: "left", Right: "right" },
}; });

function mapGraph() {
  const current = makeLineageFixtureGraph("ready")!;
  current.dashboardUsages = [{ dashboardId: "overview", title: "Northstar Commerce · Business overview for all completed orders", queryNames: ["orders"], metricIds: [current.focus.resource.id] }];
  return current;
}

function renderMap(current: LineageFocusCone = mapGraph()) {
  captured.refIndex = 0;
  return renderToStaticMarkup(<LineageMap workspaceId={current.workspaceId} graph={current} onSetFocus={vi.fn()} />);
}

function mountMapEffects() {
  const cleanups = captured.effects.map((effect) => effect()).filter((cleanup): cleanup is () => void => Boolean(cleanup));
  return () => { for (const cleanup of cleanups) cleanup(); };
}

function flushFrames() {
  const callbacks = [...captured.frames.values()];
  captured.frames.clear();
  for (const callback of callbacks) callback();
}

describe("Lineage map presentation", () => {
  beforeEach(() => {
    captured.nodes = [];
    captured.effects = [];
    captured.flowProps = {};
    captured.initialized = true;
    captured.viewportInitialized = true;
    captured.viewport = { x: 0, y: 0, zoom: 1 };
    captured.readinessOptions = undefined;
    captured.desktop = true;
    captured.canvas = { clientWidth: 1000, clientHeight: 480 };
    captured.resize = null;
    captured.fitAction = null;
    captured.frames.clear();
    captured.frameId = 0;
    captured.refIndex = 0;
    captured.observedEffects = [];
    for (const mock of [captured.fitView, captured.setCenter, captured.getNode, captured.observe, captured.disconnect, captured.addMediaListener, captured.removeMediaListener, captured.requestFrame, captured.cancelFrame]) mock.mockReset();
    captured.getNode.mockImplementation((id: string) => captured.nodes.find((node) => node.id === id));
    captured.requestFrame.mockImplementation((callback: () => void) => { const id = ++captured.frameId; captured.frames.set(id, callback); return id; });
    captured.cancelFrame.mockImplementation((id: number) => { captured.frames.delete(id); });
    vi.stubGlobal("window", { matchMedia: () => ({ matches: captured.desktop, addEventListener: captured.addMediaListener, removeEventListener: captured.removeMediaListener }), requestAnimationFrame: captured.requestFrame, cancelAnimationFrame: captured.cancelFrame });
    vi.stubGlobal("ResizeObserver", class {
      constructor(callback: () => void) { captured.resize = callback; }
      observe = captured.observe;
      disconnect = captured.disconnect;
    });
  });
  afterEach(() => { vi.unstubAllGlobals(); });

  it("leaves ordinary wheel scrolling to the page and limits zoom to Controls or Control+wheel", () => {
    renderMap();
    expect(captured.flowProps).toMatchObject({
      zoomOnScroll: false, panOnScroll: false, preventScrolling: false,
      zoomOnDoubleClick: false, zoomOnPinch: true, zoomActivationKeyCode: "Control",
      panActivationKeyCode: null, minZoom: 0.5,
      fitViewOptions: { padding: 0.12, minZoom: 0.6, maxZoom: 1 },
    });
    expect(captured.flowProps).not.toHaveProperty("fitView", true);
    expect(captured.readinessOptions).toEqual({ includeHiddenNodes: true });
  });

  it("schedules desktop fits outside observer delivery, coalesces size changes, and ignores unchanged sizes", () => {
    renderMap();
    const cleanup = mountMapEffects();
    expect(captured.observe).toHaveBeenCalledWith(captured.canvas);
    expect(captured.fitView).not.toHaveBeenCalled();
    captured.resize?.();
    expect(captured.requestFrame).toHaveBeenCalledOnce();
    flushFrames();
    expect(captured.fitView).toHaveBeenCalledWith({ padding: 0.12, minZoom: 0.6, maxZoom: 1 });
    captured.canvas.clientWidth = 660;
    captured.resize?.();
    captured.resize?.();
    captured.canvas.clientWidth = 600;
    captured.resize?.();
    expect(captured.requestFrame).toHaveBeenCalledTimes(2);
    expect(captured.fitView).toHaveBeenCalledOnce();
    flushFrames();
    expect(captured.fitView).toHaveBeenCalledTimes(2);
    captured.resize?.();
    expect(captured.requestFrame).toHaveBeenCalledTimes(2);
    captured.addMediaListener.mock.calls[0][1]();
    flushFrames();
    expect(captured.fitView).toHaveBeenCalledTimes(3);
    if (cleanup) cleanup();
    expect(captured.disconnect).toHaveBeenCalledOnce();
    expect(captured.removeMediaListener).toHaveBeenCalledWith("change", captured.addMediaListener.mock.calls[0][1]);
  });

  it("cancels a pending fit when its canvas is removed", () => {
    renderMap();
    const cleanup = mountMapEffects();
    expect(captured.frames.size).toBe(1);
    if (cleanup) cleanup();
    expect(captured.cancelFrame).toHaveBeenCalledWith(1);
    flushFrames();
    expect(captured.fitView).not.toHaveBeenCalled();
    expect(captured.setCenter).not.toHaveBeenCalled();
  });

  it("refreshes fit readiness without registering an ancestor observer during node measurement", () => {
    renderMap();
    mountMapEffects();
    flushFrames();
    const canvasDelivery = captured.resize;
    captured.effects[0]();
    expect(captured.observe).toHaveBeenCalledOnce();
    expect(captured.disconnect).not.toHaveBeenCalled();
    expect(captured.resize).toBe(canvasDelivery);
    expect(captured.observedEffects[1]).toEqual([captured.observedEffects[0]![1]]);
    expect(captured.fitView).toHaveBeenCalledOnce();
    flushFrames();
    expect(captured.fitView).toHaveBeenCalledTimes(2);
  });

  it("centers the focus at readable zoom 1 below lg instead of fitting the entire graph", () => {
    captured.desktop = false;
    renderMap();
    mountMapEffects();
    flushFrames();
    const focus = captured.nodes.find((node) => node.id === "METRIC:fixture-metric") ?? captured.nodes.find((node) => node.type === "semanticMap" && node.data.selected)!;
    expect(captured.setCenter).toHaveBeenCalledWith(focus.position.x + focus.width! / 2, focus.position.y + focus.height! / 2, { zoom: 1 });
    expect(captured.fitView).not.toHaveBeenCalled();
    captured.canvas.clientWidth = 390;
    captured.resize?.();
    expect(captured.setCenter).toHaveBeenCalledOnce();
    flushFrames();
    expect(captured.setCenter).toHaveBeenCalledTimes(2);
    captured.fitAction?.();
    expect(captured.setCenter).toHaveBeenCalledTimes(3);
    expect(captured.fitView).not.toHaveBeenCalled();
  });

  it("waits for measurable nodes and canvas before adjusting the viewport", () => {
    captured.initialized = false;
    renderMap();
    mountMapEffects();
    captured.resize?.();
    flushFrames();
    expect(captured.fitView).not.toHaveBeenCalled();
    expect(captured.setCenter).not.toHaveBeenCalled();
    captured.effects = [];
    captured.initialized = true;
    captured.viewportInitialized = false;
    renderMap();
    mountMapEffects();
    flushFrames();
    expect(captured.fitView).not.toHaveBeenCalled();
    captured.effects = [];
    captured.viewportInitialized = true;
    captured.canvas.clientWidth = 0;
    renderMap();
    mountMapEffects();
    flushFrames();
    expect(captured.fitView).not.toHaveBeenCalled();
  });

  it("compacts node-bearing lanes while keeping headings over their actual resource kinds", () => {
    renderMap();
    const headers = captured.nodes.filter((node) => node.type === "laneHeader");
    expect(headers.some((node) => node.data.kind === "RELATION")).toBe(false);
    expect(headers.find((node) => node.data.kind === "SEMANTIC_MODEL")?.position.x).toBe(0);
    for (const kind of ["SEMANTIC_MODEL", "METRIC", "DASHBOARD"]) {
      const resources = captured.nodes.filter((node) => node.type === "semanticMap" && node.data.kind === kind);
      expect(resources.length).toBeGreaterThan(0);
      for (const node of resources) expect(headers.some((header) => header.data.kind === kind && header.position.x === node.position.x)).toBe(true);
    }
    expect(headers.map((header) => header.position.x)).toEqual([0, 280, 560, 840]);
    expect(headers.every((node) => node.selectable === false && node.focusable === false)).toBe(true);
  });

  it("includes Table when adjacent to the focus and omits Used in without visible Dashboard nodes", () => {
    renderMap(makeLineageFixtureGraph("peer")!);
    const headers = captured.nodes.filter((node) => node.type === "laneHeader");
    expect(headers.map((header) => header.data.kind)).toEqual(["RELATION", "SEMANTIC_MODEL", "METRIC"]);
    for (const node of captured.nodes.filter((node) => node.type === "semanticMap")) {
      expect(headers.some((header) => header.data.kind === node.data.kind && header.position.x === node.position.x)).toBe(true);
    }
  });

  it("shows the minimap below the canvas only when the rendered nodes exceed its bounds", () => {
    const markup = renderMap();
    expect(markup).toMatch(/data-testid="lineage-map-canvas"[^>]*>.*?<\/div><div[^>]*data-testid="lineage-map-overview"><div data-testid="minimap"/);
    expect(markup.indexOf('data-testid="lineage-map-overview"')).toBeGreaterThan(markup.indexOf('data-testid="flow"'));
    captured.viewport = { x: 50, y: 80, zoom: 0.6 };
    expect(renderMap()).not.toContain('data-testid="lineage-map-overview"');
    captured.viewport.x = -10;
    expect(renderMap()).toContain('data-testid="lineage-map-overview"');
    captured.viewport = { x: 50, y: -40, zoom: 0.6 };
    expect(renderMap()).toContain('data-testid="lineage-map-overview"');
    captured.viewport = { x: 50, y: 450, zoom: 0.6 };
    expect(renderMap()).toContain('data-testid="lineage-map-overview"');
    captured.viewport = { x: 50, y: 80, zoom: 1.5 };
    expect(renderMap()).toContain('data-testid="lineage-map-overview"');
  });

  it("withholds the minimap until canvas, nodes, and viewport are ready", () => {
    captured.initialized = false;
    expect(renderMap()).not.toContain('data-testid="lineage-map-overview"');
    captured.initialized = true;
    captured.viewportInitialized = false;
    expect(renderMap()).not.toContain('data-testid="lineage-map-overview"');
    captured.viewportInitialized = true;
    captured.canvas.clientWidth = 0;
    expect(renderMap()).not.toContain('data-testid="lineage-map-overview"');
  });

  it("uses the shared neutral kind icon tiles on nodes without a tinted canvas", () => {
    const markup = renderMap();
    const nodeCount = captured.nodes.filter((node) => node.type === "semanticMap").length;
    expect(markup.split(">Affects<")[0].match(/h-6 w-6 shrink-0 place-items-center/g)).toHaveLength(nodeCount);
    expect(markup).toContain("lucide-layers");
    expect(markup).toContain("lucide-chart-column");
    expect(markup).toContain("lucide-layout-dashboard");
    expect(markup).not.toContain("bg-surface/20");
    expect(renderMap(makeLineageFixtureGraph("peer")!)).toContain("lucide-table");
  });

  it("wraps Dashboard names to two lines and provides the full title on the selectable node", () => {
    const markup = renderMap();
    const label = "Northstar Commerce · Business overview for all completed orders";
    expect(markup).toContain(`title="${label}"`);
    expect(markup).toContain(`aria-label="Select ${label}"`);
    const labelClasses = markup.match(new RegExp(`class="([^"]*line-clamp-2 break-words[^"]*)">${label}`))?.[1].split(" ");
    expect(labelClasses).toBeDefined();
    expect(labelClasses).not.toContain("block");
    expect(captured.nodes.find((node) => node.id === "DASHBOARD:overview")?.height).toBe(96);
  });
});
