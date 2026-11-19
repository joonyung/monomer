import type {
  SemanticModelDimension,
  SemanticModelEntity,
  SemanticModelMeasure,
  SemanticSourceRelation,
} from "@/api/semantic-model";

export const TECHNICAL_NAME_PATTERN = /^[a-z][a-z0-9]*(?:_[a-z0-9]+)*$/;

/** The message for a technical name, or null when it is valid. */
export function technicalNameProblem(name: string, example: string): string | null {
  if (!name.trim()) return "Enter a name.";
  if (!TECHNICAL_NAME_PATTERN.test(name)) {
    return `Use lowercase letters, numbers, and underscores, starting with a letter (e.g. ${example}).`;
  }
  return null;
}

export interface SemanticModelDraft {
  name: string;
  sourceRelations: SemanticSourceRelation[];
  entities: SemanticModelEntity[];
  dimensions: SemanticModelDimension[];
  measures: SemanticModelMeasure[];
  aggregationTimeDimension: string;
}

export interface ValidationProblem {
  /** Definition path of the invalid value, e.g. `measures[1].expression`; the editor shows the message beside it. */
  field: string;
  /** Names the field in the summary, e.g. "Aggregate metric 2". */
  subject: string;
  message: string;
}

function lowerFirst(message: string): string {
  return message.charAt(0).toLowerCase() + message.slice(1);
}

/** Summary line that names the field, e.g. "Aggregate metric 2: enter a column or SQL expression." */
export function problemSummary(problem: ValidationProblem): string {
  return `${problem.subject}: ${lowerFirst(problem.message)}`;
}

export function problemsByField(problems: ValidationProblem[]): Record<string, string> {
  const fields: Record<string, string> = {};
  for (const problem of problems) fields[problem.field] ??= problem.message;
  return fields;
}

export function validateSemanticModelDraft(draft: SemanticModelDraft): ValidationProblem[] {
  const problems: ValidationProblem[] = [];
  const nameProblem = technicalNameProblem(draft.name, "orders");
  if (nameProblem) problems.push({ field: "name", subject: "Name", message: nameProblem });

  if (draft.sourceRelations.length === 0) {
    problems.push({ field: "sourceRelations", subject: "Source", message: "Add a source table." });
  }
  const seenConnectors = new Set<string>();
  draft.sourceRelations.forEach((source, index) => {
    const field = `sourceRelations[${index}]`;
    const subject = `Source table ${index + 1}`;
    if (!source.connectorId || !source.schema || !source.relation) {
      problems.push({ field, subject, message: "Choose a table." });
    } else if (seenConnectors.has(source.connectorId)) {
      problems.push({ field, subject, message: "Each connector can have only one source table." });
    }
    if (source.connectorId) seenConnectors.add(source.connectorId);
  });

  if (draft.entities.filter((entity) => entity.type === "primary").length !== 1) {
    problems.push({ field: "entities", subject: "Fields", message: "Define exactly one Row key." });
  }

  const nameCounts = new Map<string, number>();
  for (const field of [...draft.entities, ...draft.dimensions, ...draft.measures]) {
    if (field.name) nameCounts.set(field.name, (nameCounts.get(field.name) ?? 0) + 1);
  }
  const fieldProblems = (group: "entities" | "dimensions" | "measures", subjectNoun: string, fields: Array<{ name: string; expression: string }>, example: string) => {
    fields.forEach((field, index) => {
      const subject = `${subjectNoun} ${index + 1}`;
      const nameMessage = technicalNameProblem(field.name, example)
        ?? ((nameCounts.get(field.name) ?? 0) > 1 ? "Use a name that no other field uses." : null);
      if (nameMessage) problems.push({ field: `${group}[${index}].name`, subject, message: nameMessage });
      if (!field.expression.trim()) {
        problems.push({ field: `${group}[${index}].expression`, subject, message: "Enter a column or SQL expression." });
      }
    });
  };
  fieldProblems("entities", "Key field", draft.entities, "customer_id");
  fieldProblems("dimensions", "Dimension", draft.dimensions, "ordered_at");
  draft.dimensions.forEach((dimension, index) => {
    if (dimension.type === "time" && !dimension.granularity) {
      problems.push({ field: `dimensions[${index}].granularity`, subject: `Dimension ${index + 1}`, message: "Select Day, Week, Month, or another time period." });
    }
  });
  fieldProblems("measures", "Aggregate metric", draft.measures, "revenue");

  const timeNames = new Set(draft.dimensions.filter((dimension) => dimension.type === "time").map((dimension) => dimension.name));
  if (draft.aggregationTimeDimension && !timeNames.has(draft.aggregationTimeDimension)) {
    problems.push({ field: "aggregationTimeDimension", subject: "Metric time", message: "Select a Time field." });
  }
  draft.measures.forEach((measure, index) => {
    const subject = `Aggregate metric ${index + 1}`;
    if (measure.aggregationTimeDimension && !timeNames.has(measure.aggregationTimeDimension)) {
      problems.push({ field: `measures[${index}].aggregationTimeDimension`, subject, message: "Select a Time field for Metric time." });
    }
    const nonAdditive = measure.nonAdditiveDimension;
    const groupings = nonAdditive?.windowGroupings ?? [];
    if (nonAdditive && !timeNames.has(nonAdditive.name)) {
      problems.push({ field: `measures[${index}].nonAdditiveDimension`, subject, message: "Select a Time field for One value per period." });
    } else if (groupings.some((name) => !draft.entities.some((entity) => entity.name === name))) {
      problems.push({ field: `measures[${index}].nonAdditiveDimension`, subject, message: "Group One value per period by key names." });
    }
  });
  return problems;
}
