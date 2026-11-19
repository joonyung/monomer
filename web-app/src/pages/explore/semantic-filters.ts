import type { SemanticDimensionFilterType, SemanticFilter, SemanticGroupBy } from "@/api/explore";
import type { SemanticModelResponse } from "@/api/semantic-model";

export type DimensionGroupBy = Extract<SemanticGroupBy, { kind: "DIMENSION" }>;
export type FilterKind = SemanticFilter["kind"];
export type FilterOperator = SemanticFilter["operator"];

/**
 * Filter targets: discovered dimensions whose time grain matches the grain the model declares,
 * one per model/name. Dimensions without model metadata are left out rather than guessed.
 */
export function filterDimensions(groupBys: SemanticGroupBy[], models: SemanticModelResponse[]): DimensionGroupBy[] {
  const seen = new Set<string>();
  return groupBys.filter((groupBy): groupBy is DimensionGroupBy => {
    if (groupBy.kind !== "DIMENSION") return false;
    const dimension = models.find((model) => model.name === groupBy.semanticModelName)
      ?.dimensions.find((candidate) => candidate.name === groupBy.name);
    if (!dimension) return false;
    if (dimension.type === "time" && (!dimension.granularity || groupBy.granularity !== dimension.granularity.toUpperCase())) return false;
    if (dimension.type !== "time" && groupBy.granularity) return false;
    const key = JSON.stringify([groupBy.semanticModelName, groupBy.name]);
    if (seen.has(key)) return false;
    seen.add(key);
    return true;
  });
}

export function filterOperators(kind: FilterKind): FilterOperator[] {
  if (kind === "TEXT") return ["EQUALS", "NOT_EQUALS", "IN", "NOT_IN", "IS_NULL", "IS_NOT_NULL"];
  if (kind === "BOOLEAN") return ["EQUALS", "NOT_EQUALS", "IS_NULL", "IS_NOT_NULL"];
  return [
    "EQUALS",
    "NOT_EQUALS",
    "IN",
    "NOT_IN",
    "GREATER_THAN",
    "GREATER_THAN_OR_EQUAL",
    "LESS_THAN",
    "LESS_THAN_OR_EQUAL",
    "IS_NULL",
    "IS_NOT_NULL",
  ];
}

/** Operators read as words in the question sentence: "Region is one of EU, UK". */
export function filterOperatorWords(operator: FilterOperator, kind: FilterKind): string {
  const temporal = kind === "DATE" || kind === "TIMESTAMP";
  switch (operator) {
    case "EQUALS": return "is";
    case "NOT_EQUALS": return "is not";
    case "IN": return "is one of";
    case "NOT_IN": return "is not one of";
    case "GREATER_THAN": return temporal ? "is after" : "is greater than";
    case "GREATER_THAN_OR_EQUAL": return temporal ? "is on or after" : "is at least";
    case "LESS_THAN": return temporal ? "is before" : "is less than";
    case "LESS_THAN_OR_EQUAL": return temporal ? "is on or before" : "is at most";
    case "IS_NULL": return "is empty";
    case "IS_NOT_NULL": return "is not empty";
  }
}

export function filterKindLabel(kind: FilterKind): string {
  if (kind === "BOOLEAN") return "True or false";
  return kind.charAt(0) + kind.slice(1).toLowerCase();
}

/** A categorical dimension compares as one of these; a time dimension's type follows its grain. */
export const CATEGORICAL_FILTER_KINDS: FilterKind[] = ["TEXT", "NUMBER", "BOOLEAN"];

const SUB_DAY_GRAINS = new Set(["NANOSECOND", "MICROSECOND", "MILLISECOND", "SECOND", "MINUTE", "HOUR"]);

/** Day and coarser grains compare calendar dates; finer grains compare wall-clock times in the Reporting time zone. */
export function timeFilterKind(granularity: string): "DATE" | "TIMESTAMP" {
  return SUB_DAY_GRAINS.has(granularity) ? "TIMESTAMP" : "DATE";
}

export function filterAllowsMultipleValues(operator: string): boolean {
  return operator === "IN" || operator === "NOT_IN";
}

export function filterNeedsValues(operator: string): boolean {
  return operator !== "IS_NULL" && operator !== "IS_NOT_NULL";
}

export function knownDimensionFilterKind(dimension: SemanticFilter["dimension"] | undefined, dimensionFilterTypes: SemanticDimensionFilterType[]): SemanticDimensionFilterType["kind"] | null {
  if (!dimension) return null;
  return dimensionFilterTypes.find((candidate) => candidate.dimension.semanticModelName === dimension.semanticModelName
    && candidate.dimension.name === dimension.name)?.kind ?? null;
}

export function filterComparisonMismatch(filter: SemanticFilter, dimensionFilterTypes: SemanticDimensionFilterType[]): boolean {
  const knownKind = knownDimensionFilterKind(filter.dimension, dimensionFilterTypes);
  return knownKind !== null && knownKind !== filter.kind;
}

/** Time follows grain, known columns follow Catalog, and other categorical values start as text. */
export function defaultFilterKind(dimension: DimensionGroupBy | undefined, dimensionFilterTypes: SemanticDimensionFilterType[]): FilterKind {
  return dimension?.granularity ? timeFilterKind(dimension.granularity) : knownDimensionFilterKind(dimension, dimensionFilterTypes) ?? "TEXT";
}

