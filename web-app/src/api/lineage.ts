import { graphqlRequest } from "@/lib/api/graphql";
import { CONTROL_API_URL } from "@/lib/auth/config";
export type LineageResourceKind = "RELATION" | "SEMANTIC_MODEL" | "METRIC";
import type { ConnectorType } from "@/lib/types/connector";

export interface LineageResourceRef { kind: LineageResourceKind; id: string }
export interface LineageConnector { id: string; displayName: string; type: ConnectorType }
export interface LineageResource { resource: LineageResourceRef; label: string; address: string; description: string | null; connector: LineageConnector | null; missing: boolean }
export interface LineageEdge { dependent: LineageResourceRef; parent: LineageResourceRef }
export interface LineageEntityPeer { resource: LineageResource; sharedEntities: string[]; alreadyInCone: boolean }
export interface LineageRelationColumn {
  name: string;
  nativeType: string;
  nullable: boolean;
  hasDefault: boolean;
  primaryKey: boolean;
  foreignKey: boolean;
}
export interface LineageRelationDetail {
  columns: LineageRelationColumn[];
}
export type LineageSemanticEntityKind = "PRIMARY" | "UNIQUE" | "FOREIGN" | "NATURAL";
export type LineageSemanticDimensionKind = "CATEGORICAL" | "TIME";
export type LineageMeasureAggregation = "SUM" | "COUNT" | "COUNT_DISTINCT" | "AVERAGE" | "MIN" | "MAX" | "MEDIAN" | "SUM_BOOLEAN";
export type LineageMetricType = "SIMPLE" | "RATIO" | "DERIVED" | "CUMULATIVE" | "CONVERSION";
export type LineageTimeGranularity = "NANOSECOND" | "MICROSECOND" | "MILLISECOND" | "SECOND" | "MINUTE" | "HOUR" | "DAY" | "WEEK" | "MONTH" | "QUARTER" | "YEAR";
export interface LineageSemanticEntity { name: string; type: LineageSemanticEntityKind; label: string | null; description: string | null }
export interface LineageSemanticDimension { name: string; type: LineageSemanticDimensionKind; granularity: LineageTimeGranularity | null; label: string | null; description: string | null }
export interface LineageSemanticMeasure { name: string; aggregation: LineageMeasureAggregation; label: string | null; description: string | null }
export interface LineageSemanticModelDetail { entities: LineageSemanticEntity[]; dimensions: LineageSemanticDimension[]; measures: LineageSemanticMeasure[] }
export interface LineageMetricInput {
  kind: "MEASURE" | "METRIC";
  name: string;
  semanticModelName: string | null;
}
export interface LineageMetricDetail {
  type: LineageMetricType;
  inputs: LineageMetricInput[];
  timeGranularity: LineageTimeGranularity | null;
}
export interface LineageResourceDetail {
  relation: LineageRelationDetail | null;
  semanticModel: LineageSemanticModelDetail | null;
  metric: LineageMetricDetail | null;
}
export interface LineageDashboardUsage { dashboardId: string; title: string; queryNames: string[]; metricIds: string[] }
export interface LineageFocusCone {
  dashboardUsages?: LineageDashboardUsage[];
  workspaceId: string;
  focus: LineageResource;
  ancestors: LineageResource[];
  descendants: LineageResource[];
  siblings: LineageResource[];
  missingDefinitions: LineageResource[];
  edges: LineageEdge[];
  siblingEdges: LineageEdge[];
  entityPeers: LineageEntityPeer[];
  detail: LineageResourceDetail;
}
export interface LineageResourcePage { items: LineageResource[]; nextCursor: string | null }

const LINEAGE_GRAPHQL_ENDPOINT = `${CONTROL_API_URL}/v2/graphql`;
export const LINEAGE_DEFAULT_FOCUS_QUERY = `
  query LineageDefaultFocus($workspaceId: ID!) {
    lineageDefaultFocus(workspaceId: $workspaceId) {
      resource { kind id }
      label
      address
      description
      missing
      connector { id displayName type }
    }
  }
`;
export const LINEAGE_FOCUS_CONE_QUERY = `
  query LineageFocusCone($workspaceId: ID!, $focus: LineageResourceRefInput!) {
    lineageFocusCone(workspaceId: $workspaceId, focus: $focus) {
      workspaceId
      dashboardUsages { dashboardId title queryNames metricIds }
      focus { resource { kind id } label address description missing connector { id displayName type } }
      ancestors { resource { kind id } label address description missing connector { id displayName type } }
      descendants { resource { kind id } label address description missing connector { id displayName type } }
      siblings { resource { kind id } label address description missing connector { id displayName type } }
      missingDefinitions { resource { kind id } label address description missing connector { id displayName type } }
      edges { dependent { kind id } parent { kind id } }
      siblingEdges { dependent { kind id } parent { kind id } }
      entityPeers { resource { resource { kind id } label address description missing connector { id displayName type } } sharedEntities alreadyInCone }
      detail {
        relation { columns { name nativeType nullable hasDefault primaryKey foreignKey } }
        semanticModel {
          entities { name type label description }
          dimensions { name type granularity label description }
          measures { name aggregation label description }
        }
        metric {
          type
          inputs { kind name semanticModelName }
          timeGranularity
        }
      }
    }
  }
`;
export const LINEAGE_RESOURCES_QUERY = `
  query LineageResources($workspaceId: ID!, $kind: ResourceKind!, $query: String, $cursor: String, $pageSize: Int!) {
    lineageResources(workspaceId: $workspaceId, kind: $kind, query: $query, cursor: $cursor, pageSize: $pageSize) {
      items { resource { kind id } label address description missing connector { id displayName type } }
      nextCursor
    }
  }
`;

