import { describe, expect, it } from "vitest";
import {
  filterCommands,
  isSearchableQuery,
  matchingPages,
  resourceSearchQuery,
  resourceSections,
  workspaceCommands,
} from "./commandPaletteModel";
import type { PageResponse } from "@/api/pages";

const page = (pageId: string, displayTitle: string, kind: PageResponse["kind"]): PageResponse => ({
  pageId, kind, displayTitle, title: displayTitle, documentFormat: null, dashboardId: null, description: null,
  createdAt: "2026-10-01T00:00:00Z", updatedAt: "2026-10-01T00:00:00Z",
});

describe("command palette model", () => {
  it("offers Settings only to people who can open it and filters Go to by label or hint", () => {
    expect(workspaceCommands("workspace-1", false).map((command) => command.id)).not.toContain("workspace-settings");
    expect(workspaceCommands("workspace-1", true).map((command) => command.id)).toContain("workspace-settings");
    expect(filterCommands(workspaceCommands("workspace-1", false), "conn").map((command) => command.id)).toEqual(["connectors"]);
    expect(filterCommands(workspaceCommands("workspace-1", false), "who ran").map((command) => command.href)).toEqual(["/workspaces/workspace-1/activity"]);
  });

  it("searches resources only for text with a letter or number", () => {
    expect(isSearchableQuery("rev")).toBe(true);
    expect(isSearchableQuery("수익")).toBe(true);
    expect(isSearchableQuery("!! ")).toBe(false);
  });

  it("never sends a query longer than the endpoint accepts", () => {
    expect(resourceSearchQuery("  revenue  ")).toBe("revenue");
    const pasted = `${"revenue ".repeat(40)}tail`;
    expect(resourceSearchQuery(pasted).length).toBeLessThanOrEqual(200);
    expect(resourceSearchQuery(pasted)).toBe(pasted.slice(0, 200).trim());
  });

  it("matches Pages by every word of the title", () => {
    const pages = [page("p1", "Weekly revenue", "DASHBOARD"), page("p2", "Revenue notes", "DOCUMENT"), page("p3", "Churn", "FOLDER")];
    expect(matchingPages(pages, "revenue", 5).map((item) => item.pageId)).toEqual(["p1", "p2"]);
    expect(matchingPages(pages, "weekly rev", 5).map((item) => item.pageId)).toEqual(["p1"]);
    expect(matchingPages(pages, "revenue", 1)).toHaveLength(1);
  });

  it("builds sections in a fixed order with links to each resource's page", () => {
    const sections = resourceSections("workspace-1", {
      dashboards: [{ dashboardId: "d1", title: null }],
      metrics: [{ metricId: "m1", name: "revenue", label: "Revenue", type: "SIMPLE" }],
      semanticModels: [{ semanticModelId: "s1", name: "invoices", label: null }],
      tables: [{ connectorId: "c1", connectorName: "Production Postgres", connectorType: "POSTGRESQL", catalogName: null, schemaName: "billing", tableName: "invoices", relationKind: "TABLE" }],
    }, [page("p1", "Weekly revenue", "DASHBOARD")]);

    expect(sections.map((section) => section.title)).toEqual(["Dashboards", "Pages", "Metrics", "Semantic models", "Tables"]);
    expect(sections.flatMap((section) => section.items.map((item) => [item.label, item.hint, item.href]))).toEqual([
      ["Untitled dashboard", "Dashboard", "/workspaces/workspace-1/dashboards/d1"],
      ["Weekly revenue", "Dashboard page", "/workspaces/workspace-1/pages/p1"],
      ["Revenue", "Aggregate · revenue", "/workspaces/workspace-1/metrics/m1"],
      ["invoices", "Semantic model", "/workspaces/workspace-1/semantic-models/s1"],
      ["billing.invoices", "Production Postgres", "/workspaces/workspace-1/catalog?connector=c1&schema=billing&table=invoices"],
    ]);
    expect(resourceSections("workspace-1", null, [])).toEqual([]);
  });
});