export function initialFilterComparison(dimension: DimensionGroupBy | undefined, dimensionFilterTypes: SemanticDimensionFilterType[], initial: SemanticFilter | null) {
  const knownKind = dimension?.granularity ? null : knownDimensionFilterKind(dimension, dimensionFilterTypes);
  const repairing = initial !== null && knownKind !== null && initial.kind !== knownKind;
  const kind = knownKind ?? initial?.kind ?? defaultFilterKind(dimension, dimensionFilterTypes);
  return {
    kind,
    operator: initial && filterOperators(kind).includes(initial.operator) ? initial.operator : "EQUALS",
    values: initial && !repairing ? initial.values.map(String) : [],
    repairing,
  };
}

export function reconcileFilterComparison(
  current: { kind: FilterKind; operator: string; values: string[] },
  knownKind: SemanticDimensionFilterType["kind"] | null,
) {
  if (knownKind === null || knownKind === current.kind) return current;
  return {
    kind: knownKind,
    operator: filterOperators(knownKind).includes(current.operator as FilterOperator) ? current.operator : "EQUALS",
    values: [],
  };
}

/** The query's decimal form: digits with an optional fraction, no grouping, exponent, plus sign, or trailing zeros. */
export function canonicalDecimal(raw: string): string | null {
  const match = /^([+-]?)(\d+)(?:\.(\d+))?$/.exec(raw.trim());
  if (!match) return null;
  const integer = (match[2] as string).replace(/^0+(?=\d)/, "");
  const fraction = (match[3] ?? "").replace(/0+$/, "");
  if (integer.length > 20 || fraction.length > 18) return null;
  const body = fraction ? `${integer}.${fraction}` : integer;
  return body === "0" ? "0" : `${match[1] === "-" ? "-" : ""}${body}`;
}

/** Month, quarter, and year values compare by the period they start, so a date stands for the period it falls in. */
export function periodStart(date: string, granularity: string | null): string {
  const [year, month] = date.split("-") as [string, string, string];
  if (granularity === "MONTH") return `${year}-${month}-01`;
  if (granularity === "QUARTER") return `${year}-${String(Math.floor((Number(month) - 1) / 3) * 3 + 1).padStart(2, "0")}-01`;
  if (granularity === "YEAR") return `${year}-01-01`;
  return date;
}

function validDate(value: string): boolean {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) return false;
  const [year, month, day] = value.split("-").map(Number) as [number, number, number];
  const parsed = new Date(Date.UTC(year, month - 1, day));
  return parsed.getUTCFullYear() === year && parsed.getUTCMonth() === month - 1 && parsed.getUTCDate() === day;
}

function validTimestamp(value: string): boolean {
  return /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2}(\.\d{1,9})?)?(Z|[+-]\d{2}:\d{2})$/.test(value) && !Number.isNaN(Date.parse(value));
}

/** A complete filter, or why the entered values can't make one (null while nothing has been entered). */
export type FilterBuild = { filter: SemanticFilter; problem: null } | { filter: null; problem: string | null };

export function buildSemanticFilter(
  kind: FilterKind,
  dimension: SemanticFilter["dimension"],
  operator: string,
  enteredValues: string[],
  granularity: string | null,
): FilterBuild {
  const make = (values: unknown[]): FilterBuild => ({ filter: { kind, dimension, operator, values } as SemanticFilter, problem: null });
  const reject = (problem: string): FilterBuild => ({ filter: null, problem });
  if (!filterNeedsValues(operator)) return make([]);
  const entered = enteredValues.map((value) => value.trim()).filter(Boolean);
  if (entered.length === 0) return { filter: null, problem: null };
  const values = filterAllowsMultipleValues(operator) ? [...new Set(entered)] : entered.slice(0, 1);
  switch (kind) {
    case "TEXT":
      return values.some((value) => value.length > 256) ? reject("Each value can be at most 256 characters.") : make(values);
    case "NUMBER": {
      const numbers = values.map(canonicalDecimal);
      return numbers.some((value) => value === null) ? reject("Enter numbers like 1234.5, without commas or spaces.") : make([...new Set(numbers)]);
    }
    case "BOOLEAN": {
      const value = (values[0] as string).toLowerCase();
      return value === "true" || value === "false" ? make([value === "true"]) : reject("Choose true or false.");
    }
    case "DATE":
      return values.some((value) => !validDate(value)) ? reject("Enter a date as YYYY-MM-DD.") : make([...new Set(values.map((value) => periodStart(value, granularity)))]);
    case "TIMESTAMP":
      return values.some((value) => !validTimestamp(value)) ? reject("Enter a time with a time zone, like 2026-08-20T09:30:00Z.") : make(values);
  }
}

/**
 * One plain-words line under the value: how it is compared, and what "not" and "empty" include. Times are
 * entered and compared as wall-clock times in `timeZone`, the Workspace Reporting time zone.
 */
export function filterValueHint(kind: FilterKind, operator: string, granularity: string | null, grainWord: (grain: string) => string, timeZone: string): string | null {
  const notes: string[] = [];
  if (granularity) {
    const grain = grainWord(granularity);
    notes.push(kind === "TIMESTAMP" ? `Compares whole ${grain}s, in ${timeZone} time.` : `Compares whole ${grain}s.`);
    if (granularity === "MONTH" || granularity === "QUARTER" || granularity === "YEAR") notes.push(`A date counts as the ${grain} it falls in.`);
  } else if (kind === "TEXT" && filterNeedsValues(operator)) {
    notes.push("Matches the exact text, including upper and lower case.");
  } else if (kind === "TIMESTAMP" && filterNeedsValues(operator)) {
    notes.push(`Times are in ${timeZone}.`);
  }
  if (operator === "NOT_EQUALS" || operator === "NOT_IN") notes.push("Includes rows with no value.");
  if (kind === "TEXT" && !filterNeedsValues(operator)) notes.push("Blank text counts as empty.");
  return notes.length > 0 ? notes.join(" ") : null;
}
