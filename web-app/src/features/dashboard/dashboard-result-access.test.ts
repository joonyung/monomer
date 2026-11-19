import { QueryClient, QueryObserver } from "@tanstack/react-query";
import { expect, it } from "vitest";
import { DashboardApiError, type DashboardResult } from "@/api/dashboard";
import { readDashboardResultWithAccess } from "./dashboard-result-access";

it("replaces cached result bytes when current access is revoked and can recover after a later grant", async () => {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const result: DashboardResult = { queryType: "SEMANTIC" as const, reportingTimeZone: null, queryRunId: "first-run", columns: [{ name: "metric.private", logicalType: "TEXT", nullable: false, display: null }], rows: [["private-result-bytes"]] };
  let denied = false;
  const key = ["dashboard-result", "first-run"];
  const observer = new QueryObserver(client, { queryKey: key, queryFn: () => readDashboardResultWithAccess(async () => {
    if (denied) throw new DashboardApiError("Access restricted", 403);
    return result;
  }) });
  const unsubscribe = observer.subscribe(() => {});
  try {
    expect((await observer.refetch()).data).toEqual(result);
    denied = true;
    expect((await observer.refetch()).data).toBeNull();
    expect(client.getQueryData(key)).toBeNull();
    expect(JSON.stringify(client.getQueryCache().getAll().map(query => query.state))).not.toContain("private-result-bytes");
    denied = false;
    expect((await observer.refetch()).data).toEqual(result);
  } finally {
    unsubscribe();
    observer.destroy();
    client.clear();
  }
});
