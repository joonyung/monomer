import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";
import type { ExploreRun } from "@/api/explore";
import { ExploreRunPanel } from "./ExploreRunPanel";

const run: ExploreRun = {
  runId: "r1", workspaceId: "w1", connectorId: "c1", kind: "SQL", state: "SUCCEEDED", requestDigest: "fixture",
  createdAt: "2026-10-07T00:00:00Z", updatedAt: "2026-10-07T00:00:01Z", sql: "SELECT amount FROM orders",
  failureCode: null, failureDetail: null, resultState: "AVAILABLE", resultAvailable: true,
  resultMetadata: { byteSize: "128", mediaType: "application/vnd.apache.arrow.file", rowCount: "0", columnCount: 1, expiresAt: "2099-01-01T00:00:00Z" },
  result: { columns: [{ ordinal: 1, name: "amount", logicalType: "DECIMAL", nullable: true }], rows: [], returnedRowCount: 0, stopReason: "RESULT_COMPLETE" },
  startedBy: null, credentialSurface: "WEB", agentApp: null,
};

function renderRun(selected: ExploreRun) {
  return renderToStaticMarkup(<ExploreRunPanel workspaceId="w1" principalId="u1" run={selected} loading={false} error={null} editedSinceRun={false} connectors={[]} metrics={[]} semanticModels={[]} onCancel={() => undefined} cancelling={false} cancelError={null} onOpenInSql={() => undefined} onRunAgain={() => undefined} runAgainDisabled={false} onClose={() => undefined} />);
}

describe("Explore empty results", () => {
  it("keeps an empty result table and SQL available", () => {
    const markup = renderRun(run);
    expect(markup).toContain("<table");
    expect(markup).not.toContain("<td");
    expect(markup).toContain('aria-label="Result view"');
    expect(markup).toContain(">SQL</button>");
  });
  it("does not treat one null aggregate row as an empty result", () => {
    const markup = renderRun({ ...run, resultMetadata: { ...run.resultMetadata!, rowCount: "1" }, result: { ...run.result!, rows: [[null]], returnedRowCount: 1 } });
    expect(markup).toContain("<table");
    expect(markup).toContain("<td");
  });
});

describe("Explore result time zone", () => {
  const hourly: ExploreRun = {
    ...run,
    kind: "SEMANTIC",
    reportingTimeZone: "Asia/Seoul",
    query: { schemaVersion: 4, metrics: ["revenue"], groupBys: [{ kind: "METRIC_TIME", granularity: "HOUR" }], metricTimeRange: null, filters: [], orderBy: [], rowLimit: 1000 },
    resultMetadata: { ...run.resultMetadata!, rowCount: "1", columnCount: 2 },
    result: {
      columns: [
        { ordinal: 1, name: "metric_time.hour", logicalType: "TIMESTAMP", nullable: false },
        { ordinal: 2, name: "metric.revenue", logicalType: "DECIMAL", nullable: true },
      ],
      rows: [["2026-06-01T14:00:00", "12.50"]],
      returnedRowCount: 1,
      stopReason: "RESULT_COMPLETE",
    },
  };

  it("names the run's zone and shows times as its wall clock", () => {
    const markup = renderRun(hourly);
    expect(markup).toContain("1 row · Times in Asia/Seoul");
    expect(markup).toContain("Jun 1, 2026, 14:00</td>");
    expect(markup).not.toContain("14:00 UTC");
  });

  it("says nothing about a zone the run didn't record or a question without time", () => {
    expect(renderRun({ ...hourly, reportingTimeZone: null })).not.toContain("Times in");
    const regional: ExploreRun = { ...hourly, query: { ...hourly.query!, groupBys: [{ kind: "DIMENSION", semanticModelName: "orders", name: "region", granularity: null }] } };
    expect(renderRun(regional)).not.toContain("Times in");
  });
});
