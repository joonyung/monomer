import type {
  SemanticModelDimension,
  SemanticModelEntity,
  SemanticModelMeasure,
} from "@/api/semantic-model";
import type {
  EntityType,
  MeasureAggregation,
  NonAdditiveDimension,
  TimeGranularity,
  TimeKind,
} from "@/lib/types/semantic-model";
import { suggestTechnicalName } from "@/lib/utils/semantic-model-utils";
import type { SemanticModelResponse } from "@/api/semantic-model";
import type { RelationDetail } from "@/lib/types/catalog";

/** One row of the Fields table: an entity key type, a Time or Category dimension, or a measure. */
export type FieldKind = EntityType | "time" | "categorical" | "measure" | "unused";

export const FIELD_KIND_LABELS: Record<FieldKind, string> = {
  primary: "Row key",
  unique: "Unique key",
  foreign: "Links to Model",
  natural: "Natural key",
  time: "Time",
  categorical: "Category",
  measure: "Aggregate metric",
  unused: "Not used",
};

export const AGGREGATION_LABELS: Record<MeasureAggregation, string> = {
  sum: "Sum",
  count: "Count",
  count_distinct: "Count distinct",
  average: "Average",
  min: "Min",
  max: "Max",
  median: "Median",
  sum_boolean: "Sum of true values",
};

export interface FieldDraft {
  id: string;
  kind: FieldKind;
  name: string;
  label?: string;
  description?: string;
  expression: string;
  // Kind-specific values survive a kind change so switching back restores them; only the current kind's values are saved.
  granularity?: TimeGranularity;
  timeKind?: TimeKind;
  isPartition?: boolean;
  aggregation?: MeasureAggregation;
  aggregationTimeDimension?: string;
  nonAdditiveDimension?: NonAdditiveDimension;
  // A field added empty takes its name from its label until the name is edited.
  nameFollowsLabel: boolean;
  nativeType?: string;
  partitionColumn?: boolean;
  requiresPartitionFilter?: boolean | null;
  recommendation?: { kind: Exclude<FieldKind, "unused">; aggregation?: MeasureAggregation; keyName?: string; reason: string };
}

/** The definition array a field is saved in. */
export type FieldGroup = "entities" | "dimensions" | "measures";

export function fieldGroup(kind: FieldKind): FieldGroup | null {
  if (kind === "unused") return null;
  if (kind === "time" || kind === "categorical") return "dimensions";
  if (kind === "measure") return "measures";
  return "entities";
}

export function isEntityKind(kind: FieldKind): kind is EntityType {
  return fieldGroup(kind) === "entities";
}

function newFieldId(): string {
  return `field-${crypto.randomUUID()}`;
}

function optionalText(label: string | undefined, description: string | undefined): { label?: string; description?: string } {
  return { ...(label ? { label } : {}), ...(description ? { description } : {}) };
}

export function fieldsFromDefinition(
  entities: readonly SemanticModelEntity[],
  dimensions: readonly SemanticModelDimension[],
  measures: readonly SemanticModelMeasure[],
): FieldDraft[] {
  return [
    ...entities.map((entity): FieldDraft => ({
      id: newFieldId(),
      kind: entity.type,
      name: entity.name,
      expression: entity.expression,
      ...optionalText(entity.label, entity.description),
      nameFollowsLabel: false,
    })),
    ...dimensions.map((dimension): FieldDraft => ({
      id: newFieldId(),
      kind: dimension.type,
      isPartition: dimension.isPartition,
      name: dimension.name,
      expression: dimension.expression,
      ...(dimension.type === "time" && dimension.granularity ? { granularity: dimension.granularity } : {}),
      ...(dimension.type === "time" && dimension.timeKind ? { timeKind: dimension.timeKind } : {}),
      ...optionalText(dimension.label, dimension.description),
      nameFollowsLabel: false,
    })),
    ...measures.map((measure): FieldDraft => ({
      id: newFieldId(),
      kind: "measure",
      name: measure.name,
      expression: measure.expression,
      aggregation: measure.aggregation,
      ...(measure.aggregationTimeDimension ? { aggregationTimeDimension: measure.aggregationTimeDimension } : {}),
      ...(measure.nonAdditiveDimension ? { nonAdditiveDimension: measure.nonAdditiveDimension } : {}),
      ...optionalText(measure.label, measure.description),
      nameFollowsLabel: false,
    })),
  ];
}

