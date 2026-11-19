import { QueryClient, QueryObserver } from "@tanstack/react-query";
import { expect, it } from "vitest";
import type { DashboardResult } from "@/api/dashboard";
import { validateDashboardResult } from "./dashboard-result";

it("preserves validated result references on identical refetches and rejects malformed results", async () => {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  let response: DashboardResult = { queryType: "SEMANTIC" as const, reportingTimeZone: null,
    queryRunId: "run-1",
    columns: [{ name: "metric.revenue", logicalType: "DECIMAL", nullable: false, display: null }],
    rows: [["130226.60"]],
  };
  const observer = new QueryObserver(client, {
    queryKey: ["dashboard-result", "run-1"],
    queryFn: async () => structuredClone(response),
    select: validateDashboardResult,
  });
  try {
    const first = (await observer.refetch()).data!;
    const repeated = (await observer.refetch()).data!;
    expect(repeated).toBe(first);
    expect(repeated.columns).toBe(first.columns);
    expect(repeated.rows).toBe(first.rows);

    response = { ...response, rows: [["250.00"]] };
    const changed = (await observer.refetch()).data!;
    expect(changed.rows).not.toBe(first.rows);
    expect(changed.rows[0]["metric.revenue"]).toBe("250.00");

    response = { ...response, rows: [[]] };
    const malformed = await observer.refetch();
    expect(malformed.isError).toBe(true);
    expect(malformed.error?.message).toContain("row width");
  } finally {
    observer.destroy();
    client.clear();
  }
});
