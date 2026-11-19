import type { ReactNode } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { describe, expect, it, vi } from "vitest";
import type { SemanticModelResponse } from "@/api/semantic-model";
import { MetricEditor, metricPrefill, metricCountedByOptions, metricCountedByValue } from "@/components/metric/MetricEditor";
import { queryKeys } from "@/lib/query/keys";
import type { Metric } from "@/lib/types/metric";

vi.mock("@/hooks/use-workspace", () => ({ useWorkspaceId: () => "w1", usePrincipalId: () => null }));
vi.mock("@/lib/navigation", () => ({
  Link: ({ href, children }: { href: string; children: ReactNode }) => <a href={href}>{children}</a>,
  useRouter: () => ({ push: vi.fn() }),
}));

const billing: SemanticModelResponse = {
  id: "model-1",
  name: "billing",
  label: "Billing",
  sourceRelations: [{ connectorId: "c1", schema: "billing", relation: "invoices" }],
  entities: [{ name: "invoice", type: "primary", expression: "invoice_id" }],
  dimensions: [{ isPartition: false, name: "issued_at", type: "time", expression: "issued_at", granularity: "hour" }],
  measures: [{ name: "invoice_count", aggregation: "count", expression: "invoice_id", label: "Invoices" }],
  createdAt: "2026-09-01T00:00:00Z",
  updatedAt: "2026-09-01T00:00:00Z",
};

function render(node: ReactNode): string {
  return renderToStaticMarkup(<QueryClientProvider client={new QueryClient()}>{node}</QueryClientProvider>);
}

describe("metricPrefill", () => {
  it("opens a Simple metric over the measure, labelled and named after it", () => {
    expect(metricPrefill([billing], { model: "billing", measure: "invoice_count" })).toEqual({
      params: { semanticModelName: "billing", measure: "invoice_count" },
      label: "Invoices",
      name: "invoice_count",
    });
    expect(metricPrefill([billing], { model: "model-1" })?.params).toEqual({ semanticModelName: "billing", measure: "" });
    expect(metricPrefill([billing], { model: "missing", measure: "invoice_count" })).toBeNull();
  });
});

describe("MetricEditor", () => {
  it("asks the five calculation questions and starts Counted by at Day before a model is selected", () => {
    const markup = render(<MetricEditor />);
    expect(markup).toContain('aria-label="Calculation method"');
    expect(markup).toContain("Check");
    for (const question of ["Add up a column", "Divide one metric by another", "Write a formula", "Running total or period to date", "Conversion between two events"]) expect(markup).toContain(question);
    expect(markup).toContain("Complete the required fields to check this metric.");
    expect(markup).not.toContain("Check the saved metric after saving.");
    expect(markup).not.toContain(">Hour<");
    expect(markup).not.toContain(">Nanosecond<");
    expect(markup).toContain(">Day<");
    expect(markup).not.toMatch(/Grain to date|Default time granularity/);
  });

  it("supports same-page editing with Cancel, Save, Check and Affects", () => {
    const client = new QueryClient();
    client.setQueryData(queryKeys.metrics.detail("w1", "", "metric-1"), {
      id: "metric-1", name: "invoices", type: "simple", typeParams: { semanticModelName: "billing", measure: "invoice_count" },
      createdAt: "2026-09-01T00:00:00Z", updatedAt: "2026-09-01T00:00:00Z",
    });
    const markup = renderToStaticMarkup(<QueryClientProvider client={client}><MetricEditor metricId="metric-1" onCancel={() => undefined} /></QueryClientProvider>);
    expect(markup).toContain(">Cancel<");
    expect(markup).toContain(">Save<");
    expect(markup).toContain("Affects if saved");
    expect(markup).toContain("Check");
  });

  it("shows the name read-only when editing", () => {
    const client = new QueryClient();
    client.setQueryData(queryKeys.metrics.detail("w1", "", "metric-1"), {
      id: "metric-1", name: "invoices", type: "simple", typeParams: { semanticModelName: "billing", measure: "invoice_count" },
      createdAt: "2026-09-01T00:00:00Z", updatedAt: "2026-09-01T00:00:00Z",
    });
    const markup = renderToStaticMarkup(<QueryClientProvider client={client}><MetricEditor metricId="metric-1" /></QueryClientProvider>);
    expect(markup).toContain('<p id="metric-name"');
    expect(markup).not.toContain('<input id="metric-name"');
  });
});

