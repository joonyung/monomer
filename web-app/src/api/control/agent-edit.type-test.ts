import type { components } from "./generated";

type EditRequest = components["schemas"]["AgentEditRequest"];
const draftPath = "workspace/dashboards/0198ef28-2000-7000-8000-000000000001/draft";
const sessionPath = "workspace/dashboards/0198ef28-2000-7000-8000-000000000001/sessions/0198ef28-2000-7000-8000-000000000002";
const connectorSelectionPath = "workspace/connectors/0198ef28-1000-7000-8000-000000000001/catalog/catalog-selection";
const selectionPath = "workspace/connectors/0198ef28-1000-7000-8000-000000000001/catalog/selection";

export const semanticModelEdit: EditRequest = { path: "workspace/semantic-models/orders", aggregateMetrics: [], patch: [{ op: "replace", path: "/sourceRelations/0/relation", value: "orders_v2" }] };
export const metricEdit: EditRequest = { path: "workspace/metrics/revenue", patch: [{ op: "remove", path: "/measure" }, { op: "add", path: "/measure", value: "net_revenue" }] };
export const draftEdit: EditRequest = { path: draftPath, revision: "3", patch: [{ op: "replace", path: "/definition/meta/title", value: "매출 현황" }] };
// @ts-expect-error Session has no EDIT operation.
export const sessionEdit: EditRequest = { path: sessionPath, revision: "3", patch: [{ op: "replace", path: "/variables/region", value: null }] };
export const connectorSelectionEdit: EditRequest = { path: connectorSelectionPath, patch: [{ op: "replace", path: "", value: { selectedCatalogNames: [] } }] };
export const selectionEdit: EditRequest = { path: selectionPath, patch: [{ op: "replace", path: "", value: { selectedSchemas: [] } }] };
export const nestedSelectionEdit: EditRequest = { path: "workspace/connectors/0198ef28-1000-7000-8000-000000000001/catalog/catalogs/warehouse/selection", patch: [{ op: "replace", path: "", value: { selectedSchemas: [] } }] };

// @ts-expect-error Semantic revision is forbidden even when the surrounding union contains CAS requests.
export const semanticRevision: EditRequest = { path: "workspace/metrics/revenue", revision: "3", patch: [{ op: "replace", path: "/description", value: "updated" }] };
// @ts-expect-error Explicit null is not a revisionless request.
export const semanticNullRevision: EditRequest = { path: "workspace/semantic-models/orders", aggregateMetrics: [], revision: null, patch: [{ op: "replace", path: "/description", value: "updated" }] };
// @ts-expect-error Draft edits require the current authoring revision.
export const draftWithoutRevision: EditRequest = { path: draftPath, patch: [{ op: "replace", path: "/definition/meta/title", value: "updated" }] };
// @ts-expect-error Session has no EDIT operation.
export const sessionWithoutRevision: EditRequest = { path: sessionPath, patch: [{ op: "replace", path: "/variables/region", value: "EU" }] };
// @ts-expect-error Session edits cannot add Variables.
export const sessionAdd: EditRequest = { path: sessionPath, revision: "3", patch: [{ op: "add", path: "/variables/region", value: "EU" }] };
// @ts-expect-error Session edits cannot remove Variables.
export const sessionRemove: EditRequest = { path: sessionPath, revision: "3", patch: [{ op: "remove", path: "/variables/region" }] };
// @ts-expect-error Session edits cannot replace the document root.
export const sessionRoot: EditRequest = { path: sessionPath, revision: "3", patch: [{ op: "replace", path: "", value: null }] };
// @ts-expect-error Selection is exactly one whole-document replacement.
export const selectionTwice: EditRequest = { path: selectionPath, patch: [{ op: "replace", path: "", value: { selectedSchemas: [] } }, { op: "replace", path: "", value: { selectedSchemas: [] } }] };
// @ts-expect-error Connector selection also permits exactly one replacement.
export const connectorSelectionTwice: EditRequest = { path: connectorSelectionPath, patch: [{ op: "replace", path: "", value: { selectedCatalogNames: [] } }, { op: "replace", path: "", value: { selectedCatalogNames: [] } }] };
// @ts-expect-error Selection revisions are forbidden.
export const selectionRevision: EditRequest = { path: selectionPath, revision: "3", patch: [{ op: "replace", path: "", value: { selectedSchemas: [] } }] };

