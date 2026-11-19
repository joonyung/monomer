import { beforeEach, describe, expect, it } from "vitest";
import { useSidebarUiStore } from "./sidebarUiState";

describe("sidebar UI state", () => {
  beforeEach(() => {
    useSidebarUiStore.setState({ collapsed: false });
  });

  it("keeps the collapsed preference until the browser app session resets", () => {
    useSidebarUiStore.getState().toggleCollapsed();

    expect(useSidebarUiStore.getState().collapsed).toBe(true);
  });
});
