import { describe, expect, it } from "vitest";
import { captureSourceLine, dashboardInputs, displayedResultsTimeZoneLabel, latestDisplayedResultAt, resultsFromLabel } from "./provenance";

describe("Dashboard capture provenance", () => {
  it("names the zones the displayed Semantic results were rendered in", () => {
    expect(displayedResultsTimeZoneLabel({
      revenue: { revenue: { reportingTimeZone: "Asia/Seoul" }, orders: { reportingTimeZone: null } },
      churn: { churn: { reportingTimeZone: "Asia/Seoul" } },
    })).toBe("Times in Asia/Seoul");
    expect(displayedResultsTimeZoneLabel({ a: { a: { reportingTimeZone: "UTC" } }, b: { b: { reportingTimeZone: "Asia/Seoul" } } })).toBe("Times in Asia/Seoul, UTC");
    expect(displayedResultsTimeZoneLabel({ sql: { sql: { reportingTimeZone: null } } })).toBeNull();
    expect(displayedResultsTimeZoneLabel({})).toBeNull();
  });

  it("dates results by the latest successful run that is actually displayed", () => {
    const results = { revenue: { revenue: { queryRunId: "run-a" } }, churn: { churn: { queryRunId: "run-b" } } };
    expect(latestDisplayedResultAt(results, [
      { queryRunId: "run-a", state: "SUCCEEDED", updatedAt: "2026-09-12T09:00:00Z" },
      { queryRunId: "run-b", state: "SUCCEEDED", updatedAt: "2026-09-12T10:00:00Z" },
      { queryRunId: "run-c", state: "SUCCEEDED", updatedAt: "2026-09-13T10:00:00Z" },
      { queryRunId: "run-d", state: "RUNNING", updatedAt: "2026-09-14T10:00:00Z" },
    ])).toBe("2026-09-12T10:00:00Z");
    expect(latestDisplayedResultAt({}, [{ queryRunId: "run-a", state: "SUCCEEDED", updatedAt: "2026-09-12T09:00:00Z" }])).toBeNull();
  });

  it("labels recent results relative to the reader's day", () => {
    const now = new Date(2026, 9, 1, 15, 0).getTime();
    expect(resultsFromLabel(new Date(2026, 9, 1, 9, 2).toISOString(), now)).toMatch(/^Results from today, /);
    expect(resultsFromLabel(new Date(2026, 8, 30, 9, 2).toISOString(), now)).toMatch(/^Results from yesterday, /);
    expect(resultsFromLabel(new Date(2026, 8, 20, 9, 2).toISOString(), now)).toMatch(/^Results from Sep 20/);
  });

  it("names the displayed inputs with their labels", () => {
    expect(dashboardInputs({ region: "APAC", other_value: 3 }, {
      region: { type: "TEXT", nullable: false, label: "Region" },
    } as never)).toEqual([{ label: "Region", value: "APAC" }, { label: expect.any(String), value: "3" }]);
  });

  it("keeps empty choice labels in the inputs used by Pages and PNG provenance", () => {
    const bindings = { region: null, statuses: null, active: null, selected: "US" };
    expect(dashboardInputs(bindings, {
      region: { type: "TEXT", nullable: true, label: "Region", control: { options: [{ value: "US" }], emptyLabel: "All regions" } },
      statuses: { type: "TEXT_SET", nullable: true, label: "Statuses", control: { options: [{ value: "PAID" }] } },
      active: { type: "BOOLEAN", nullable: true, label: "Active" },
      selected: { type: "TEXT", nullable: true, label: "Selected", control: { options: [{ value: "US", label: "United States" }], emptyLabel: "All regions" } },
    })).toEqual([
      { label: "Region", value: "All regions" },
      { label: "Statuses", value: "Any" },
      { label: "Active", value: "Any" },
      { label: "Selected", value: "United States" },
    ]);
    expect(bindings).toEqual({ region: null, statuses: null, active: null, selected: "US" });
  });

  it("attributes the image to its source and Monomer", () => {
    expect(captureSourceLine("Northstar Labs · Revenue Analytics")).toBe("Northstar Labs · Revenue Analytics · Built with Monomer");
    expect(captureSourceLine(null)).toBe("Built with Monomer");
  });
});