export interface FieldDefinition {
  entities: SemanticModelEntity[];
  dimensions: SemanticModelDimension[];
  measures: SemanticModelMeasure[];
}

/** The saved arrays, in table order within each array; only the current kind's values are kept. */
export function definitionFromFields(fields: readonly FieldDraft[]): FieldDefinition {
  const definition: FieldDefinition = { entities: [], dimensions: [], measures: [] };
  for (const field of fields) {
    if (field.kind === "unused") continue;
    const text = optionalText(field.label, field.description);
    if (isEntityKind(field.kind)) {
      definition.entities.push({ name: field.name, type: field.kind, expression: field.expression, ...text });
    } else if (field.kind === "time") {
      definition.dimensions.push({ name: field.name, type: "time", expression: field.expression, isPartition: field.isPartition ?? false, ...(field.granularity ? { granularity: field.granularity } : {}), ...(field.timeKind ? { timeKind: field.timeKind } : {}), ...text });
    } else if (field.kind === "categorical") {
      definition.dimensions.push({ name: field.name, type: "categorical", expression: field.expression, isPartition: field.isPartition ?? false, ...text });
    } else {
      definition.measures.push({
        name: field.name,
        aggregation: field.aggregation ?? "sum",
        expression: field.expression,
        ...(field.aggregationTimeDimension ? { aggregationTimeDimension: field.aggregationTimeDimension } : {}),
        ...(field.nonAdditiveDimension ? { nonAdditiveDimension: field.nonAdditiveDimension } : {}),
        ...text,
      });
    }
  }
  return definition;
}

/** Each field's position in its definition array, e.g. `measures[2]`; validation messages are keyed by these paths. */
export function fieldPaths(fields: readonly FieldDraft[]): Map<string, string> {
  const counts: Record<FieldGroup, number> = { entities: 0, dimensions: 0, measures: 0 };
  const paths = new Map<string, string>();
  for (const field of fields) {
    const group = fieldGroup(field.kind);
    if (group === null) continue;
    paths.set(field.id, `${group}[${counts[group]}]`);
    counts[group] += 1;
  }
  return paths;
}

/**
 * Re-keys path-addressed server errors (`measures[1].expression`) from the draft they were reported against
 * to the current draft, so each error stays on its row after rows are added, removed, or change kind.
 */
export function followDraftPaths(
  errors: Readonly<Record<string, string>>,
  pathsAtFailure: ReadonlyMap<string, string>,
  currentPaths: ReadonlyMap<string, string>,
): Record<string, string> {
  const idsAtFailure = new Map([...pathsAtFailure].map(([id, path]) => [path, id]));
  const followed: Record<string, string> = {};
  for (const [key, message] of Object.entries(errors)) {
    const match = /^(\w+\[\d+\])(.*)$/.exec(key);
    const id = match ? idsAtFailure.get(match[1]) : undefined;
    if (!match || id === undefined) {
      followed[key] = message;
      continue;
    }
    const currentPath = currentPaths.get(id);
    if (currentPath !== undefined) followed[`${currentPath}${match[2]}`] = message;
  }
  return followed;
}

/** Moves a field to another kind, keeping its name, label, description, and expression. */
export function changeFieldKind(field: FieldDraft, kind: FieldKind): FieldDraft {
  return { ...field, kind, recommendation: undefined, ...(kind === "measure" && !field.aggregation ? { aggregation: "sum" as const } : {}), ...(kind === "time" && !field.granularity ? { granularity: "day" as const } : {}) };
}

export function applyRecommendation(field: FieldDraft): FieldDraft {
  const recommendation = field.recommendation;
  if (!recommendation) return field;
  return {
    ...changeFieldKind(field, recommendation.kind),
    ...(recommendation.aggregation ? { aggregation: recommendation.aggregation } : {}),
    ...(recommendation.keyName ? { name: recommendation.keyName } : {}),
  };
}

export function keyedModels(models: readonly SemanticModelResponse[], currentModelId?: string) {
  return models.filter((model) => model.id !== currentModelId).flatMap((model) =>
    model.entities.filter((key) => key.type === "primary" || key.type === "unique").map((key) => ({ model, key })));
}

/**
 * Catalog metadata suggests roles for a draft with nothing in use yet; a model that already uses columns keeps its
 * other columns as deliberately unused. Accepting a suggestion is always an explicit draft edit.
 */
