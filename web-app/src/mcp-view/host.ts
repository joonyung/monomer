import type { McpUiHostContext } from "@modelcontextprotocol/ext-apps";

export type ViewDisplayMode = "inline" | "fullscreen";

export interface ViewHostState {
  readonly displayMode: ViewDisplayMode;
  readonly fullscreenAvailable: boolean;
  readonly theme: "light" | "dark" | null;
  readonly safeAreaInsets: Readonly<{ top: number; right: number; bottom: number; left: number }>;
}

export const INITIAL_HOST_STATE: ViewHostState = {
  displayMode: "inline",
  fullscreenAvailable: false,
  theme: null,
  safeAreaInsets: { top: 0, right: 0, bottom: 0, left: 0 },
};

function inset(value: unknown): number {
  return typeof value === "number" && Number.isFinite(value) && value > 0 ? value : 0;
}

// Picture-in-picture is not offered, so any mode other than fullscreen is presented inline.
export function hostStateFrom(context: McpUiHostContext | undefined): ViewHostState {
  const insets = context?.safeAreaInsets;
  return {
    displayMode: context?.displayMode === "fullscreen" ? "fullscreen" : "inline",
    fullscreenAvailable: context?.availableDisplayModes?.includes("fullscreen") === true,
    theme: context?.theme === "light" || context?.theme === "dark" ? context.theme : null,
    safeAreaInsets: { top: inset(insets?.top), right: inset(insets?.right), bottom: inset(insets?.bottom), left: inset(insets?.left) },
  };
}

export interface ViewHostStore {
  subscribe(listener: () => void): () => void;
  getSnapshot(): ViewHostState;
  update(context: McpUiHostContext | undefined): void;
  setDisplayMode(mode: ViewDisplayMode): void;
}

export function createViewHostStore(): ViewHostStore {
  let state = INITIAL_HOST_STATE;
  const listeners = new Set<() => void>();
  const publish = (next: ViewHostState) => {
    state = next;
    for (const listener of listeners) listener();
  };
  return {
    subscribe(listener) {
      listeners.add(listener);
      return () => listeners.delete(listener);
    },
    getSnapshot: () => state,
    update: (context) => publish(hostStateFrom(context)),
    setDisplayMode: (displayMode) => publish({ ...state, displayMode }),
  };
}
