import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { lastOpenedLabel, latestVisitedWorkspaceId, readWorkspaceVisits, recordWorkspaceVisit } from "./workspace-visits";
import { createMemoryStorage } from "@/test/memory-storage";

describe("workspace visits", () => {
  const storage = createMemoryStorage();

  beforeEach(() => {
    storage.clear();
    vi.stubGlobal("window", { localStorage: storage });
  });
  afterEach(() => vi.unstubAllGlobals());

  it("keeps visits per user and finds the latest", () => {
    recordWorkspaceVisit("user-1", "workspace-a", new Date("2026-09-29T10:00:00Z"));
    recordWorkspaceVisit("user-1", "workspace-b", new Date("2026-10-02T09:00:00Z"));
    recordWorkspaceVisit("user-2", "workspace-c", new Date("2026-10-02T11:00:00Z"));
    expect(latestVisitedWorkspaceId(readWorkspaceVisits("user-1"))).toBe("workspace-b");
    expect(Object.keys(readWorkspaceVisits("user-2"))).toEqual(["workspace-c"]);
  });

  it("ignores malformed storage and storage that throws", () => {
    storage.setItem("monomer.workspace.visits.user-1", "[1,2]");
    expect(readWorkspaceVisits("user-1")).toEqual({});
    vi.stubGlobal("window", { localStorage: { getItem: () => { throw new Error("blocked"); }, setItem: () => { throw new Error("blocked"); } } });
    expect(readWorkspaceVisits("user-1")).toEqual({});
    expect(() => recordWorkspaceVisit("user-1", "workspace-a", new Date())).not.toThrow();
  });

  it("says when a workspace was last opened", () => {
    const now = new Date(2026, 9, 2, 15, 0);
    expect(lastOpenedLabel(new Date(2026, 9, 2, 8, 0).toISOString(), now)).toBe("Opened today");
    expect(lastOpenedLabel(new Date(2026, 9, 1, 23, 0).toISOString(), now)).toBe("Opened yesterday");
    expect(lastOpenedLabel(new Date(2026, 8, 29, 9, 0).toISOString(), now)).toBe("Sep 29");
    expect(lastOpenedLabel(new Date(2025, 8, 29, 9, 0).toISOString(), now)).toBe("Sep 29, 2025");
  });
});
