import type { ExploreErrorPresentation, ExploreViolation } from "@/api/explore-errors";
import type { SemanticExcludedModel } from "@/api/explore";
import type { SemanticModelResponse } from "@/api/semantic-model";
import { catalogHref } from "@/lib/catalog/catalog-links";
import type { Metric } from "@/lib/types/metric";
import { webPaths } from "@/lib/web-paths";

export type FailureAction = { label: string } & ({ href: string; onClick?: never } | { href?: never; onClick: () => void });

export interface FailureGuidance {
  title: string;
  explanation: string | null;
  actions: FailureAction[];
  reference: string | null;
}

export interface SemanticProblemContext {
  workspaceId: string | null;
  connectorId: string | null;
  connectorName: string;
  metrics: Metric[];
  models: SemanticModelResponse[];
}

type DefinitionKind = "METRIC" | "SEMANTIC_MODEL";

interface DefinitionReference {
  kind: DefinitionKind;
  name: string;
}

/** A reason the server gave, with the saved definition it names when it can name one. */
interface DefinitionFinding {
  definition: DefinitionReference | null;
  code: string;
  message: string;
}

// The server names a definition by its Agent resource path; a request-root field means it couldn't name one.
const DEFINITION_FIELD = /^workspace\/(semantic-models|metrics)\/([a-z][a-z0-9]*(?:_[a-z0-9]+)*)$/;
const REQUEST_ROOT_FIELDS = new Set(["query", "metrics"]);

const DEFINITION_CODES = new Set([
  "QUERY_METRIC_NOT_FOUND",
  "METRIC_MODEL_NOT_FOUND",
  "METRIC_DEPENDENCY_NOT_FOUND",
  "METRICFLOW_MANIFEST_INVALID",
]);
const SELECTION_CODES = new Set([
  "QUERY_GROUP_BY_NOT_FOUND",
  "GROUP_BY_KIND_MISMATCH",
  "UNKNOWN_FILTER_TARGET",
  "QUERY_FILTER_UNRENDERABLE",
]);
const SOURCE_CODES = new Set(["CATALOG_RELATION_NOT_FOUND", "SOURCE_RELATION_NOT_FOUND"]);

export function problemReference(code: string | null, traceId: string | null): string | null {
  return [code, traceId ? `Reference ${traceId}` : null].filter(Boolean).join(" · ") || null;
}

/** Semantic reasons travel in the problem detail as stable codes joined by commas. */
export function semanticReasonCodes(error: ExploreErrorPresentation): Set<string> {
  return new Set(error.detail.split(",").map((part) => part.trim()));
}

function definitionFindings(violations: ExploreViolation[]): DefinitionFinding[] {
  return violations.flatMap((violation): DefinitionFinding[] => {
    const named = DEFINITION_FIELD.exec(violation.field);
    if (named) {
      const kind: DefinitionKind = named[1] === "metrics" ? "METRIC" : "SEMANTIC_MODEL";
      return [{ definition: { kind, name: named[2] as string }, code: violation.code, message: violation.message }];
    }
    return REQUEST_ROOT_FIELDS.has(violation.field) ? [{ definition: null, code: violation.code, message: violation.message }] : [];
  });
}

function uniqueDefinitions(findings: DefinitionFinding[]): DefinitionReference[] {
  const seen = new Map<string, DefinitionReference>();
  for (const finding of findings) {
    if (finding.definition) seen.set(`${finding.definition.kind}:${finding.definition.name}`, finding.definition);
  }
  return [...seen.values()];
}

function kindWord(kind: DefinitionKind): string {
  return kind === "METRIC" ? "metric" : "semantic model";
}

function modelByName(name: string, models: SemanticModelResponse[]): SemanticModelResponse | undefined {
  return models.find((model) => model.name === name);
}

