import { readFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";
import problemSchemaClosure from "./problem-schema.json";
import {
  ControlProblemValidationError,
  parseControlProblem,
} from "./problem";
import { ControlProblemSchemaConfigurationError, createControlProblemValidator } from "./problem-runtime";

type JsonObject = { [key: string]: JsonValue };
type JsonValue = boolean | JsonObject | JsonValue[] | null | number | string;

const moduleDirectory = dirname(fileURLToPath(import.meta.url));
const workspaceId = "019e501f-0000-7000-8000-000000000011";

function jsonObject(value: unknown, context: string): JsonObject {
  if (value === null || typeof value !== "object" || Array.isArray(value)) {
    throw new Error(`Expected a JSON object at ${context}.`);
  }
  return value as JsonObject;
}

function jsonArray(value: unknown, context: string): JsonValue[] {
  if (!Array.isArray(value)) {
    throw new Error(`Expected a JSON array at ${context}.`);
  }
  return value;
}

function sourceProblemSchema(): JsonObject {
  const sourcePath = resolve(moduleDirectory, "../../../../contracts/problem-details.schema.json");
  return jsonObject(JSON.parse(readFileSync(sourcePath, "utf8")), sourcePath);
}

function validProblem(): JsonObject {
  return {
    code: "ADMISSION_LIMITED",
    detail: "Retry later.",
    hints: ["Retry after the advertised delay."],
    instance: `/v2/workspaces/${workspaceId}/agent/invoke`,
    nextActions: [{
      operation: "READ",
      path: "workspace/metrics/revenue",
    }],
    retryable: true,
    status: 429,
    title: "Admission limited",
    requestId: "request-1",
    traceId: "trace-1",
    type: "https://monomer.cc/problems/admission-limited",
    violations: [{
      code: "INVALID_INPUT",
      field: "input.goal",
      message: "Goal is required.",
    }],
  };
}

describe("Control ProblemDetails runtime boundary", () => {
  it("preserves both canonical partition filter variants and the 16-entry Problem budget", () => {
    const common = { connectorId: workspaceId, relation: { catalog: "northstar", schema: "analytics", name: "orders" }, partitionKeys: ["order_date"] };
    const model = { ...common, kind: "SEMANTIC_MODEL", semanticModelName: "orders", dimensions: ["ordered_at"], timeRangeApplies: true };
    const spine = { ...common, kind: "TIME_SPINE" };
    const problem = { ...validProblem(), code: "PARTITION_FILTER_REQUIRED", status: 409, retryable: false, partitionFilters: [model, spine] };
    expect(parseControlProblem(problem).partitionFilters).toEqual([model, spine]);
    expect(parseControlProblem({ ...problem, partitionFilters: Array.from({ length: 16 }, () => model) }).partitionFilters).toHaveLength(16);
    expect(() => parseControlProblem({ ...problem, partitionFilters: Array.from({ length: 17 }, () => model) })).toThrow(ControlProblemValidationError);
    for (const invalid of [{ ...model, unexpected: true }, { ...spine, unexpected: true }, { ...spine, semanticModelName: "orders" }, { ...spine, dimensions: [] }, { ...spine, timeRangeApplies: false }]) {
      expect(() => parseControlProblem({ ...problem, partitionFilters: [invalid] })).toThrow(ControlProblemValidationError);
    }
    for (const required of ["semanticModelName", "dimensions", "timeRangeApplies", "connectorId", "relation", "partitionKeys", "kind"]) {
      const invalid: Record<string, unknown> = { ...model };
      delete invalid[required];
      expect(() => parseControlProblem({ ...problem, partitionFilters: [invalid] })).toThrow(ControlProblemValidationError);
    }
  });

  it("intersects duplicate property constraints while normalizing a closed object composition", () => {
    const schema = { $ref: "#/$defs/Value", $defs: { Value: { type: "object", unevaluatedProperties: false, allOf: [
      { type: "object", properties: { kind: { enum: ["MODEL", "SPINE"] }, name: { type: "string", minLength: 3 } }, required: ["kind", "name"] },
      { oneOf: [
        { type: "object", properties: { kind: { const: "MODEL" }, name: { type: "string", maxLength: 5 }, enabled: { type: "boolean" } }, required: ["kind", "enabled"] },
        { type: "object", properties: { kind: { const: "SPINE" } }, required: ["kind"] },
      ] },
    ] } } };
    const validator = createControlProblemValidator(schema);
    expect(validator.safeParse({ kind: "MODEL", name: "abcd", enabled: true }).success).toBe(true);
    expect(validator.safeParse({ kind: "SPINE", name: "abcdef" }).success).toBe(true);
    for (const value of [{ kind: "MODEL", name: "ab", enabled: true }, { kind: "MODEL", name: "abcdef", enabled: true }, { kind: "MODEL", name: "abcd" }, { kind: "SPINE", name: "abcd", enabled: true }, { kind: "MODEL", name: "abcd", enabled: true, extra: true }]) {
      expect(validator.safeParse(value).success).toBe(false);
    }
    expect(() => createControlProblemValidator({ $ref: "#/$defs/Value", $defs: { Value: { type: "object", unevaluatedProperties: false, properties: { name: { type: "string" } } } } })).toThrow(ControlProblemSchemaConfigurationError);
  });

  it("compiles only the root's local reference closure", () => {
    const schema = { $ref: "#/$defs/Value", $defs: {
      Value: { type: "object", properties: { name: { $ref: "#/$defs/Name" } }, required: ["name"], additionalProperties: false },
      Name: { type: "string", minLength: 3 },
      UnusedExternal: { $ref: "monomer://schema/unrelated" },
      UnusedClosedObject: { type: "object", unevaluatedProperties: false, properties: { unrelated: { type: "string" } } },
    } };
    const validator = createControlProblemValidator(schema);
    expect(validator.safeParse({ name: "abc" }).success).toBe(true);
    expect(validator.safeParse({ name: "ab" }).success).toBe(false);
    expect(validator.safeParse({ name: "abc", unrelated: true }).success).toBe(false);
    expect(() => createControlProblemValidator({ ...schema, $ref: "#/$defs/UnusedClosedObject" })).toThrow(ControlProblemSchemaConfigurationError);
    expect(() => createControlProblemValidator({ ...schema, $ref: "#/$defs/UnusedExternal" })).toThrow(ControlProblemSchemaConfigurationError);
  });

  it("keeps the generated Problem field and enum projection aligned with source components", () => {
    const source = sourceProblemSchema();
    const sourceDefinitions = jsonObject(source.$defs, "source $defs");
    const generatedDefinitions = jsonObject(problemSchemaClosure.$defs, "generated $defs");
    const sourceProperties = jsonObject(source.properties, "source properties");
    const generatedProblemDetails = jsonObject(generatedDefinitions.ProblemDetails, "generated ProblemDetails");
    const generatedProperties = jsonObject(generatedProblemDetails.properties, "generated properties");
    const sourceViolation = jsonObject(sourceDefinitions.Violation, "source violation");
    const generatedViolation = jsonObject(generatedDefinitions.ProblemDetailsViolation, "generated violation");
    const sourceAction = jsonObject(
      jsonObject(JSON.parse(readFileSync(resolve(moduleDirectory, "../../../../contracts/agent-workspace.schema.json"), "utf8")), "agent source").$defs,
      "agent source definitions",
    );
    const sourceAvailableAction = jsonObject(sourceAction.AgentAvailableOperation, "source available action");
    const generatedAction = jsonObject(generatedDefinitions.AgentWorkspaceAgentAvailableOperation, "generated action");
    const operationConstants = (definition: JsonObject) => jsonArray(definition.oneOf, "operation variants")
      .map((branch) => jsonObject(jsonObject(jsonObject(branch, "branch").properties, "branch properties").operation, "operation").const);


    expect(Object.keys(generatedProperties).sort()).toEqual(Object.keys(sourceProperties).sort());
    expect(generatedProblemDetails.required).toEqual(source.required);
    expect(generatedProblemDetails.additionalProperties).toBe(false);
    expect(generatedProperties.status).toMatchObject({ maximum: 599, minimum: 100, type: "integer" });
    expect(generatedProperties.code).toMatchObject({ pattern: "^[A-Z][A-Z0-9_]{0,127}$", type: "string" });
    expect(generatedViolation.properties).toEqual(sourceViolation.properties);
    expect(generatedViolation.required).toEqual(sourceViolation.required);
    expect(operationConstants(generatedAction)).toEqual(operationConstants(sourceAvailableAction));
  });

  it("accepts a closed canonical ProblemDetails document", () => {
    const parsed = parseControlProblem(validProblem());

    expect(parsed).toMatchObject({ code: "ADMISSION_LIMITED", status: 429 });
  });

  it("preserves the shared server request id as an opaque support reference", () => {
    const requestId = "srv_5ac15c33-f9ae-4e8a-9b01-73c80be5daa7";
    const parsed = parseControlProblem({ ...validProblem(), requestId, traceId: requestId });

    expect(parsed.requestId).toBe(requestId);
    expect(parsed.traceId).toBe(requestId);
  });

  it("preserves the canonical path negation through referenced next actions", () => {
    const root = "workspace/connectors/0198ef28-1000-7000-8000-000000000001/catalog/schemas/";
    for (const name of ["sales", "a~0b", "a~1b", "a~01b"]) {
      expect(parseControlProblem({ ...validProblem(), nextActions: [{ operation: "READ", path: root + name }] }).nextActions)
        .toEqual([{ operation: "READ", path: root + name }]);
    }
    for (const name of ["~", "~2", "a~escape", "sales~", "a~0~2b"]) {
      expect(() => parseControlProblem({ ...validProblem(), nextActions: [{ operation: "READ", path: root + name }] }))
        .toThrow(ControlProblemValidationError);
    }
  });

  it("rejects unknown fields and invalid nested ProblemDetails values", () => {
    const unknownRoot = { ...validProblem(), unexpected: true };
    const violation = jsonObject(jsonArray(validProblem().violations, "valid violations")[0], "valid violation");
    const action = jsonObject(jsonArray(validProblem().nextActions, "valid actions")[0], "valid action");
    const invalidViolation = {
      ...validProblem(),
      violations: [{ ...violation, unexpected: true }],
    };
    const invalidAction = {
      ...validProblem(),
      nextActions: [{ ...action, operation: "RUN" }],
    };
    const invalidStatus = { ...validProblem(), status: 600 };

    for (const invalidProblem of [unknownRoot, invalidViolation, invalidAction, invalidStatus,
      { ...validProblem(), nextActions: [{ ...action, inputKind: "MADE_UP_PATCH" }] },
      { ...validProblem(), nextActions: [{ operation: "INVOKE", path: "workspace/explore/runs" }] },
      { ...validProblem(), nextActions: [{ operation: "READ", path: "workspace/not-a-resource" }] },
      { ...validProblem(), nextActions: [{ operation: "INVOKE", path: "workspace/metrics/revenue", action: "cancel" }] },
    ]) {
      expect(() => parseControlProblem(invalidProblem)).toThrow(ControlProblemValidationError);
    }
  });

  it("rejects unknown current fields and invalid revision values", () => {
    const unknownCurrentField = {
      ...validProblem(),
      current: { revision: "1", unexpected: true },
    };
    const invalidRevision = {
      ...validProblem(),
      current: {
        revision: 9_007_199_254_740_992,
      },
    };

    for (const invalidProblem of [unknownCurrentField, invalidRevision]) {
      expect(() => parseControlProblem(invalidProblem)).toThrow(ControlProblemValidationError);
    }
  });
});
