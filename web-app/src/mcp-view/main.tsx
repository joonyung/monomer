import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { App } from "@modelcontextprotocol/ext-apps";
import "@/pages/globals.css";
import "./dashboard-view.css";
import { CAPABILITY_PROBE_TIMEOUT_MS, createBlobProbeWorker, pageTransfersCanvas, probeWorkerCapability, widgetRuntimePlan, type WidgetRuntimePlan } from "./capability";
import { createDashboardViewController } from "./controller";
import { DashboardView, type ViewHostActions } from "./DashboardView";
import { registerViewFonts } from "./fonts";
import { createViewHostStore } from "./host";
import { browserSupersessionChannel } from "./supersession";
import { createInlineChartWidgetWorker, createInlineTableWidgetWorker } from "./workers";

const VIEW_APP_INFO = { name: "Monomer Dashboard", version: "1" };
const WORKER_FACTORIES = { chart: createInlineChartWidgetWorker, table: createInlineTableWidgetWorker };

function createInstanceId(): string {
  return typeof crypto.randomUUID === "function" ? crypto.randomUUID() : `${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}`;
}

// Polling runs only while the document is visible and the view intersects the viewport. A host
// whose observer never reports leaves the view treated as visible.
function viewActivity(element: Element): () => boolean {
  let intersecting = true;
  if (typeof IntersectionObserver === "function") {
    new IntersectionObserver((entries) => {
      for (const entry of entries) intersecting = entry.intersectionRatio > 0;
    }).observe(element);
  }
  return () => document.visibilityState === "visible" && intersecting;
}

registerViewFonts(document.fonts);
const rootElement = document.getElementById("root");
if (!rootElement) throw new Error("Root element was not found.");

const hostStore = createViewHostStore();
const app = new App(VIEW_APP_INFO, { availableDisplayModes: ["inline", "fullscreen"] });
const controller = createDashboardViewController({
  callServerTool: (request) => app.callServerTool(request),
  clock: { now: () => Date.now(), setTimeout: (callback, delayMs) => window.setTimeout(callback, delayMs), clearTimeout: (handle) => window.clearTimeout(handle as number) },
  instanceId: createInstanceId(),
  isViewActive: viewActivity(rootElement),
  createChannel: browserSupersessionChannel(),
});
const actions: ViewHostActions = {
  openLink: (url) => {
    void app.openLink({ url }).catch(() => undefined);
  },
  requestDisplayMode: (mode) => {
    void app.requestDisplayMode({ mode }).then((result) => hostStore.setDisplayMode(result.mode === "fullscreen" ? "fullscreen" : "inline")).catch(() => undefined);
  },
};

const root = createRoot(rootElement);
let runtimePlan: WidgetRuntimePlan | null = null;
let tornDown = false;
const render = () => {
  if (tornDown) return;
  root.render(<StrictMode>
    <DashboardView controller={controller} hostStore={hostStore} actions={actions} workerFactories={WORKER_FACTORIES} runtimePlan={runtimePlan} />
  </StrictMode>);
};
const teardown = () => {
  if (tornDown) return;
  tornDown = true;
  controller.teardown();
  root.unmount();
};

app.ontoolresult = (result) => controller.acceptToolResult(result);
app.ontoolcancelled = () => controller.closeBeforeShow("The dashboard was not shown.");
app.onhostcontextchanged = () => hostStore.update(app.getHostContext());
app.onteardown = async () => {
  teardown();
  return {};
};
window.addEventListener("pagehide", teardown);

render();
void probeWorkerCapability({ createProbeWorker: createBlobProbeWorker, pageTransfersCanvas: pageTransfersCanvas(), timeoutMs: CAPABILITY_PROBE_TIMEOUT_MS }).then((capability) => {
  runtimePlan = widgetRuntimePlan(capability);
  render();
});
void app.connect().then(() => {
  hostStore.update(app.getHostContext());
  controller.connected();
}, () => controller.closeBeforeShow("This view couldn't connect to the chat."));