describe("Counted by options", () => {
  const aggregate = (name: string, modelName: string, measure: string): Metric => ({ id: name, name, type: "simple", typeParams: { semanticModelName: modelName, measure }, createdAt: billing.createdAt, updatedAt: billing.updatedAt });

  it("offers Hour through Year for an hourly model, while a daily model starts at Day", () => {
    const params = { semanticModelName: billing.name, measure: "invoice_count" };
    expect(metricCountedByOptions("simple", params, [billing], [])).toEqual(["hour", "day", "week", "month", "quarter", "year"]);
    const daily = { ...billing, dimensions: [{ ...billing.dimensions[0], granularity: "day" as const }] };
    expect(metricCountedByOptions("simple", params, [daily], [])).toEqual(["day", "week", "month", "quarter", "year"]);
  });

  it("uses the aggregate's time override before the model default", () => {
    const model = { ...billing, aggregationTimeDimension: "issued_at", dimensions: [...billing.dimensions, { isPartition: false, name: "received_at", type: "time" as const, expression: "received_at", granularity: "minute" as const }], measures: [{ ...billing.measures[0], aggregationTimeDimension: "received_at" }] };
    expect(metricCountedByOptions("simple", { semanticModelName: billing.name, measure: "invoice_count" }, [model], [])[0]).toBe("minute");
  });

  it("follows compound metric inputs and uses a grain all their time fields support", () => {
    const daily = { ...billing, name: "daily", dimensions: [{ ...billing.dimensions[0], granularity: "day" as const }] };
    const hourlyMetric = aggregate("hourly_invoices", billing.name, "invoice_count");
    const dailyMetric = aggregate("daily_invoices", daily.name, "invoice_count");
    const running: Metric = { ...hourlyMetric, name: "running_invoices", type: "cumulative", typeParams: { inputMetric: { metricName: hourlyMetric.name } } };
    expect(metricCountedByOptions("cumulative", running.typeParams, [billing], [hourlyMetric])[0]).toBe("hour");
    expect(metricCountedByOptions("ratio", { numerator: { metricName: running.name }, denominator: { metricName: dailyMetric.name } }, [billing, daily], [hourlyMetric, dailyMetric, running])[0]).toBe("day");
  });

  it("keeps unknown or cyclic input definitions bounded at Day", () => {
    const cycle: Metric = { id: "cycle", name: "cycle", type: "derived", typeParams: { expression: "cycle", inputMetrics: [{ metricName: "cycle" }] }, createdAt: billing.createdAt, updatedAt: billing.updatedAt };
    expect(metricCountedByOptions("derived", cycle.typeParams, [billing], [cycle])[0]).toBe("day");
    expect(metricCountedByOptions("simple", { semanticModelName: "missing", measure: "invoice_count" }, [billing], [])[0]).toBe("day");
  });

  it("clears a newly selected Hour when the model changes to Day", () => {
    const params = { semanticModelName: billing.name, measure: "invoice_count" };
    const hourlyOptions = metricCountedByOptions("simple", params, [billing], []);
    expect(metricCountedByValue("hour", hourlyOptions, undefined)).toBe("hour");
    const daily = { ...billing, dimensions: [{ ...billing.dimensions[0], granularity: "day" as const }] };
    const dailyOptions = metricCountedByOptions("simple", params, [daily], []);
    expect(metricCountedByValue("hour", dailyOptions, undefined)).toBe("");
    expect(metricCountedByValue("hour", dailyOptions, "month")).toBe("");
  });

  it("clears a draft Hour after switching calculation methods while preserving Day", () => {
    const options = metricCountedByOptions("ratio", { numerator: { metricName: "" }, denominator: { metricName: "" } }, [billing], []);
    expect(metricCountedByValue("hour", options, undefined)).toBe("");
    expect(metricCountedByValue("day", options, undefined)).toBe("day");
  });

  it("preserves only the original stored finer value, not a newly selected unsupported value", () => {
    const options = ["day", "week", "month", "quarter", "year"] as const;
    expect(metricCountedByValue("nanosecond", options, "nanosecond")).toBe("nanosecond");
    expect(metricCountedByValue("hour", options, "nanosecond")).toBe("");
    expect(metricCountedByValue("nanosecond", options, undefined)).toBe("");
    expect(metricCountedByValue("", options, "nanosecond")).toBe("");
  });
});
