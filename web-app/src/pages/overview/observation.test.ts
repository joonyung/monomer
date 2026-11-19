import { describe, expect, it } from "vitest";
import type { ActivityItem } from "@/api/overview";
import { activityId, insertArrivals, pruneRuns, reconcileRuns, type ObservedRun } from "./observation";
const item = (id: string, state: ActivityItem["state"] = "RUNNING"): ActivityItem => ({ id, kind: "EXPLORE", title: "SQL query", description: "Explore", href: `/explore?run=${id}`, actorId: "person", actorName: "Alex", actorKind: "USER", credentialSurface: "AGENT", agentApp: null, failureReason: null, state, startedAt: "2026-09-09T00:00:00Z", occurredAt: "2026-09-09T00:01:00Z" });
const row = (id: string): ObservedRun => ({ item: item(id), terminalObservedAt: null, lastObservedAt: 0 });

describe("Overview observed run reconciliation", () => {
  it("retains the first terminal observation and expires after 60 seconds unless protected", () => {
    const first = reconcileRuns([row("a")], [], [item("a", "SUCCEEDED")], [activityId(item("a"))], [], 1000).existing;
    const repeated = reconcileRuns(first, [], [item("a", "SUCCEEDED")], [activityId(item("a"))], [], 30000).existing;
    expect(repeated[0].terminalObservedAt).toBe(1000);
    expect(pruneRuns(repeated, 60999, new Set())).toHaveLength(1);
    expect(pruneRuns(repeated, 61000, new Set())).toHaveLength(0);
    expect(pruneRuns(repeated, 61000, new Set([activityId(item("a"))]))).toHaveLength(1);
  });
  it("removes a revoked watched run even if an older loaded page still includes it", () => {
    expect(reconcileRuns([row("a")], [item("a")], [], [activityId(item("a"))], [], 1000).existing).toEqual([]);
    expect(reconcileRuns([], [item("a")], [], [activityId(item("a"))], [], 2000).arrivals).toEqual([]);
  });
  it("preserves unavailable source observations without inventing completion", () => {
    const result = reconcileRuns([row("a")], [], [], [activityId(item("a"))], ["EXPLORE"], 1000);
    expect(result.existing).toEqual([row("a")]);
  });
  it("keeps unrelated loaded runs when a bounded empty scan page arrives", () => {
    expect(reconcileRuns([row("a")], [], [], [], [], 1000).existing).toEqual([row("a")]);
  });
  it("updates terminal state without reordering, and inserts new arrivals after protected rows", () => {
    const result = reconcileRuns([row("a"), row("b")], [item("c"), item("b")], [item("a", "FAILED")], [activityId(item("a"))], [], 1000);
    expect(result.existing.map(({ item }) => item.id)).toEqual(["a", "b"]);
    expect(insertArrivals(result.existing, result.arrivals, new Set([activityId(item("b"))])).map(({ item }) => item.id)).toEqual(["a", "b", "c"]);
    expect(insertArrivals(result.existing, result.arrivals, new Set()).map(({ item }) => item.id)).toEqual(["c", "a", "b"]);
  });
});
