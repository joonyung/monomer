import type { components } from "@/api/control/generated";
import type { AgentApp, CredentialSurface, RecordedActor } from "@/lib/types/provenance";
import { apiFetch } from "@/lib/api/client";
import { semanticBffUrl } from "@/api/workspace-bff";
import { parseMetricDsl } from "@/api/metric";
import type { Metric, MetricFilter, TimeGranularity } from "@/lib/types/metric";
import type {
  MeasureAggregation,
  NonAdditiveDimension,
  TimeKind,
} from "@/lib/types/semantic-model";

const SEMANTIC_MODEL_BASE_PATH = "/semantic-models";

export interface SemanticSourceRelation {
  connectorId: string;
  catalog?: string;
  schema: string;
  relation: string;
}

export interface SemanticModelEntity {
  name: string;
  type: "primary" | "unique" | "foreign" | "natural";
  expression: string;
  label?: string;
  description?: string;
}

export interface SemanticModelDimension {
  name: string;
  type: "time" | "categorical";
  expression: string;
  isPartition: boolean;
  granularity?: components["schemas"]["SemanticModeldimension"]["granularity"];
  // Time dimensions only; unset, the kind follows the column's warehouse type.
  timeKind?: TimeKind;
  label?: string;
  description?: string;
}

export interface SemanticModelMeasure {
  name: string;
  aggregation: MeasureAggregation;
  expression: string;
  aggregationTimeDimension?: string;
  nonAdditiveDimension?: NonAdditiveDimension;
  label?: string;
  description?: string;
}

export interface CreateSemanticModelRequest {
  name: string;
  label?: string;
  description?: string;
  sourceRelations: SemanticSourceRelation[];
  entities: SemanticModelEntity[];
  dimensions: SemanticModelDimension[];
  measures: SemanticModelMeasure[];
  aggregationTimeDimension?: string;
}

export type SourcePartitionInfo = components["schemas"]["SemanticModelResponse"]["sourcePartitionInfo"][number];

export interface SemanticModelResponse extends CreateSemanticModelRequest {
  sourcePartitionInfo?: SourcePartitionInfo[];
  id: string;
  createdAt: string;
  updatedAt: string;
}

export interface AggregateMetricRequest {
  measure: string;
  name: string;
  label?: string;
  description?: string;
  filters?: MetricFilter[];
  timeGranularity?: TimeGranularity;
}

export interface WriteSemanticModelRequest {
  definition: CreateSemanticModelRequest;
  aggregateMetrics: AggregateMetricRequest[];
}

export interface SemanticModelWriteResult {
  model: SemanticModelResponse;
  aggregateMetrics: Metric[];
}

interface SemanticModelDsl {
  schemaVersion: 3;
  name: string;
  label?: string;
  description?: string;
  sourceRelations: SemanticSourceRelation[];
  aggregationTimeDimension?: string;
  entities: SemanticModelEntity[];
  dimensions: SemanticModelDimension[];
  measures: SemanticModelMeasure[];
}

interface SemanticModelResourceWireResponse {
  semanticModelId: string;
  workspaceId: string;
  definition: SemanticModelDsl;
  sourcePartitionInfo: SourcePartitionInfo[];
  createdAt: string;
  updatedAt: string;
}

interface SemanticModelDetailWireResponse extends SemanticModelResourceWireResponse {
  updatedBy: RecordedActor | null;
  credentialSurface: CredentialSurface | null;
  agentApp: AgentApp | null;
}

/** The detail read adds the last recorded save: who saved it and whether they worked through an agent. */
export interface SemanticModelDetail extends SemanticModelResponse {
  updatedBy: RecordedActor | null;
  credentialSurface: CredentialSurface | null;
  agentApp: AgentApp | null;
}

function fromWireResponse(response: SemanticModelResourceWireResponse): SemanticModelResponse {
  return {
    ...response.definition,
    id: response.semanticModelId,
    sourcePartitionInfo: response.sourcePartitionInfo,
    createdAt: response.createdAt,
    updatedAt: response.updatedAt,
  };
}

