import { describe, expect, it } from "vitest";
import type { ActivityItem, ActivityPage } from "@/api/overview";
import { activityRows } from "./activity-model";

const alex = "019e8000-0000-7000-8000-000000000b02";

function item(id: string, overrides: Partial<ActivityItem>): ActivityItem {
  return {
    id, kind: "EXPLORE", title: `Query ${id}`, description: "Explore", state: "SUCCEEDED", startedAt: "2026-10-04T00:00:00Z",
    occurredAt: "2026-10-04T00:01:00Z", actorId: alex, actorName: "Alex Morgan", actorKind: "USER", credentialSurface: "WEB",
    agentApp: null, failureReason: null, href: `/workspaces/w/explore?run=${id}`, ...overrides,
  };
}

const page = (items: ActivityItem[]): ActivityPage => ({ items, watched: [], nextCursor: null, unavailableSources: [], observedAt: "2026-10-04T00:02:00Z" });

describe("activity rows", () => {
  it("puts running work first and shows a run that just finished once, finished", () => {
    const running = [item("r1", { state: "RUNNING" }), item("r2", { state: "RUNNING" })];
    const finished = item("r2", { state: "SUCCEEDED" });
    const rows = activityRows(running, [page([finished, item("c1", { kind: "METRIC", state: "UPDATED" })]), page([item("c1", { kind: "METRIC", state: "UPDATED" })])]);
    expect(rows.map((row) => `${row.kind}:${row.id}:${row.state}`)).toEqual(["EXPLORE:r1:RUNNING", "EXPLORE:r2:SUCCEEDED", "METRIC:c1:UPDATED"]);
  });

  it("keeps rows of different kinds that share an id", () => {
    expect(activityRows([], [page([item("same", {}), item("same", { kind: "PAGE", state: "UPDATED" })])])).toHaveLength(2);
  });
});

