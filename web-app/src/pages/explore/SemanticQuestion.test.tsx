import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it, vi } from "vitest";
import type { SemanticDimensionFilterType, SemanticFilter } from "@/api/explore";
import { filterFixtureConnector, filterFixtureGroupBys, filterFixtureMetrics, filterFixtureModels, filterFixtureTypes } from "@/pages/dev/explore-semantic-filter-fixture/SemanticFilterFixture";
import { emptyExploreDraft, NO_UNAVAILABLE_SELECTIONS } from "./explore-draft";
import { SemanticQuestion, type SemanticQuestionProps } from "./SemanticQuestion";

vi.mock("@/lib/navigation", () => ({ Link: () => null }));
vi.mock("@/components/explore/TokenPopover", () => ({
  TokenPopover: ({ panelLabel, children }: { panelLabel: string; children: (close: () => void) => React.ReactNode }) => panelLabel === "Filter" ? children(() => undefined) : null,
}));

function renderFilter(name: string, initial: SemanticFilter | null, dimensionFilterTypes: SemanticDimensionFilterType[] = filterFixtureTypes, reportingTimeZone = "UTC", semanticModelName = "orders"): string {
  const props: SemanticQuestionProps = {
    workspaceId: "workspace-1",
    reportingTimeZone,
    draft: { ...emptyExploreDraft().semantic, metrics: ["revenue"], filters: initial ? [initial] : [] },
    onDraftChange: vi.fn(),
    metrics: filterFixtureMetrics,
    metricsLoading: false,
    metricsError: null,
    modelFilter: null,
    onClearModelFilter: vi.fn(),
    visibleMetricNames: ["revenue"],
    semanticModels: filterFixtureModels,
    semanticModelsLoading: false,
    semanticModelsError: null,
    onRetrySemanticModels: vi.fn(),
    groupBys: filterFixtureGroupBys.filter((dimension) => dimension.kind === "DIMENSION" && dimension.semanticModelName === semanticModelName && dimension.name === name),
    dimensionFilterTypes,
    excludedSemanticModels: [],
    groupBysLoading: false,
    groupBysError: null,
    unavailable: NO_UNAVAILABLE_SELECTIONS,
    connector: { all: [filterFixtureConnector], switchCount: 0, compatible: [filterFixtureConnector], selectedId: filterFixtureConnector.id, onSelect: vi.fn(), resolving: false, problem: null },
    onChooseMetric: vi.fn(),
    canEditDefinitions: true,
    metricSwap: null,
  };
  return renderToStaticMarkup(<SemanticQuestion {...props} listsOpen={false} onRunRequest={vi.fn()} />);
}

describe("Semantic filter comparison controls", () => {
  it.each(["region", "amount", "active"])("hides Compare as for known %s columns", (name) => {
    const markup = renderFilter(name, null);
    expect(markup).not.toContain('aria-label="Compare as"');
    if (name === "amount") expect(markup).toContain('inputMode="decimal"');
    if (name === "active") expect(markup).toContain('aria-label="Filter value" aria-haspopup="listbox"');
  });

  it.each(["region_upper", "profile"])("keeps Compare as for unresolved %s comparisons", (name) => {
    const markup = renderFilter(name, null);
    expect(markup).toContain('aria-label="Compare as"');
    expect(markup).toContain("as Text");
  });

  it("clears restored wrong-type values and shows how to repair them", () => {
    const markup = renderFilter("amount", { kind: "TEXT", dimension: { semanticModelName: "orders", name: "amount" }, operator: "IN", values: ["123"] });
    expect(markup).not.toContain('aria-label="Compare as"');
    expect(markup).not.toContain('aria-label="Remove 123"');
    expect(markup).toContain("This column uses Number comparisons. Enter a new value to update the filter.");
    const updateButton = markup.match(/<button[^>]*>Update filter<\/button>/)?.[0];
    expect(updateButton).toContain('type="submit"');
    expect(updateButton).toContain('disabled=""');
  });

  it("preserves values on matching filters and keeps time controls grain-based", () => {
    const matching = renderFilter("amount", { kind: "NUMBER", dimension: { semanticModelName: "orders", name: "amount" }, operator: "IN", values: ["123"] });
    expect(matching).toContain('aria-label="Remove 123"');
    expect(matching).not.toContain("Enter a new value to update the filter.");
    const time = renderFilter("created_at", null);
    expect(time).not.toContain('aria-label="Compare as"');
    expect(time).toContain('type="date"');
    expect(time).toContain("Compares whole days.");
  });

  it("enters and reads hour filters as wall-clock times in the Reporting time zone", () => {
    const saved: SemanticFilter = { kind: "TIMESTAMP", dimension: { semanticModelName: "events", name: "created_at" }, operator: "EQUALS", values: ["2026-08-20T00:30:00Z"] };
    const seoul = renderFilter("created_at", saved, filterFixtureTypes, "Asia/Seoul", "events");
    expect(seoul).toContain('type="datetime-local"');
    expect(seoul).toContain('value="2026-08-20T09:30:00"');
    expect(seoul).toContain("Compares whole hours, in Asia/Seoul time.");
    const utc = renderFilter("created_at", saved, filterFixtureTypes, "UTC", "events");
    expect(utc).toContain('value="2026-08-20T00:30:00"');
    expect(utc).toContain("Compares whole hours, in UTC time.");
  });
});
