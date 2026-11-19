import { useState } from "react";
import type { SemanticDimensionFilterType, SemanticGroupBy } from "@/api/explore";
import type { SemanticModelResponse } from "@/api/semantic-model";
import type { Connector } from "@/lib/types/connector";
import type { Metric } from "@/lib/types/metric";
import { emptyExploreDraft, NO_UNAVAILABLE_SELECTIONS, type SemanticDraft } from "@/pages/explore/explore-draft";
import { SemanticQuestion } from "@/pages/explore/SemanticQuestion";

const noop = () => undefined;
export const filterFixtureModels: SemanticModelResponse[] = ["orders", "events"].map((name) => ({
  id: name, name, label: name === "orders" ? "Orders" : "Events",
  sourceRelations: [{ connectorId: "connector-1", schema: "public", relation: name }], entities: [], measures: [], createdAt: "", updatedAt: "",
  dimensions: [
    { isPartition: false, name: "created_at", label: "Created at", type: "time", expression: "created_at", granularity: name === "orders" ? "day" : "hour" },
    { isPartition: false, name: "region", type: "categorical", expression: "region" },
    { isPartition: false, name: "amount", label: "Amount", type: "categorical", expression: "amount" },
    { isPartition: false, name: "active", label: "Active", type: "categorical", expression: "active" },
    { isPartition: false, name: "region_upper", label: "Uppercase region", type: "categorical", expression: "upper(region)" },
    { isPartition: false, name: "profile", label: "Profile", type: "categorical", expression: "profile" },
    { isPartition: false, name: "undiscovered", type: "categorical", expression: "private" },
  ],
}));
export const filterFixtureGroupBys: SemanticGroupBy[] = ["orders", "events"].flatMap((semanticModelName): SemanticGroupBy[] => [
  ...(["HOUR", "DAY", "WEEK", "MONTH"] as const).map((granularity) => ({ kind: "DIMENSION" as const, semanticModelName, name: "created_at", granularity })),
  { kind: "DIMENSION", semanticModelName, name: "region" },
  ...["amount", "active", "region_upper", "profile"].map((name) => ({ kind: "DIMENSION" as const, semanticModelName, name })),
]);
export const filterFixtureTypes: SemanticDimensionFilterType[] = ["orders", "events"].flatMap((semanticModelName) => [
  { dimension: { semanticModelName, name: "region" }, kind: "TEXT" as const },
  { dimension: { semanticModelName, name: "amount" }, kind: "NUMBER" as const },
  { dimension: { semanticModelName, name: "active" }, kind: "BOOLEAN" as const },
]);
export const filterFixtureMetrics: Metric[] = [
  { id: "revenue", name: "revenue", label: "Revenue", type: "simple", typeParams: { semanticModelName: "orders", measure: "revenue" }, createdAt: "", updatedAt: "" },
];
export const filterFixtureConnector: Connector = {
  id: "connector-1", workspaceId: "workspace-1", name: "Fixture warehouse", type: "POSTGRESQL",
  properties: { host: "fixture.invalid", port: 5432, database: "warehouse" }, createdAtEpochMs: 0, updatedAtEpochMs: 0,
};

/** Mounts the production Metrics question with fixed discovery to exercise filter grains and metadata states. */
export function SemanticFilterFixture() {
  const [metadataState, setMetadataState] = useState("ready");
  const [reportingTimeZone, setReportingTimeZone] = useState("UTC");
  const [draft, setDraft] = useState<SemanticDraft>({ ...emptyExploreDraft().semantic, metrics: ["revenue"] });
  return <div className="flex min-h-screen flex-col gap-4 bg-background px-5 py-4">
    <label className="text-sm">Fixture metadata state <select aria-label="Fixture metadata state" value={metadataState} onChange={(event) => setMetadataState(event.target.value)}>
      {["ready", "loading", "error", "missing"].map((state) => <option key={state}>{state}</option>)}
    </select></label>
    <label className="text-sm">Fixture Reporting time zone <select aria-label="Fixture Reporting time zone" value={reportingTimeZone} onChange={(event) => setReportingTimeZone(event.target.value)}>
      {["UTC", "Asia/Seoul", "America/New_York"].map((zone) => <option key={zone}>{zone}</option>)}
    </select></label>
    <SemanticQuestion
      listsOpen={false}
      onRunRequest={() => undefined}
      unavailable={NO_UNAVAILABLE_SELECTIONS}
      workspaceId="workspace-1"
      reportingTimeZone={reportingTimeZone}
      draft={draft}
      onDraftChange={setDraft}
      metrics={filterFixtureMetrics}
      metricsLoading={false}
      metricsError={null}
      modelFilter={null}
      onClearModelFilter={noop}
      visibleMetricNames={filterFixtureMetrics.map((metric) => metric.name)}
      semanticModels={metadataState === "missing" ? [] : filterFixtureModels}
      semanticModelsLoading={metadataState === "loading"}
      semanticModelsError={metadataState === "error" ? { code: "SERVICE_UNAVAILABLE", detail: "Model metadata is unavailable.", retryable: true, traceId: null, violations: [] } : null}
      onRetrySemanticModels={() => setMetadataState("ready")}
      groupBys={filterFixtureGroupBys}
      dimensionFilterTypes={filterFixtureTypes}
      excludedSemanticModels={[]}
      groupBysLoading={false}
      groupBysError={null}
      onChooseMetric={(metricName) => setDraft((current) => ({ ...current, metrics: current.metrics.includes(metricName) ? current.metrics.filter((name) => name !== metricName) : [...current.metrics, metricName] }))}
      metricSwap={null}
      canEditDefinitions
      connector={{ all: [filterFixtureConnector], switchCount: 0, compatible: [filterFixtureConnector], selectedId: filterFixtureConnector.id, onSelect: noop, resolving: false, problem: null }}
    />
    <output aria-label="Filter request" className="break-all text-xs">{JSON.stringify(draft.filters)}</output>
    <output aria-label="Group by request" className="break-all text-xs">{JSON.stringify(draft.groupBys)}</output>
  </div>;
}
