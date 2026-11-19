import { describe, expect, it } from "vitest";
import type { DashboardParameterDefinition } from "@/api/dashboard";
import {
  dashboardParameterLabel,
  formatDashboardParameterValue,
  formatUnknownDashboardParameterValue,
} from "./dashboard-parameter-presentation";

describe("dashboard parameter presentation", () => {
  it("uses Any or the declared empty label for nullable choice summaries", () => {
    for (const type of ["TEXT", "TEXT_SET", "BOOLEAN"] as const) {
      const definition: DashboardParameterDefinition = {
        type,
        nullable: true,
        control: type === "BOOLEAN" ? undefined : { options: [{ value: "US", label: "United States" }] },
      };
      expect(formatDashboardParameterValue(definition, null)).toBe("Any");
      expect(formatDashboardParameterValue({ ...definition, control: { ...definition.control, emptyLabel: "All regions" } }, null)).toBe("All regions");
    }
  });

  it("preserves nonempty values and the distinction between a null and an empty set", () => {
    const definition: DashboardParameterDefinition = {
      type: "TEXT_SET",
      nullable: true,
      control: { options: [{ value: "US", label: "United States" }], emptyLabel: "All regions" },
    };
    expect(formatDashboardParameterValue(definition, ["US"])).toBe("United States");
    expect(formatDashboardParameterValue(definition, [])).toBe("");
    expect(formatDashboardParameterValue({ type: "TEXT", nullable: true }, null)).toBe("None");
    expect(formatDashboardParameterValue({ type: "DATE_RANGE", nullable: true }, null)).toBe("None");
    expect(formatUnknownDashboardParameterValue(null)).toBe("None");
    expect(formatDashboardParameterValue({ type: "DATE_RANGE", nullable: true }, { start: "2026-01-01", end: "2026-01-31" })).toBe("Jan 1 – Jan 31, 2026");
  });

  it("uses declared labels for set values instead of serialized JSON", () => {
    const definition: DashboardParameterDefinition = {
      type: "TEXT_SET",
      nullable: false,
      label: "Order statuses",
      description: null,
      control: { options: [{ value: "SHIPPED", label: "Shipped" }, { value: "REFUNDED", label: "Refunded" }] },
    };

    expect(formatDashboardParameterValue(definition, ["SHIPPED", "REFUNDED"])).toBe("Shipped, Refunded");
  });

  it("formats immutable date ranges and fallback names for compact reading", () => {
    const definition: DashboardParameterDefinition = {
      type: "DATE_RANGE",
      nullable: false,
      label: "Report period",
      description: null,
    };

    expect(formatDashboardParameterValue(definition, { start: "2025-01-26", end: "2025-02-01" })).toBe("Jan 26 – Feb 1, 2025");
    expect(formatUnknownDashboardParameterValue({ start: "2025-01-26", end: "2025-02-01" })).toBe("Jan 26 – Feb 1, 2025");
    expect(dashboardParameterLabel("orderStatuses")).toBe("Order Statuses");
  });
  it("shows exact stored text outside options and bounds", () => {
    expect(formatDashboardParameterValue({ type: "TEXT_SET", nullable: false, control: { options: [{ value: "north", label: "North" }] } }, ["west", "north"])).toBe("west, north");
    expect(formatDashboardParameterValue({ type: "BOOLEAN", nullable: false, control: { options: [{ value: true, label: "Enabled" }] } }, false)).toBe("false");
    expect(formatDashboardParameterValue({ type: "TIMESTAMP", nullable: false, control: { max: "2026-09-01T00:00:00Z" } }, "2026-09-06T09:00:00.000000001Z")).toBe("2026-09-06T09:00:00.000000001Z");
    expect(formatDashboardParameterValue({ type: "DATE_RANGE", nullable: false, control: { max: "2026-09-01" } }, { start: "2026-09-01", end: "2026-09-06" })).toBe("2026-09-01 — 2026-09-06");
  });

});
