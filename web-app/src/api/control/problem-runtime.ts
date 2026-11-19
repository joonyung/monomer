import { z } from "zod";

type JsonPrimitive = boolean | null | number | string;
type JsonValue = JsonObject | JsonPrimitive | JsonValue[];
type JsonObject = { [key: string]: JsonValue };

export class ControlProblemSchemaConfigurationError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "ControlProblemSchemaConfigurationError";
  }
}

function isJsonObject(value: unknown): value is JsonObject {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}

function hasOwn(object: JsonObject, key: string): boolean {
  return Object.prototype.hasOwnProperty.call(object, key);
}

function asJsonObject(value: unknown, context: string): JsonObject {
  if (!isJsonObject(value)) {
    throw new ControlProblemSchemaConfigurationError(`Control Problem schema must contain an object at ${context}.`);
  }
  return value;
}

function closedObjectIntersectionForZod(schema: JsonObject): JsonObject {
  const unsupported = () => { throw new ControlProblemSchemaConfigurationError("Control Problem schema has an unsupported closed object intersection."); };
  const hasOnly = (value: JsonObject, keys: string[]) => Object.keys(value).every((key) => keys.includes(key));
  if (schema.type !== "object" || schema.unevaluatedProperties !== false || !hasOnly(schema, ["type", "allOf", "unevaluatedProperties"])) return unsupported();
  if (!Array.isArray(schema.allOf) || schema.allOf.length !== 2) return unsupported();
  const common = schema.allOf.find((branch) => isJsonObject(branch) && hasOwn(branch, "properties"));
  const variants = schema.allOf.find((branch) => isJsonObject(branch) && hasOwn(branch, "oneOf"));
  if (!isJsonObject(common) || !isJsonObject(variants) || !hasOnly(variants, ["oneOf"]) || !Array.isArray(variants.oneOf) || variants.oneOf.length < 2) return unsupported();
  const objectParts = (value: JsonValue): { properties: JsonObject; required: string[] } => {
    if (!isJsonObject(value) || value.type !== "object" || !hasOnly(value, ["type", "properties", "required"]) || !isJsonObject(value.properties) || !Array.isArray(value.required)) return unsupported();
    const properties = value.properties;
    if (!value.required.every((name) => typeof name === "string" && hasOwn(properties, name))) return unsupported();
    return { properties, required: value.required as string[] };
  };
  const commonParts = objectParts(common);
  const branchParts = variants.oneOf.map(objectParts);
  // Distinct required constants make the branches disjoint, preserving oneOf's exclusive meaning.
  const discriminator = commonParts.required.find((name) => {
    const constants = branchParts.map((branch) => isJsonObject(branch.properties[name]) ? branch.properties[name].const : undefined);
    return branchParts.every((branch) => branch.required.includes(name)) && constants.every((value) => typeof value === "string") && new Set(constants).size === branchParts.length;
  });
  if (!discriminator) return unsupported();
  return { oneOf: branchParts.map((branch) => {
    const properties = { ...commonParts.properties };
    for (const [name, constraint] of Object.entries(branch.properties)) {
      properties[name] = hasOwn(properties, name) ? { allOf: [properties[name], constraint] } : constraint;
    }
    return { type: "object", properties, required: [...new Set([...commonParts.required, ...branch.required])], additionalProperties: false };
  }) };
}

function normalizeForZod(value: JsonValue): JsonValue {
  if (Array.isArray(value)) return value.map(normalizeForZod);
  if (!isJsonObject(value)) return value;

  const normalized = Object.fromEntries(
    Object.entries(value).map(([key, nestedValue]) => [key, normalizeForZod(nestedValue)]),
  ) as JsonObject;
  if (hasOwn(normalized, "unevaluatedProperties")) return closedObjectIntersectionForZod(normalized);
  const hasObjectKeyword = [
    "additionalProperties",
    "maxProperties",
    "minProperties",
    "patternProperties",
    "properties",
    "propertyNames",
    "required",
  ].some((key) => hasOwn(normalized, key));
  const hasArrayKeyword = [
    "contains",
    "items",
    "maxContains",
    "maxItems",
    "minContains",
    "minItems",
    "prefixItems",
    "uniqueItems",
  ].some((key) => hasOwn(normalized, key));

  if (!hasOwn(normalized, "type") && hasObjectKeyword) normalized.type = "object";
  if (!hasOwn(normalized, "type") && hasArrayKeyword) normalized.type = "array";
  if (
    normalized.type === "string"
    && isJsonObject(normalized.not)
    && Object.keys(normalized.not).length === 1
    && typeof normalized.not.pattern === "string"
  ) {
    const forbiddenPattern = normalized.not.pattern;
    delete normalized.not;
    const existingAllOf = normalized.allOf ?? [];
    if (!Array.isArray(existingAllOf)) {
      throw new ControlProblemSchemaConfigurationError("String schema allOf must be an array.");
    }
    // JSON Schema pattern searches anywhere, including across Unicode line separators.
    normalized.allOf = [...existingAllOf, {
      type: "string",
      pattern: `^(?![\\s\\S]*(?:${forbiddenPattern}))[\\s\\S]*$`,
    }];
  }
  return normalized;
}

function stripUnsupportedKeywords(value: JsonValue): JsonValue {
  if (Array.isArray(value)) return value.map(stripUnsupportedKeywords);
  if (!isJsonObject(value)) return value;
  return Object.fromEntries(
    Object.entries(value)
      .filter(([key]) =>
        key !== "else"
        && key !== "if"
        && key !== "then"
        && key !== "uniqueItems"
        && !(key === "format" && value.format === "uri-reference"),
      )
      .map(([key, nestedValue]) => [key, stripUnsupportedKeywords(nestedValue)]),
  ) as JsonObject;
}

