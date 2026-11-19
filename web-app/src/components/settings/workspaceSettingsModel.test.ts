import { describe, expect, it } from "vitest";
import {
  filterReportingTimeZoneOptions,
  REPORTING_TIME_ZONES,
  reportingTimeZoneLabel,
  reportingTimeZoneOptions,
  workspaceAccessRemovalBlock,
} from "./workspaceSettingsModel";

describe("workspaceAccessRemovalBlock", () => {
  it("allows removing a non-owner or one of several owners", () => {
    expect(workspaceAccessRemovalBlock("Devon Park", { name: "Growth", role: "EDITOR", ownerCount: 1 }, false)).toBeNull();
    expect(workspaceAccessRemovalBlock("Devon Park", { name: "Growth", role: "OWNER", ownerCount: 2 }, false)).toBeNull();
  });

  it("blocks removing the only owner with the unified last-owner message", () => {
    expect(workspaceAccessRemovalBlock("Mara Chen", { name: "Finance", role: "OWNER", ownerCount: 1 }, false)).toBe(
      "Mara is the only owner of Finance. Make someone else an owner first.",
    );
    expect(workspaceAccessRemovalBlock("Jordan Lee", { name: "Revenue Analytics", role: "OWNER", ownerCount: 1 }, true)).toBe(
      "Jordan is the only owner of Revenue Analytics. Make someone else an owner first.",
    );
  });

  it("sends Admins to workspace settings to leave a workspace themselves", () => {
    expect(workspaceAccessRemovalBlock("Jordan Lee", { name: "Finance", role: "EDITOR", ownerCount: 1 }, true)).toBe(
      "To leave Finance, use its workspace settings.",
    );
  });
});

describe("Reporting time zone options", () => {
  const at = new Date("2026-07-01T00:00:00Z");

  it("offers the contract's zones, labeled with their current offset", () => {
    expect(REPORTING_TIME_ZONES).toContain("UTC");
    expect(REPORTING_TIME_ZONES).toContain("Asia/Seoul");
    expect(REPORTING_TIME_ZONES).not.toContain("US/Eastern");
    expect(reportingTimeZoneLabel("Asia/Seoul", at)).toBe("Asia/Seoul · GMT+9");
    expect(reportingTimeZoneLabel("UTC", at)).toBe("UTC · GMT+0");
    expect(reportingTimeZoneLabel("Mars/Olympus_Mons", at)).toBe("Mars/Olympus_Mons");
  });

  it("finds zones by city words, region, or offset", () => {
    const options = reportingTimeZoneOptions(at);
    expect(options).toHaveLength(REPORTING_TIME_ZONES.length);
    expect(filterReportingTimeZoneOptions(options, "new york").map((option) => option.value)).toEqual(["America/New_York"]);
    expect(filterReportingTimeZoneOptions(options, "asia/seoul").map((option) => option.value)).toEqual(["Asia/Seoul"]);
    expect(filterReportingTimeZoneOptions(options, "GMT+9").map((option) => option.value)).toContain("Asia/Tokyo");
    expect(filterReportingTimeZoneOptions(options, "  ")).toHaveLength(options.length);
    expect(filterReportingTimeZoneOptions(options, "atlantis")).toEqual([]);
  });
});