// @ts-expect-error A Metric root replacement requires its complete owner definition.
export const incompleteMetricRoot: EditRequest = { path: "workspace/metrics/revenue", patch: [{ op: "replace", path: "", value: {} }] };
// @ts-expect-error A Model root replacement requires its complete owner definition.
export const incompleteModelRoot: EditRequest = { path: "workspace/semantic-models/orders", aggregateMetrics: [], patch: [{ op: "replace", path: "", value: {} }] };
// @ts-expect-error A Draft root replacement requires all three editable fields.
export const incompleteDraftRoot: EditRequest = { path: draftPath, revision: "3", patch: [{ op: "replace", path: "", value: {} }] };
// @ts-expect-error Partial operations cannot make root add valid.
export const draftRootAdd: EditRequest = { path: draftPath, revision: "3", patch: [{ op: "add", path: "", value: {} }] };
// @ts-expect-error Removing the resource root is forbidden.
export const draftRootRemove: EditRequest = { path: draftPath, revision: "3", patch: [{ op: "remove", path: "" }] };
// @ts-expect-error The read envelope is outside the Model editable document.
export const modelServerField: EditRequest = { path: "workspace/semantic-models/orders", aggregateMetrics: [], patch: [{ op: "add", path: "/requestId", value: "r1" }] };
// @ts-expect-error Server facts are outside the Draft editable document.
export const draftServerField: EditRequest = { path: draftPath, revision: "3", patch: [{ op: "remove", path: "/targetQueryRuns" }] };

export const metricRoot: EditRequest = { path: "workspace/metrics/revenue", patch: [{ op: "replace", path: "", value: { schemaVersion: 3, name: "revenue", type: "simple", semanticModelName: "orders", measure: "amount" } }] };
export const draftRoot: EditRequest = { path: draftPath, revision: "3", patch: [{ op: "replace", path: "", value: { definition: { schemaVersion: 1, meta: { title: "Orders" }, parameters: {}, variables: {}, queries: {}, widgets: {}, layout: { sections: [] } }, parameterBindings: {}, variables: {} } }] };

export const unicodeSelectionEdit: EditRequest = { path: "workspace/connectors/0198ef28-1000-7000-8000-000000000001/catalog/catalogs/매출 😀~1한 해~0%+?#|../selection", patch: [{ op: "replace", path: "", value: { selectedSchemas: [{ schemaName: "스키마/원문", relations: { kind: "SELECTED_RELATIONS", relationNames: ["테이블~원문"] } }] } }] };
// @ts-expect-error Catalog selection edits still require the selection resource suffix.
export const catalogWithoutSelectionSuffix: EditRequest = { path: "workspace/connectors/0198ef28-1000-7000-8000-000000000001/catalog/catalogs/매출", patch: [{ op: "replace", path: "", value: { selectedSchemas: [] } }] };
// @ts-expect-error Unicode Catalog names do not allow partial selection document patches.
export const unicodePartialSelection: EditRequest = { path: "workspace/connectors/0198ef28-1000-7000-8000-000000000001/catalog/catalogs/매출~1원문/selection", patch: [{ op: "replace", path: "/selectedSchemas", value: [] }] };

export const pagesTreeEdit: EditRequest = { path: "workspace/pages/tree", revision: "3", commandId: "move-page", patch: [{ op: "remove", path: "/placements/0/children/0" }] };
export const pageMetadataEdit: EditRequest = { path: "workspace/pages/0198ef28-1000-7000-8000-000000000001", revision: "3", commandId: "rename-page", patch: [{ op: "replace", path: "/title", value: "Reports" }, { op: "replace", path: "/description", value: null }] };
// @ts-expect-error Tree patches cannot edit Page metadata.
export const pagesTreeMetadata: components["schemas"]["AgentWorkspacePagesTreeEditRequest"] = { path: "workspace/pages/tree", revision: "3", commandId: "invalid", patch: [{ op: "replace", path: "/title", value: "Reports" }] };
// @ts-expect-error Directory mutations require a command identifier.
export const pagesTreeWithoutCommand: EditRequest = { path: "workspace/pages/tree", revision: "3", patch: [{ op: "remove", path: "/placements/0" }] };
// @ts-expect-error Page metadata cannot mutate Dashboard references.
export const pageDashboardEdit: EditRequest = { path: "workspace/pages/0198ef28-1000-7000-8000-000000000001", revision: "3", commandId: "invalid", patch: [{ op: "replace", path: "/dashboardId", value: "0198ef28-1000-7000-8000-000000000002" }] };

// @ts-expect-error Model writes require an explicit aggregate metric creation list.
export const modelWithoutAggregates: EditRequest = { path: "workspace/semantic-models/orders", patch: [{ op: "replace", path: "/description", value: "updated" }] };
// @ts-expect-error Metric writes cannot create model aggregates.
export const metricWithAggregates: EditRequest = { path: "workspace/metrics/revenue", aggregateMetrics: [], patch: [{ op: "replace", path: "/description", value: "updated" }] };
