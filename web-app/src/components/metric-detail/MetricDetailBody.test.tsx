import type { ReactNode } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it, vi } from "vitest";
import { MetricDetailBody } from "@/components/metric-detail/MetricDetailBody";
import type { SemanticModelResponse } from "@/api/semantic-model";
import type { SemanticGroupBy } from "@/api/explore";
import type { Metric } from "@/lib/types/metric";

vi.mock("@/lib/navigation", () => ({
  Link: ({ href, children, ...props }: { href: string; children: ReactNode; className?: string }) => <a href={href} {...props}>{children}</a>,
}));

const METRIC: Metric = {
  id: "metric-1",
  name: "emea_revenue",
  type: "simple",
  typeParams: { semanticModelName: "orders", measure: "revenue" },
  filters: [
    { dimension: { semanticModelName: "orders", name: "region" }, operator: "in", values: ["EMEA", "APAC"] },
    { dimension: { semanticModelName: "orders", name: "ordered_at", granularity: "month" }, operator: "between", values: ["2026-01", "2026-06"] },
    { not: { dimension: { semanticModelName: "orders", name: "channel" }, operator: "is_null", values: [] } },
  ],
  timeGranularity: "month",
  createdAt: "2026-08-01T00:00:00.000Z",
  updatedAt: "2026-08-01T00:00:00.000Z",
};

describe("Metric detail filters", () => {
  it("reads operators and granularities as words while keeping dimension names", () => {
    const markup = renderToStaticMarkup(
      <MetricDetailBody
        workspaceId="workspace-1"
        metric={METRIC}
        relationsPending={false}
        relationsError={null}
      />,
    );

    expect(markup).toContain("orders.region");
    expect(markup).toContain(">is one of<");
    expect(markup).toContain("EMEA, APAC");
    expect(markup).toContain(">by month<");
    expect(markup).toContain(">is between<");
    expect(markup).toContain("2026-01 and 2026-06");
    expect(markup).toContain(">not<");
    expect(markup).toContain(">is empty<");
    expect(markup).not.toContain(">in<");
    expect(markup).not.toContain("is_null");
    expect(markup).toContain(">Default grain</dt>");
    expect(markup).toContain(">Month</dd>");
    expect(markup).not.toContain("Counted by");
  });
});

const orders: SemanticModelResponse = {
  id: "model-1", name: "orders", label: "Orders", sourceRelations: [{connectorId: "c1", schema: "analytics", relation: "orders"}], entities: [],
  dimensions: [{ isPartition: false,name: "region", type: "categorical", expression: "region", label: "Region"}],
  measures: [{name: "revenue", aggregation: "sum", expression: "total_amount"}],
  createdAt: "2026-08-01T00:00:00.000Z", updatedAt: "2026-08-01T00:00:00.000Z",
};