interface ConnectorResponse { id: string; displayName: string; type: string }
interface ResourceResponse { resource: { kind: string; id: string }; label: string; address: string; description: string | null; connector: ConnectorResponse | null; missing: boolean }
interface EdgeResponse { dependent: { kind: string; id: string }; parent: { kind: string; id: string } }
interface FocusConeResponse {
  dashboardUsages: LineageDashboardUsage[];
  workspaceId: string;
  focus: ResourceResponse;
  ancestors: ResourceResponse[];
  descendants: ResourceResponse[];
  siblings: ResourceResponse[];
  missingDefinitions: ResourceResponse[];
  edges: EdgeResponse[];
  siblingEdges: EdgeResponse[];
  entityPeers: Array<{ resource: ResourceResponse; sharedEntities: string[]; alreadyInCone: boolean }>;
  detail: {
    relation: { columns: LineageRelationColumn[] } | null;
    semanticModel: { entities: Array<{ name: string; type: string; label: string | null; description: string | null }>; dimensions: Array<{ name: string; type: string; granularity: string | null; label: string | null; description: string | null }>; measures: Array<{ name: string; aggregation: string; label: string | null; description: string | null }> } | null;
    metric: { type: string; inputs: Array<{ kind: string; name: string; semanticModelName: string | null }>; timeGranularity: string | null } | null;
  };
}
interface ResourcePageResponse { items: ResourceResponse[]; nextCursor: string | null }

function parseEnum<const T extends readonly string[]>(value: string, values: T, label: string): T[number] {
  if ((values as readonly string[]).includes(value)) return value as T[number];
  throw new Error(`Lineage response contained an unknown ${label}.`);
}
const RESOURCE_KINDS = ["RELATION", "SEMANTIC_MODEL", "METRIC"] as const;
const ENTITY_KINDS = ["PRIMARY", "UNIQUE", "FOREIGN", "NATURAL"] as const;
const DIMENSION_KINDS = ["CATEGORICAL", "TIME"] as const;
const AGGREGATIONS = ["SUM", "COUNT", "COUNT_DISTINCT", "AVERAGE", "MIN", "MAX", "MEDIAN", "SUM_BOOLEAN"] as const;
const METRIC_TYPES = ["SIMPLE", "RATIO", "DERIVED", "CUMULATIVE", "CONVERSION"] as const;
const GRANULARITIES = ["NANOSECOND", "MICROSECOND", "MILLISECOND", "SECOND", "MINUTE", "HOUR", "DAY", "WEEK", "MONTH", "QUARTER", "YEAR"] as const;
const CONNECTOR_TYPES = ["POSTGRESQL", "BIGQUERY", "SNOWFLAKE", "DATABRICKS", "REDSHIFT", "TRINO", "DUCKDB"] as const;

function mapRef(value: { kind: string; id: string }): LineageResourceRef {
  if (!value.id) throw new Error("Lineage response contained an empty resource id.");
  return { kind: parseEnum(value.kind, RESOURCE_KINDS, "resource kind"), id: value.id };
}
function mapConnector(value: ConnectorResponse): LineageConnector {
  if (!value.id || !value.displayName) throw new Error("Lineage response contained an invalid connector.");
  return { id: value.id, displayName: value.displayName, type: parseEnum(value.type, CONNECTOR_TYPES, "connector type") };
}
function mapResource(value: ResourceResponse): LineageResource {
  if (typeof value.missing !== "boolean") throw new Error("Lineage response contained an invalid missing-definition state.");
  const resource = mapRef(value.resource);
  const connector = value.connector === null ? null : mapConnector(value.connector);
  if (resource.kind === "RELATION" ? connector === null : connector !== null) throw new Error("Lineage response contained an invalid connector for the resource kind.");
  return { resource, label: value.label, address: value.address, description: value.description, connector, missing: value.missing };
}
function mapEdge(value: EdgeResponse): LineageEdge { return { dependent: mapRef(value.dependent), parent: mapRef(value.parent) } }
function parseGranularity(value: string | null): LineageTimeGranularity | null { return value === null ? null : parseEnum(value, GRANULARITIES, "time granularity") }

