import { create } from "zustand";

interface SidebarUiState {
  collapsed: boolean;
  toggleCollapsed: () => void;
}

export const useSidebarUiStore = create<SidebarUiState>((set) => ({
  // Direct Pages visits start focused on content; in-app toggles remain in memory.
  collapsed: typeof window !== "undefined" && /^\/workspaces\/[^/]+\/pages(?:\/|$)/.test(window.location.pathname),
  toggleCollapsed: () => set((state) => ({ collapsed: !state.collapsed })),
}));
