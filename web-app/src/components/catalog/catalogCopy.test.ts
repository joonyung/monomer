import { describe, expect, it } from "vitest";
import { failureCause, failureExplanation, formatElapsed, formatRefreshTime, formatShortDate, refreshFailureSummary, relationKindLabel, requesterLabel, selectionCountLabel } from "@/components/catalog/catalogCopy";

describe("catalog copy", () => {
  it("names relation kinds in sentence case instead of raw enums", () => {
    expect(relationKindLabel("TABLE")).toBe("Table");
    expect(relationKindLabel("VIEW")).toBe("View");
    expect(relationKindLabel("MATERIALIZED_VIEW")).toBe("Materialized view");
  });

  it("summarizes classified refresh failures", () => {
    const denied = { failureCode: "PERMISSION_DENIED", safeMessage: "permission denied for schema finance" };
    expect(refreshFailureSummary(denied)).toBe("Last refresh failed: permission denied");
    const unclassified = { failureCode: "WAREHOUSE_QUERY_FAILED", safeMessage: "Metadata query exceeded the warehouse statement timeout." };
    expect(refreshFailureSummary(unclassified)).toBe("Last refresh failed: Metadata query exceeded the warehouse statement timeout.");
  });

  it("formats refresh elapsed time as a clock", () => {
    expect(formatElapsed(51)).toBe("0:51");
    expect(formatElapsed(125)).toBe("2:05");
    expect(formatElapsed(3_725)).toBe("1:02:05");
  });
});

describe("catalog recovery and status copy", () => {
  it("explains warehouse failures in human words with the fix they need", () => {
    expect(failureExplanation("AUTHENTICATION_FAILED", null, "Snowflake")).toEqual({ title: "Monomer couldn't sign in to Snowflake.", detail: "The warehouse rejected the username or key.", fix: "credentials" });
    expect(failureExplanation("CONNECTION_FAILED", null, "Trino").fix).toBe("settings");
    expect(failureExplanation("PERMISSION_DENIED", null, "BigQuery").fix).toBe("access");
    expect(failureExplanation("WAREHOUSE_QUERY_FAILED", "Statement timeout", "Redshift")).toEqual({ title: "Monomer couldn't read this from the warehouse.", detail: "Statement timeout", fix: null });
    expect(failureCause("AUTHENTICATION_FAILED")).toBe("Sign-in rejected");
    expect(failureCause("SOMETHING_ELSE")).toBe("Refresh failed");
  });

  it("counts whole schemas and individually chosen tables separately", () => {
    expect(selectionCountLabel({ selectedSchemas: [] })).toBe("Nothing selected");
    expect(selectionCountLabel({ selectedSchemas: [
      { relations: { kind: "ALL_RELATIONS" } },
      { relations: { kind: "SELECTED_RELATIONS", relationNames: ["invoices", "payments"] } },
    ] })).toBe("1 schema · 2 tables selected");
  });

  it("names refresh times relative to today", () => {
    const now = new Date(2026, 9, 2, 12, 0);
    expect(formatRefreshTime(new Date(2026, 9, 2, 8, 12).toISOString(), now)).toBe("Today, 8:12 AM");
    expect(formatRefreshTime(new Date(2026, 9, 1, 18, 40).toISOString(), now)).toBe("Yesterday, 6:40 PM");
    expect(formatShortDate(new Date(2026, 8, 30, 8, 0).toISOString(), now)).toBe("Sep 30");
    expect(requesterLabel({ displayName: null })).toBe("Unknown member");
  });
});
