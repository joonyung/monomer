const renderers = new WeakMap<HTMLElement, (host: HTMLElement | null) => void>();
export function registerCaptureRenderer(node: HTMLElement, render: (host: HTMLElement | null) => void) {
  renderers.set(node, render);
  return () => { renderers.delete(node); };
}
export function getCaptureRenderer(root: HTMLElement) {
  const layout = root.querySelector<HTMLElement>(".dashboard-layout-container");
  return layout ? renderers.get(layout) : undefined;
}