export function buildSemanticModelDsl(request: CreateSemanticModelRequest): SemanticModelDsl {
  return {
    schemaVersion: 3,
    name: request.name,
    ...(request.label ? { label: request.label } : {}),
    ...(request.description ? { description: request.description } : {}),
    sourceRelations: request.sourceRelations.map((source) => ({
      connectorId: source.connectorId,
      ...(source.catalog ? { catalog: source.catalog } : {}),
      schema: source.schema,
      relation: source.relation,
    })),
    ...(request.aggregationTimeDimension
      ? { aggregationTimeDimension: request.aggregationTimeDimension }
      : {}),
    entities: request.entities.map((entity) => ({
      name: entity.name,
      type: entity.type,
      expression: entity.expression,
      ...(entity.label ? { label: entity.label } : {}),
      ...(entity.description ? { description: entity.description } : {}),
    })),
    dimensions: request.dimensions.map((dimension) => ({
      isPartition: dimension.isPartition,
      name: dimension.name,
      type: dimension.type,
      expression: dimension.expression,
      ...(dimension.type === "time" && dimension.granularity
        ? { granularity: dimension.granularity }
        : {}),
      ...(dimension.type === "time" && dimension.timeKind
        ? { timeKind: dimension.timeKind }
        : {}),
      ...(dimension.label ? { label: dimension.label } : {}),
      ...(dimension.description ? { description: dimension.description } : {}),
    })),
    measures: request.measures.map((measure) => ({
      name: measure.name,
      aggregation: measure.aggregation,
      expression: measure.expression,
      ...(measure.aggregationTimeDimension
        ? { aggregationTimeDimension: measure.aggregationTimeDimension }
        : {}),
      ...(measure.nonAdditiveDimension
        ? {
            nonAdditiveDimension: {
              name: measure.nonAdditiveDimension.name,
              windowChoice: measure.nonAdditiveDimension.windowChoice,
              ...(measure.nonAdditiveDimension.windowGroupings
                ? { windowGroupings: [...measure.nonAdditiveDimension.windowGroupings] }
                : {}),
            },
          }
        : {}),
      ...(measure.label ? { label: measure.label } : {}),
      ...(measure.description ? { description: measure.description } : {}),
    })),
  };
}

async function writeSemanticModel(
  workspaceId: string,
  resourceId: string | null,
  request: WriteSemanticModelRequest,
  idempotencyKey: string,
): Promise<SemanticModelWriteResult> {
  const path = resourceId
    ? `${SEMANTIC_MODEL_BASE_PATH}/${encodeURIComponent(resourceId)}`
    : SEMANTIC_MODEL_BASE_PATH;
  const response = await apiFetch(semanticBffUrl(workspaceId, path), {
    method: resourceId ? "PUT" : "POST",
    headers: { "Content-Type": "application/json", "Idempotency-Key": idempotencyKey },
    body: JSON.stringify({ definition: buildSemanticModelDsl(request.definition), aggregateMetrics: request.aggregateMetrics }),
  });
  if (!response.ok) {
    const error = await response.json().catch(() => ({}));
    throw new Error(error.message || `Failed to write semantic model: ${response.statusText}`);
  }
  const result = await response.json() as { model: SemanticModelResourceWireResponse; aggregateMetrics: components["schemas"]["MetricResponse"][] };
  return {
    model: fromWireResponse(result.model),
    aggregateMetrics: result.aggregateMetrics.map((metric) => parseMetricDsl(metric.metricId, metric.definition, metric.createdAt, metric.updatedAt)),
  };
}

export const semanticModelAPI = {
  create: (workspaceId: string, request: WriteSemanticModelRequest, idempotencyKey: string) =>
    writeSemanticModel(workspaceId, null, request, idempotencyKey),

  update: (
    workspaceId: string,
    id: string,
    request: WriteSemanticModelRequest,
    idempotencyKey: string,
  ) => writeSemanticModel(workspaceId, id, request, idempotencyKey),

  list: async (workspaceId: string): Promise<SemanticModelResponse[]> => {
    const response = await apiFetch(semanticBffUrl(workspaceId, SEMANTIC_MODEL_BASE_PATH));
    if (!response.ok) throw new Error(`Failed to fetch semantic models: ${response.statusText}`);
    const resources = await response.json() as SemanticModelResourceWireResponse[];
    return resources.map(fromWireResponse);
  },

  getById: async (workspaceId: string, id: string): Promise<SemanticModelDetail> => {
    const response = await apiFetch(
      semanticBffUrl(workspaceId, `${SEMANTIC_MODEL_BASE_PATH}/${encodeURIComponent(id)}`),
    );
    if (!response.ok) throw new Error(`Failed to fetch semantic model: ${response.statusText}`);
    const detail = await response.json() as SemanticModelDetailWireResponse;
    return { ...fromWireResponse(detail), updatedBy: detail.updatedBy, credentialSurface: detail.credentialSurface, agentApp: detail.agentApp };
  },

};

export function formatSourceRelation(relation: SemanticSourceRelation): string {
  const catalog = relation.catalog ? `${relation.catalog}.` : "";
  return `${catalog}${relation.schema}.${relation.relation}`;
}
