import { describe, expect, it } from "vitest";
import { eventMatchesQuery, snapshotChartEventPayload, snapshotEventQuery } from "./event-boundary";

describe("ChartWidget event boundary", () => {
  it("projects an ECharts click payload and drops live event objects", () => {
    const data: Record<string, unknown> = { region: "North", value: 42 };
    data.self = data;
    data.onClick = () => { throw new Error("live function must not cross the boundary"); };
    const payload = {
      componentType: "series",
      seriesType: "bar",
      seriesId: "orders_by_region",
      seriesIndex: 0,
      name: "North",
      dataIndex: 2,
      data,
      value: ["North", 42],
      event: {
        target: { shape: { live: true } },
        stopPropagation() { throw new Error("live event must not cross the boundary"); },
      },
    };
    const safe = snapshotChartEventPayload(payload);
    expect(eventMatchesQuery(safe, { seriesId: "orders_by_region" })).toBe(true);
    expect(safe.event).toBeUndefined();
    expect(safe.seriesId).toBe("orders_by_region");
    expect(safe.data).toEqual({ region: "North", value: 42 });
  });

  it("does not invoke accessor-backed event fields", () => {
    const payload: Record<string, unknown> = { componentType: "series" };
    Object.defineProperty(payload, "seriesId", {
      enumerable: true,
      get() { throw new Error("event accessor must not run"); },
    });
    expect(snapshotChartEventPayload(payload)).toEqual({ componentType: "series" });
  });

  it("adapts a validated object selector to the plain record expected by ECharts", () => {
    const query = snapshotEventQuery({ seriesId: "orders_by_region" });

    expect(Object.getPrototypeOf(query)).toBe(Object.prototype);
    expect(query).toEqual({ seriesId: "orders_by_region" });
    expect(Object.isFrozen(query)).toBe(true);
  });
});

describe("aggregate event capacity", () => {
  it("budgets all whitelisted fields together", () => {
    const text = "x".repeat(200000);
    expect(snapshotChartEventPayload({ name: text, value: text }).name).toBe(text);
    expect(() => snapshotChartEventPayload({ name: text, value: text, data: text })).toThrow(/byte budget/);
    expect(() => snapshotChartEventPayload({ value: Array(11000).fill(0), data: Array(11000).fill(0) })).toThrow(/node budget/);
    expect(snapshotChartEventPayload({ name: "ok", ignored: text.repeat(10) })).toEqual({ name: "ok" });
  });
});
