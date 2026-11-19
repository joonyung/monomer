import { describe, expect, it } from "vitest";
import { statusLine, type StatusLineInput } from "./status";

const NOW = 1_000_000_000;
const line = (input: Partial<StatusLineInput>) => statusLine({ targetKind: "DRAFT", stage: "ready", live: "live", runningQueries: 0, updatedAt: NOW, now: NOW, ...input });

describe("status line", () => {
  it("describes a live Draft by its last change", () => {
    expect(line({})).toBe("Draft · Updated Just now");
    expect(line({ updatedAt: NOW - 3 * 60_000 })).toBe("Draft · Updated 3m ago");
    expect(line({ updatedAt: NOW - 2 * 60 * 60_000 })).toBe("Draft · Updated 2h ago");
    expect(line({ runningQueries: 2 })).toBe("Draft · 2 queries running");
    expect(line({ runningQueries: 1 })).toBe("Draft · 1 query running");
  });

  it("describes a Session by its runs", () => {
    expect(line({ targetKind: "SESSION", runningQueries: 1 })).toBe("Session · 1 query running");
    expect(line({ targetKind: "SESSION", live: "complete" })).toBe("Session · Ready");
  });

  it("names stopped, paused and superseded views", () => {
    expect(line({ live: "paused" })).toBe("Draft · Paused");
    expect(line({ live: "failed" })).toBe("Draft · Updates stopped");
    expect(line({ live: "superseded" })).toBe("Draft · Not updating");
    expect(line({ stage: "loading" })).toBe("Draft · Loading…");
    expect(line({ stage: "error" })).toBe("Draft · Couldn't load");
    expect(line({ stage: "out-of-date", targetKind: null })).toBe("Out of date");
    expect(line({ stage: "waiting", targetKind: null })).toBe("Loading…");
  });
});