function toZodSchema(schema: JsonValue): z.ZodType {
  try {
    return z.fromJSONSchema(normalizeForZod(stripUnsupportedKeywords(schema)) as Parameters<typeof z.fromJSONSchema>[0]);
  } catch (error) {
    const message = error instanceof Error ? error.message : "unknown conversion failure";
    throw new ControlProblemSchemaConfigurationError(`Control Problem schema cannot compile to Zod: ${message}`);
  }
}

function resolveLocalReference(schemaClosure: JsonObject, reference: string): JsonObject {
  if (!reference.startsWith("#/")) {
    throw new ControlProblemSchemaConfigurationError(`Control Problem schema has a non-local reference: ${reference}`);
  }
  let resolvedValue: JsonValue = schemaClosure;
  for (const encodedSegment of reference.slice(2).split("/")) {
    const segment = encodedSegment.replaceAll("~1", "/").replaceAll("~0", "~");
    if (!isJsonObject(resolvedValue) || !hasOwn(resolvedValue, segment)) {
      throw new ControlProblemSchemaConfigurationError(`Control Problem schema cannot resolve ${reference}.`);
    }
    resolvedValue = resolvedValue[segment];
  }
  return asJsonObject(resolvedValue, reference);
}

function referencedSchemaClosure(schemaClosure: JsonObject, rootReference: string): JsonObject {
  const referencedDefinitions = new Set<string>();
  const visitedReferences = new Set<string>();
  const pendingReferences = [rootReference];
  const collectReferences = (value: JsonValue): void => {
    if (Array.isArray(value)) { value.forEach(collectReferences); return; }
    if (!isJsonObject(value)) return;
    if (typeof value.$ref === "string") pendingReferences.push(value.$ref);
    Object.values(value).forEach(collectReferences);
  };
  while (pendingReferences.length > 0) {
    const reference = pendingReferences.pop()!;
    if (visitedReferences.has(reference)) continue;
    visitedReferences.add(reference);
    const resolved = resolveLocalReference(schemaClosure, reference);
    const [, definitionName] = reference.slice(2).split("/");
    if (reference.startsWith("#/$defs/") && definitionName) referencedDefinitions.add(definitionName.replaceAll("~1", "/").replaceAll("~0", "~"));
    collectReferences(resolved);
  }
  const definitions = isJsonObject(schemaClosure.$defs) ? schemaClosure.$defs : {};
  return { ...schemaClosure, $defs: Object.fromEntries(Object.entries(definitions).filter(([name]) => referencedDefinitions.has(name))) };
}

function isUriReference(value: string): boolean {
  if (/[\u0000-\u0020<>"{}|\\^`]/u.test(value) || /%(?![0-9A-Fa-f]{2})/u.test(value)) {
    return false;
  }
  const firstDelimiter = value.search(/[/?#]/u);
  const firstSegment = firstDelimiter === -1 ? value : value.slice(0, firstDelimiter);
  return !firstSegment.includes(":") || /^[A-Za-z][A-Za-z0-9+.-]*:/u.test(firstSegment);
}

function addUriReferenceIssues(
  schemaClosure: JsonObject,
  schema: JsonObject,
  value: unknown,
  context: z.RefinementCtx,
  path: Array<string | number>,
  traversedReferences: Set<string>,
): void {
  if (typeof schema.$ref === "string") {
    if (traversedReferences.has(schema.$ref)) return;
    traversedReferences.add(schema.$ref);
    addUriReferenceIssues(
      schemaClosure,
      resolveLocalReference(schemaClosure, schema.$ref),
      value,
      context,
      path,
      traversedReferences,
    );
    traversedReferences.delete(schema.$ref);
    return;
  }
  if (schema.format === "uri-reference" && typeof value === "string" && !isUriReference(value)) {
    context.addIssue({ code: "custom", message: "Invalid URI reference.", path });
  }
  if (isJsonObject(value) && isJsonObject(schema.properties)) {
    for (const [propertyName, propertySchema] of Object.entries(schema.properties)) {
      if (!hasOwn(value, propertyName) || !isJsonObject(propertySchema)) continue;
      addUriReferenceIssues(
        schemaClosure,
        propertySchema,
        value[propertyName],
        context,
        [...path, propertyName],
        traversedReferences,
      );
    }
  }
  if (Array.isArray(value) && isJsonObject(schema.items)) {
    value.forEach((item, index) => addUriReferenceIssues(
      schemaClosure,
      schema.items as JsonObject,
      item,
      context,
      [...path, index],
      traversedReferences,
    ));
  }
  for (const composition of [schema.allOf, schema.anyOf, schema.oneOf]) {
    if (!Array.isArray(composition)) continue;
    for (const branch of composition) {
      if (!isJsonObject(branch)) continue;
      addUriReferenceIssues(schemaClosure, branch, value, context, path, traversedReferences);
    }
  }
}

export function createControlProblemValidator(schemaClosureInput: unknown): z.ZodType {
  const schemaClosure = asJsonObject(schemaClosureInput, "#");
  const rootReference = schemaClosure.$ref;
  if (typeof rootReference !== "string") {
    throw new ControlProblemSchemaConfigurationError("Control Problem schema must declare a local root reference.");
  }
  const rootProblemSchema = resolveLocalReference(schemaClosure, rootReference);
  const baseProblemValidator = toZodSchema(referencedSchemaClosure(schemaClosure, rootReference));

  return baseProblemValidator.superRefine((value, context) => {
    if (!isJsonObject(value)) return;
    addUriReferenceIssues(schemaClosure, rootProblemSchema, value, context, [], new Set());
  });
}