export function fieldsWithSourceColumns(
  fields: readonly FieldDraft[],
  relation: RelationDetail,
  models: readonly SemanticModelResponse[],
  currentModelId?: string,
): FieldDraft[] {
  const suggest = fields.every((field) => field.kind === "unused");
  const used = new Set<string>();
  const targets = keyedModels(models, currentModelId);
  const partitionColumns = new Set(relation.partitionKeys.map((key) => key.toLowerCase()));
  const metadata = (column: string) => ({ partitionColumn: partitionColumns.has(column.toLowerCase()), requiresPartitionFilter: relation.requiresPartitionFilter });
  const hasPrimary = fields.some((field) => field.kind === "primary");
  const primaryColumn = relation.primaryKey?.columns.length === 1
    ? relation.primaryKey.columns[0]
    : !relation.primaryKey ? relation.columns.find((column) => /^(id|.*_id)$/.test(column.name))?.name : undefined;
  const rows = relation.columns.flatMap((column): FieldDraft[] => {
    const existing = fields.filter((field) => !used.has(field.id) && field.expression === column.name);
    if (existing.length > 0) {
      existing.forEach((field) => used.add(field.id));
      return existing.map((field) => ({ ...field, nativeType: column.nativeType, ...metadata(column.name) }));
    }
    const foreignKey = relation.foreignKeys.find((key) => key.columns.length === 1 && key.columns[0] === column.name);
    const target = foreignKey
      ? targets.find(({ model, key }) => model.sourceRelations.some((source) =>
          source.connectorId === foreignKey.referencedConnectorId
          && (source.catalog ?? null) === (foreignKey.referencedCatalogName ?? null)
          && source.schema === foreignKey.referencedSchemaName
          && source.relation === foreignKey.referencedRelationName
          && key.expression === foreignKey.referencedColumns[0]))
      : targets.find(({ key }) => key.name === column.name);
    const recommendation: FieldDraft["recommendation"] = !hasPrimary && primaryColumn === column.name
      ? { kind: "primary", reason: relation.primaryKey ? "Catalog primary key" : "Key name" }
      : target ? { kind: "foreign", keyName: target.key.name, reason: foreignKey ? "Catalog foreign key" : "Matching model key" }
      : /date|time/i.test(column.nativeType) || /(^|_)(date|at|timestamp)$/.test(column.name)
        ? { kind: "time", reason: "Date or time column" }
        : /int|numeric|decimal|float|double|real|number/i.test(column.nativeType) && !/(^|_)id$/.test(column.name)
          ? { kind: "measure", aggregation: "sum", reason: "Numeric column" }
          : { kind: "categorical", reason: "Group and filter" };
    return [{ id: newFieldId(), kind: "unused", name: suggestTechnicalName(column.name), expression: column.name, nativeType: column.nativeType, ...metadata(column.name), nameFollowsLabel: false, ...(suggest ? { recommendation } : {}) }];
  });
  return [...rows, ...fields.filter((field) => !used.has(field.id))];
}

export function fieldMatchesText(field: FieldDraft, text: string): boolean {
  const needle = text.trim().toLowerCase();
  return !needle || [field.name, field.label, field.expression, field.description].some((value) => value?.toLowerCase().includes(needle));
}

/**
 * Rows the editor's views hide. The filter hides rows that did not match when it was typed, so a row edited
 * while filtered stays put; In use hides the rows that were unused when it was chosen, until they get a use.
 */
export function hiddenFieldIds(fields: readonly FieldDraft[], filterHiddenIds: ReadonlySet<string> | null, inUseHiddenIds: ReadonlySet<string> | null): Set<string> {
  return new Set(fields.filter((field) => filterHiddenIds?.has(field.id) || (field.kind === "unused" && inUseHiddenIds?.has(field.id))).map((field) => field.id));
}

/** The model's Metric time after a field edit: renaming the Metric time field keeps it the Metric time. */
export function metricTimeAfterFieldChange(metricTime: string, previous: FieldDraft, next: FieldDraft): string {
  const renamed = metricTime !== "" && previous.kind === "time" && next.kind === "time" && previous.name === metricTime && next.name !== previous.name;
  return renamed ? next.name : metricTime;
}

export function changeFieldLabel(field: FieldDraft, label: string): FieldDraft {
  const next: FieldDraft = { ...field, label: label || undefined };
  return field.nameFollowsLabel ? { ...next, name: suggestTechnicalName(label) } : next;
}

export function emptyField(): FieldDraft {
  return { id: newFieldId(), kind: "categorical", name: "", expression: "", nameFollowsLabel: true };
}