function definitionLabel(definition: DefinitionReference, context: SemanticProblemContext): string {
  if (definition.kind === "SEMANTIC_MODEL") return modelByName(definition.name, context.models)?.label || definition.name;
  const metric = context.metrics.find((candidate) => candidate.name === definition.name);
  return metric?.label || definition.name;
}

function definitionHref(workspaceId: string, definition: DefinitionReference, context: SemanticProblemContext): string {
  if (definition.kind === "SEMANTIC_MODEL") {
    const model = modelByName(definition.name, context.models);
    return model ? webPaths.semanticModel(workspaceId, model.id) : webPaths.semanticModelCollection(workspaceId);
  }
  const metric = context.metrics.find((candidate) => candidate.name === definition.name);
  return metric ? webPaths.metric(workspaceId, metric.id) : webPaths.metricCollection(workspaceId);
}

/** "Open semantic model" when the title already names the one definition, "Open Orders" when there are several. */
function openDefinitionActions(definitions: DefinitionReference[], context: SemanticProblemContext): FailureAction[] {
  const workspaceId = context.workspaceId;
  if (!workspaceId) return [];
  const shown = definitions.slice(0, 3);
  return shown.map((definition) => ({
    label: shown.length === 1 ? `Open ${kindWord(definition.kind)}` : `Open ${definitionLabel(definition, context)}`,
    href: definitionHref(workspaceId, definition, context),
  }));
}

function quoted(label: string): string {
  return `“${label}”`;
}

function messages(findings: DefinitionFinding[]): string | null {
  const distinct = [...new Set(findings.map((finding) => finding.message.trim()).filter(Boolean))];
  return distinct.slice(0, 2).join(" ") || null;
}

function sourceGuidance(reasons: Set<string>, findings: DefinitionFinding[], context: SemanticProblemContext, reference: string | null): FailureGuidance {
  const sourceFindings = findings.filter((finding) => SOURCE_CODES.has(finding.code));
  const missingSource = sourceFindings.filter((finding) => finding.code === "SOURCE_RELATION_NOT_FOUND" && finding.definition);
  if (missingSource.length > 0) {
    const definitions = uniqueDefinitions(missingSource);
    const first = definitions[0] as DefinitionReference;
    return {
      title: definitions.length === 1
        ? `${quoted(definitionLabel(first, context))} has no source table on ${context.connectorName}.`
        : `${definitions.length} semantic models have no source table on ${context.connectorName}.`,
      explanation: null,
      actions: openDefinitionActions(definitions, context),
      reference,
    };
  }
  const chooseTables = context.workspaceId && context.connectorId && reasons.has("CATALOG_RELATION_NOT_FOUND")
    ? [{ label: "Choose tables", href: catalogHref(context.workspaceId, { connector: context.connectorId, choose: true }) }]
    : [];
  const namedModels = uniqueDefinitions(sourceFindings);
  if (namedModels.length > 0) {
    // The server's message says whether the table is missing or matches more than one table, so the
    // title stays true for both and the model comes first: an ambiguous source is fixed in the model.
    return {
      title: `A table this query reads can’t be found in Catalog for ${context.connectorName}.`,
      explanation: messages(sourceFindings.filter((finding) => finding.definition)),
      actions: [...openDefinitionActions(namedModels, context).slice(0, 2), ...chooseTables],
      reference,
    };
  }
  if (sourceFindings.length > 0) {
    return {
      title: `No tables from ${context.connectorName} are loaded in Catalog yet.`,
      explanation: "Choose the tables these metrics read, then run it again.",
      actions: chooseTables,
      reference,
    };
  }
  return {
    title: `A table this query reads isn’t loaded in Catalog for ${context.connectorName}.`,
    explanation: "Load it in Catalog, or point the model at a table that exists.",
    actions: chooseTables,
    reference,
  };
}

