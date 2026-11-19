import { flushSync } from "react-dom";
import { getCaptureRenderer } from "./stage";
import { captureBounds, type CaptureRect } from "./geometry";
import { captureSourceLine, resultsFromLabel, type CaptureProvenance } from "./provenance";
export interface CaptureWidget extends CaptureRect { id: string; node: HTMLElement }
export function measureWidgets(root: HTMLElement): CaptureWidget[] {
  const origin = root.getBoundingClientRect();
  return [...root.querySelectorAll<HTMLElement>("[data-capture-widget-id]")].flatMap(node => {
    const r = node.getBoundingClientRect();
    return r.width && r.height ? [{ id: node.dataset.captureWidgetId!, node, x: r.left - origin.left, y: r.top - origin.top, width: r.width, height: r.height }] : [];
  });
}
function textNode(tag: "div" | "span", className: string, text: string) {
  const node = document.createElement(tag);
  node.className = className;
  node.textContent = text;
  return node;
}
function provenanceHeader(provenance: CaptureProvenance) {
  const header = document.createElement("div");
  header.className = "flex flex-col gap-2 bg-background";
  header.append(textNode("div", "type-display wrap-anywhere text-heading", provenance.title));
  if (provenance.description) header.append(textNode("div", "type-body wrap-anywhere text-foreground-secondary", provenance.description));
  if (provenance.inputs.length || provenance.resultsAt) {
    const context = document.createElement("div");
    context.className = "mt-1 flex flex-wrap items-baseline gap-x-5 gap-y-1 type-meta tabular-nums text-foreground-secondary";
    if (provenance.inputs.length) context.append(textNode("span", "type-group text-foreground-secondary", "Inputs"));
    for (const input of provenance.inputs) {
      const item = textNode("span", "wrap-anywhere", `${input.label}: `);
      item.append(textNode("span", "text-foreground", input.value));
      context.append(item);
    }
    if (provenance.resultsAt) context.append(textNode("span", "", resultsFromLabel(provenance.resultsAt, Date.now())));
    header.append(context);
  }
  return header;
}
function provenanceFooter(provenance: CaptureProvenance) {
  return textNode("div", "border-t border-border bg-background pt-3 type-meta text-foreground-secondary", captureSourceLine(provenance.source));
}
export async function exportWidgets(root: HTMLElement, ids: Set<string>, provenance: CaptureProvenance) {
  const render = getCaptureRenderer(root);
  if (!render) throw new Error("The dashboard is not ready to capture. Please try again.");
  const stage = document.createElement("div");
  stage.setAttribute("aria-hidden", "true");
  stage.inert = true;
  stage.style.cssText = "position:fixed;left:-100000px;top:0;width:1200px;pointer-events:none;background:var(--background);";
  const header = provenanceHeader(provenance);
  const footer = provenanceFooter(provenance);
  const body = document.createElement("div");
  body.style.width = "1200px";
  stage.append(header, body, footer);
  document.body.append(stage);
  try {
    flushSync(() => render(body));
    const deadline = performance.now() + 20000;
    await beforeDeadline(document.fonts.ready, deadline, "Fonts could not finish loading. Please try again.");
    const images = [...body.querySelectorAll<HTMLImageElement>('[data-dashboard-text] img')];
    await beforeDeadline(Promise.all(images.map(image => image.decode())), deadline,
      "Some images could not finish loading. Please try again.");
    let settled = 0;
    while (settled < 3) {
      await beforeDeadline(new Promise<void>(resolve => requestAnimationFrame(() => resolve())), deadline,
        "Some charts could not finish rendering. Please try again.");
      const charts = [...body.querySelectorAll<HTMLElement>('[data-testid="dashboard-chart-widget"]')];
      settled = charts.every(chart => chart.dataset.chartStatus === "ready") ? settled + 1 : 0;
    }
    return await exportRenderedWidgets(body, ids, header, footer);
  } finally {
    flushSync(() => render(null));
    stage.remove();
  }
}
async function beforeDeadline<T>(work: Promise<T>, deadline: number, message: string): Promise<T> {
  let timer: ReturnType<typeof setTimeout> | undefined;
  try {
    return await Promise.race([work, new Promise<never>((_, reject) => {
      timer = setTimeout(() => reject(new Error(message)), Math.max(0, deadline - performance.now()));
    })]);
  } catch {
    throw new Error(message);
  } finally {
    clearTimeout(timer);
  }
}
async function exportRenderedWidgets(root: HTMLElement, ids: Set<string>, header: HTMLElement, footer: HTMLElement) {
  const available = measureWidgets(root);
  const all = available.every(w => ids.has(w.id));
  // All preserves the entire dashboard layout, including section spacing and dividers.
  const widgets: CaptureWidget[] = all
    ? [{ id: "__dashboard__", node: root, x: 0, y: 0, width: root.offsetWidth, height: root.offsetHeight }]
    : available.filter(w => ids.has(w.id));
  const bounds = captureBounds(widgets);
  header.style.width = `${bounds.width}px`;
  footer.style.width = `${bounds.width}px`;
  const headerHeight = header.offsetHeight;
  const footerHeight = footer.offsetHeight;
  const padding = 24;
  const gap = 24;
  const bodyTop = padding + headerHeight + gap;
  // Render at 4x; bound long reports by both edge length and total canvas memory.
  const width = bounds.width + padding * 2;
  const height = bodyTop + bounds.height + gap + footerHeight + padding;
  const scale = Math.min(4, 8192 / width, 8192 / height, Math.sqrt(32_000_000 / (width * height)));
  const canvas = document.createElement("canvas");
  canvas.width = Math.ceil(width * scale);
  canvas.height = Math.ceil(height * scale);
  const context = canvas.getContext("2d");
  if (!context) throw new Error("This browser could not create an image.");
  context.fillStyle = getComputedStyle(root).getPropertyValue("--background").trim() || "white";
  context.fillRect(0, 0, canvas.width, canvas.height);
  const { toCanvas, getFontEmbedCSS } = await import("html-to-image");
  const fontEmbedCSS = await getFontEmbedCSS(root);
  const draw = async (node: HTMLElement, x: number, y: number, nodeWidth: number, nodeHeight: number, style?: Partial<CSSStyleDeclaration>) => {
    const image = await toCanvas(node, {
      pixelRatio: scale, fontEmbedCSS, width: nodeWidth, height: nodeHeight, style,
      filter: child => !(child instanceof HTMLElement && (child.hasAttribute("data-capture-overlay") || child.classList.contains("change-highlight-overlay"))),
    });
    context.drawImage(image, x * scale, y * scale, nodeWidth * scale, nodeHeight * scale);
  };
  await draw(header, padding, padding, bounds.width, headerHeight);
  for (const widget of widgets) {
    const charts = widget.node.querySelectorAll<HTMLElement>('[data-testid="dashboard-chart-widget"]');
    if ([...charts].some(chart => chart.dataset.chartStatus !== "ready")) throw new Error("Wait for the selected charts to finish loading, then try again.");
    await draw(widget.node, widget.x - bounds.x + padding, widget.y - bounds.y + bodyTop, widget.width, widget.height,
      widgets.length === 1 && !all ? { border: "0", borderStyle: "none", boxShadow: "none" } : undefined);
  }
  await draw(footer, padding, bodyTop + bounds.height + gap, bounds.width, footerHeight);
  const blob = await new Promise<Blob>((resolve, reject) => canvas.toBlob(value => value ? resolve(value) : reject(new Error("The image could not be created.")), "image/png"));
  return blob;
}