describe("metric calculation tree", () => {
  it("shows one labelled link for a time dimension and opens a valid grain that Explore can change", () => {
    const timeModel: SemanticModelResponse = {...orders, dimensions: [...orders.dimensions, { isPartition: false,name: "ordered_at", type: "time", expression: "ordered_at", label: "Order date", granularity: "day"}]};
    const timeGroupBys: SemanticGroupBy[] = ["MONTH", "DAY", "WEEK", "QUARTER", "YEAR"].map((granularity) => ({kind: "DIMENSION", semanticModelName: "orders", name: "ordered_at", granularity: granularity as "DAY" | "WEEK" | "MONTH" | "QUARTER" | "YEAR"}));
    const markup = renderToStaticMarkup(<MetricDetailBody workspaceId="w1" metric={METRIC} models={[timeModel]} relationsPending={false} relationsError={null} groupBys={[...timeGroupBys, {kind: "METRIC_TIME", granularity: "DAY"}]} />);
    expect(markup.match(/>Order date<\/span><svg/g)).toHaveLength(1);
    expect(markup).toContain('aria-label="Explore by Order date"');
    expect(markup).not.toContain("rounded-[4px] border border-border bg-background px-2.5");
    expect(markup).toContain('href="/workspaces/w1/explore?metric=emea_revenue&amp;dimensionModel=orders&amp;dimension=ordered_at&amp;granularity=DAY"');
    expect(markup).not.toContain("Order date ·");
    expect(markup).not.toContain("granularity=MONTH");
    expect(markup).not.toContain("Metric time");
  });
  it("keeps same-named dimensions from separate models and falls back to the technical name", () => {
    const withoutLabels = {...orders, label: undefined, dimensions: [{ isPartition: false,name: "ordered_at", type: "time" as const, expression: "ordered_at", granularity: "day" as const}]};
    const otherModel = {...withoutLabels, id: "returns-1", name: "returns", label: "Returns"};
    const markup = renderToStaticMarkup(<MetricDetailBody workspaceId="w1" metric={METRIC} models={[withoutLabels, otherModel]} relationsPending={false} relationsError={null} groupBys={[
      {kind: "DIMENSION", semanticModelName: "orders", name: "ordered_at", granularity: "MONTH"},
      {kind: "DIMENSION", semanticModelName: "orders", name: "ordered_at", granularity: "WEEK"},
      {kind: "DIMENSION", semanticModelName: "returns", name: "ordered_at", granularity: "DAY"},
    ]} />);
    expect(markup.match(/>ordered_at<\/span><svg/g)).toHaveLength(2);
    expect(markup).toContain("dimensionModel=orders&amp;dimension=ordered_at&amp;granularity=MONTH");
    expect(markup).toContain("dimensionModel=returns&amp;dimension=ordered_at&amp;granularity=DAY");
    expect(markup).toContain(">Returns</h3>");
  });
  it("follows qualified inputs to model columns and links only reachable Slice by options", () => {
    const ratio: Metric = { ...METRIC, id: "ratio-1", name: "relative_revenue", type: "ratio", filters: [], typeParams: {numerator: {metricName: METRIC.name}, denominator: {metricName: METRIC.name, alias: "prior", offsetWindow: "1 month"}} };
    const markup = renderToStaticMarkup(<MetricDetailBody workspaceId="w1" metric={ratio} metrics={[ratio, METRIC]} models={[orders]} relationsPending={false} relationsError={null} groupBys={[{kind: "DIMENSION", semanticModelName: "orders", name: "region"}]} />);
    expect(markup).toContain("How it&#x27;s calculated");
    expect(markup).toContain("total_amount");
    expect(markup).toContain(">Aggregation</dt>");
    expect(markup).toContain(">total_amount</pre>");
    expect(markup).toMatch(/href="\/workspaces\/w1\/semantic-models\/model-1"[^>]*>Orders<\/a>/);
    expect(markup).toContain("underline-offset-4");
    expect(markup).toContain("divide-y divide-border");
    const role = markup.indexOf(">Numerator<");
    expect(role).toBeGreaterThan(-1);
    expect(role).toBeLessThan(markup.indexOf("<h3", role));
    expect(markup).not.toContain("rounded-[6px] border border-border-light");
    expect(markup).toContain("Offset: 1 month");
    expect(markup).toContain("dimensionModel=orders&amp;dimension=region");
    expect(markup).not.toContain("Depends on");
    expect(markup).not.toContain("Built from models");
    expect(markup).toContain(">Formula</dt>");
    expect(markup).toContain(">emea_revenue / emea_revenue(alias: prior, offset: 1 month)</pre>");
    expect(markup).not.toContain("Numerator ÷ Denominator");
  });
  it("shows running total and conversion settings as labelled rows", () => {
    const cumulative: Metric = { ...METRIC, id: "running", name: "running_revenue", type: "cumulative", filters: [], timeGranularity: undefined, typeParams: { inputMetric: { metricName: METRIC.name }, grainToDate: "month", periodAggregation: "last" } };
    const running = renderToStaticMarkup(<MetricDetailBody workspaceId="w1" metric={cumulative} metrics={[cumulative, METRIC]} models={[orders]} relationsPending={false} relationsError={null} />);
    expect(running).toContain(">Window</dt><dd class=\"type-body min-w-0 text-foreground\">Month to date</dd>");
    expect(running).toContain(">Period aggregation</dt><dd class=\"type-body min-w-0 text-foreground\">Last</dd>");
    expect(running).not.toContain("Running total over");
    const conversion: Metric = { ...METRIC, id: "conversion", name: "visit_conversion", type: "conversion", filters: [], timeGranularity: undefined, typeParams: { baseMetric: { metricName: METRIC.name }, conversionMetric: { metricName: METRIC.name }, entity: "customer", calculation: "conversion_rate", window: "7 days", constantProperties: [{ baseProperty: "campaign", conversionProperty: "campaign_id" }] } };
    const converted = renderToStaticMarkup(<MetricDetailBody workspaceId="w1" metric={conversion} metrics={[conversion, METRIC]} models={[orders]} relationsPending={false} relationsError={null} />);
    expect(converted).toContain(">Conversion rate</dd>");
    expect(converted).toContain(">Key</dt>");
    expect(converted).toContain(">customer</code>");
    expect(converted).toContain(">Within</dt><dd class=\"type-body min-w-0 text-foreground\">7 days</dd>");
    expect(converted).toContain(">campaign</code>");
    expect(converted).toContain(">campaign_id</code>");
    expect(converted).not.toContain("base <code");
  });
  it("shows a stored expression as written, without a generated sentence, and bounds cycles", () => {
    const expressionModel = {...orders, measures: [{...orders.measures[0], expression: "price * quantity"}]};
    const props = {workspaceId: "w1", metric: METRIC, metrics: [METRIC], models: [expressionModel], relationsPending: false, relationsError: null};
    const markup = renderToStaticMarkup(<MetricDetailBody {...props} />);
    expect(markup).toContain(">price * quantity</pre>");
    expect(markup).not.toContain("Sum of ");
    const cycle: Metric = {...METRIC, type: "derived", typeParams: {expression: "emea_revenue", inputMetrics: [{metricName: METRIC.name}]}};
    expect(renderToStaticMarkup(<MetricDetailBody {...props} metric={cycle} metrics={[cycle]} />)).toContain("Circular reference: emea_revenue");
  });
});