function definitionGuidance(findings: DefinitionFinding[], context: SemanticProblemContext, reference: string | null): FailureGuidance {
  const definitionFindingsOnly = findings.filter((finding) => DEFINITION_CODES.has(finding.code));
  const definitions = uniqueDefinitions(definitionFindingsOnly);
  const semantics = context.workspaceId ? [{ label: "Open Semantics", href: webPaths.semantics(context.workspaceId) }] : [];
  if (definitions.length === 0) {
    return {
      title: "A definition this query uses has a problem.",
      explanation: messages(definitionFindingsOnly) ?? "Check the models and metrics it uses in Semantics.",
      actions: semantics,
      reference,
    };
  }
  const first = definitions[0] as DefinitionReference;
  return {
    title: definitions.length === 1
      ? `The ${kindWord(first.kind)} ${quoted(definitionLabel(first, context))} has a problem.`
      : `${definitions.length} definitions this query uses have problems.`,
    explanation: messages(definitionFindingsOnly.filter((finding) => finding.definition)),
    actions: openDefinitionActions(definitions, context),
    reference,
  };
}

/**
 * What a rejected Semantic request means and where to fix it. Null when the reasons aren't about
 * definitions, source tables, or a selection that a broken model hid; callers keep their own wording then.
 */
export function semanticProblemGuidance(error: ExploreErrorPresentation, context: SemanticProblemContext): FailureGuidance | null {
  const reasons = semanticReasonCodes(error);
  const findings = definitionFindings(error.violations);
  // The reason codes say more to support than the generic INVALID_REQUEST.
  const reference = problemReference([...reasons].join(", "), error.traceId);
  if ([...reasons].some((reason) => SOURCE_CODES.has(reason))) return sourceGuidance(reasons, findings, context, reference);
  if ([...reasons].some((reason) => DEFINITION_CODES.has(reason))) return definitionGuidance(findings, context, reference);
  if ([...reasons].some((reason) => SELECTION_CODES.has(reason))) {
    const brokenModels = uniqueDefinitions(findings.filter((finding) => finding.definition?.kind === "SEMANTIC_MODEL"));
    if (brokenModels.length === 0) return null;
    return {
      title: brokenModels.length === 1
        ? `A group by or filter uses ${quoted(definitionLabel(brokenModels[0] as DefinitionReference, context))}, which has a problem.`
        : `Group bys or filters use ${brokenModels.length} semantic models that have problems.`,
      explanation: messages(findings.filter((finding) => finding.definition)),
      actions: openDefinitionActions(brokenModels, context),
      reference,
    };
  }
  return null;
}

/** "Options from “Orders” are hidden because the model has a problem." */
/** Models the engine left out of this question's options, with what they hide and where to fix them. */
export interface ExcludedModelsSummary {
  summary: string;
  models: { label: string; reason: string; href: string | null }[];
}

export function excludedModelsSummary(excluded: SemanticExcludedModel[], context: SemanticProblemContext, subject: "group bys" | "dimensions"): ExcludedModelsSummary | null {
  if (excluded.length === 0) return null;
  const models = excluded.map((model) => {
    const definition: DefinitionReference = { kind: "SEMANTIC_MODEL", name: model.semanticModelName };
    return {
      label: definitionLabel(definition, context),
      reason: model.message,
      href: context.workspaceId ? definitionHref(context.workspaceId, definition, context) : null,
    };
  });
  const effect = subject === "group bys" ? "group bys aren’t listed" : "dimensions can’t be filtered";
  const summary = models.length === 1
    ? `${quoted((models[0] as { label: string }).label)} has an error, so its ${effect}.`
    : `${models.length} models have errors, so their ${effect}.`;
  return { summary, models };
}

/** Labels of the excluded models that these selections read from, for the "Remove to run" hint. */
export function excludedModelLabels(semanticModelNames: string[], excluded: SemanticExcludedModel[], models: SemanticModelResponse[]): string[] {
  const excludedNames = new Set(excluded.map((model) => model.semanticModelName));
  return [...new Set(semanticModelNames.filter((name) => excludedNames.has(name)))]
    .map((name) => modelByName(name, models)?.label || name);
}