export function mapLineageFocusCone(value: FocusConeResponse): LineageFocusCone {
  const focusKind = parseEnum(value.focus.resource.kind, RESOURCE_KINDS, "resource kind");
  const detail: LineageResourceDetail = {
    relation: value.detail.relation && { columns: value.detail.relation.columns.map((column) => ({ ...column })) },
    semanticModel: value.detail.semanticModel && {
      entities: value.detail.semanticModel.entities.map((entity) => ({ ...entity, type: parseEnum(entity.type, ENTITY_KINDS, "entity kind") })),
      dimensions: value.detail.semanticModel.dimensions.map((dimension) => ({ ...dimension, type: parseEnum(dimension.type, DIMENSION_KINDS, "dimension kind"), granularity: parseGranularity(dimension.granularity) })),
      measures: value.detail.semanticModel.measures.map((measure) => ({ ...measure, aggregation: parseEnum(measure.aggregation, AGGREGATIONS, "measure aggregation") })),
    },
    metric: value.detail.metric && {
      type: parseEnum(value.detail.metric.type, METRIC_TYPES, "metric type"),
      timeGranularity: parseGranularity(value.detail.metric.timeGranularity),
      inputs: value.detail.metric.inputs.map((input) => {
        const kind = parseEnum(input.kind, ["MEASURE", "METRIC"] as const, "metric input kind");
        if ((kind === "MEASURE") !== (input.semanticModelName !== null)) throw new Error("Lineage response contained an inconsistent metric input.");
        return { kind, name: input.name, semanticModelName: input.semanticModelName };
      }),
    },
  };
  if ((focusKind === "RELATION") !== (detail.relation !== null) || (focusKind === "SEMANTIC_MODEL") !== (detail.semanticModel !== null) || (focusKind === "METRIC") !== (detail.metric !== null)) throw new Error("Lineage response contained an invalid resource detail.");
  return {
    workspaceId: value.workspaceId,
    dashboardUsages: value.dashboardUsages.map((usage) => ({ ...usage, queryNames: [...usage.queryNames], metricIds: [...usage.metricIds] })),
    focus: mapResource(value.focus),
    ancestors: value.ancestors.map(mapResource),
    descendants: value.descendants.map(mapResource),
    siblings: value.siblings.map(mapResource),
    missingDefinitions: value.missingDefinitions.map(mapResource),
    edges: value.edges.map(mapEdge),
    siblingEdges: value.siblingEdges.map(mapEdge),
    entityPeers: value.entityPeers.map((peer) => ({ resource: mapResource(peer.resource), sharedEntities: [...peer.sharedEntities], alreadyInCone: peer.alreadyInCone })),
    detail,
  };
}

export function mapLineageResourcePage(value: ResourcePageResponse): LineageResourcePage {
  if (value.nextCursor !== null && typeof value.nextCursor !== "string") throw new Error("Lineage resource page contained an invalid next cursor.");
  return { nextCursor: value.nextCursor, items: value.items.map(mapResource) };
}

export const lineageAPI = {
  async getDefaultFocus(workspaceId: string, signal?: AbortSignal): Promise<LineageResource | null> {
    const data = await graphqlRequest<{ lineageDefaultFocus: ResourceResponse | null }>({ endpoint: LINEAGE_GRAPHQL_ENDPOINT, query: LINEAGE_DEFAULT_FOCUS_QUERY, variables: { workspaceId }, signal });
    return data.lineageDefaultFocus === null ? null : mapResource(data.lineageDefaultFocus);
  },
  async listResources(workspaceId: string, options: { kind: LineageResourceKind; query: string; cursor: string | null; pageSize: number; signal?: AbortSignal }): Promise<LineageResourcePage> {
    const data = await graphqlRequest<{ lineageResources: ResourcePageResponse }>({ endpoint: LINEAGE_GRAPHQL_ENDPOINT, query: LINEAGE_RESOURCES_QUERY, variables: { workspaceId, kind: options.kind, query: options.query.trim() || null, cursor: options.cursor, pageSize: options.pageSize }, signal: options.signal });
    return mapLineageResourcePage(data.lineageResources);
  },
  async getFocusCone(workspaceId: string, focus: LineageResourceRef, signal?: AbortSignal): Promise<LineageFocusCone> {
    const data = await graphqlRequest<{ lineageFocusCone: FocusConeResponse }>({ endpoint: LINEAGE_GRAPHQL_ENDPOINT, query: LINEAGE_FOCUS_CONE_QUERY, variables: { workspaceId, focus }, signal });
    return mapLineageFocusCone(data.lineageFocusCone);
  },
};
