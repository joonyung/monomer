import { describe, expect, it } from "vitest";
import { readFile } from "node:fs/promises";
import typescript from "typescript";
import {
  _internal,
  generateControlClient,
} from "../../../scripts/generate-control-client.mjs";

function objectValue(value: unknown, context: string): { [key: string]: unknown } {
  if (value === null || typeof value !== "object" || Array.isArray(value)) {
    throw new Error(`Expected object at ${context}.`);
  }
  return value as { [key: string]: unknown };
}

describe("Control client generator", () => {
  it("rejects duplicate members and lone surrogates before ordinary JSON parsing", () => {
    expect(() => _internal.parseSchemaDocument('{"x":1,"x":2}', "duplicate.json"))
      .toThrow("strict I-JSON");
    expect(() => _internal.parseSchemaDocument('{"x":"\\ud800"}', "surrogate.json"))
      .toThrow("lone high surrogate");
  });

  it("fails explicitly when an Agent edit path pattern cannot preserve its category", () => {
    for (const pattern of ["workspace/items/[a-z]+", "^workspace/(items|other)/[a-z]+$", "^workspace/(?=items)/[a-z]+$"]) {
      expect(() => _internal.editPathPatternType(pattern)).toThrow("Agent edit path pattern");
    }
  });

  it("preserves Catalog edit categories when an optional segment carries a name", () => {
    const pattern = String.raw`^workspace/connectors/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[89ab][0-9a-f]{3}-[0-9a-f]{12}/catalog(?:/catalogs/[^/\u0000-\u001F\u007F-\u009F]+)?/selection$`;
    const printed = typescript.createPrinter().printNode(
      typescript.EmitHint.Unspecified,
      _internal.editPathPatternType(pattern),
      typescript.createSourceFile("path.ts", "", typescript.ScriptTarget.Latest),
    );
    expect(printed).toBe('`workspace/connectors/${string}/catalog/selection` | `workspace/connectors/${string}/catalog/catalogs/${string}/selection`');
  });

  it("drops name validity lookaheads from Semantic edit path categories", () => {
    const pattern = String.raw`^workspace/semantic-models/(?![a-z0-9_]*__)[a-z](?:[a-z0-9_]*[a-z0-9])?$`;
    const printed = typescript.createPrinter().printNode(
      typescript.EmitHint.Unspecified,
      _internal.editPathPatternType(pattern),
      typescript.createSourceFile("path.ts", "", typescript.ScriptTarget.Latest),
    );
    expect(printed).toBe("`workspace/semantic-models/${string}`");
  });

  it("dereferences component aliases and rejects an alias cycle", () => {
    const dereferenced = _internal.dereferenceComponentAliases({
      components: {
        responses: {
          Problem429: {
            content: {
              "application/problem+json": { schema: { type: "object" } },
            },
          },
          TooManyRequests: { $ref: "#/components/responses/Problem429" },
        },
      },
    });

    const components = objectValue(dereferenced, "bundle").components;
    const responses = objectValue(objectValue(components, "components").responses, "responses");

    expect(responses.TooManyRequests).toEqual(
      responses.Problem429,
    );
    expect(() => _internal.dereferenceComponentAliases({
      components: {
        responses: {
          First: { $ref: "#/components/responses/Second" },
          Second: { $ref: "#/components/responses/First" },
        },
      },
    })).toThrow("alias cycle");
  });

  it("keeps Draft refresh query-only and requires complete Session resolution maps", async () => {
    const contract = JSON.parse(await readFile(new URL("../../../../contracts/control-service.openapi.json", import.meta.url), "utf8")) as {
      components: { schemas: Record<string, { required?: string[]; properties?: Record<string, unknown> }> };
    };
    const schemas = contract.components.schemas;
    expect(schemas.DashboardDraftRefreshRequest.required).toEqual(["queryNames"]);
    expect(schemas.DashboardDraftRefreshRequest.properties).not.toHaveProperty("variables");
    expect(schemas.DashboardSessionRefreshRequest.required).toEqual(["variables", "queryNames"]);
    expect(schemas.DashboardSessionResolveRequest.required).toEqual(["variables"]);
    expect(schemas.DashboardSessionResolutionResponse.required).toEqual(["variables", "queryRuns"]);
    expect(schemas.DashboardSessionResponse.properties).not.toHaveProperty("variableRevision");
    expect(schemas.DashboardSessionResponse.required).toContain("initialVariables");
    expect(schemas.DashboardSessionResponse.required).toContain("runningQueryRuns");
    const agentContract = JSON.parse(await readFile(new URL("../../../../contracts/agent-workspace.schema.json", import.meta.url), "utf8")) as { $defs: object };
    expect(agentContract.$defs).not.toHaveProperty("DashboardSessionEditRequest");
  });

  it("is fresh and deterministic with Problem closure and response rails", async () => {
    const first = await generateControlClient({ checkOnly: true });
    const second = await generateControlClient({ checkOnly: true });
    const problemClosure = JSON.parse(first.problemSchemaText) as { $defs: object; $ref: string };

    expect(first.generatedTypesText).toBe(second.generatedTypesText);
    expect(first.generatedOperationsText).toBe(second.generatedOperationsText);
    expect(first.manifest.manifestSha256).toBe(second.manifest.manifestSha256);
    expect(first.generatedTypesText).toContain("TooManyRequests: {");
    expect(first.generatedTypesText).toContain('"application/problem+json": components["schemas"]["ProblemDetails"]');
    expect(first.generatedTypesText).toContain("export type ControlSemanticDecimal38_18 = string");
    expect(first.generatedTypesText).not.toContain(": any");
    expect(first.generatedOperationsText).toContain('"requiredHeaders": [');
    expect(problemClosure.$ref).toBe("#/$defs/ProblemDetails");
    expect(problemClosure.$defs).toHaveProperty("ProblemDetailsSafeCurrent");
    expect(problemClosure.$defs).not.toHaveProperty("OperationDocument");

    const exploreResultStart = first.generatedTypesText.indexOf("ExploreResultView:");
    const exploreResultEnd = first.generatedTypesText.indexOf("ExploreRunView:", exploreResultStart);
    const exploreResultTypes = first.generatedTypesText.slice(exploreResultStart, exploreResultEnd);
    expect(exploreResultTypes).not.toContain("manifestDigest");
    expect(exploreResultTypes).not.toContain("finalizedAt");

    const exploreRequestStart = first.generatedTypesText.indexOf("SqlExploreRunRequest:");
    const exploreRequestEnd = first.generatedTypesText.indexOf("Uuid:", exploreRequestStart);
    const exploreRequestTypes = first.generatedTypesText.slice(exploreRequestStart, exploreRequestEnd);
    expect(exploreRequestTypes).not.toContain("maximumResultRows");

    const exploreRunStart = first.generatedTypesText.indexOf("ExploreRunView:");
    const exploreRunTypes = first.generatedTypesText.slice(
      exploreRunStart,
      first.generatedTypesText.indexOf("MetricResponse:", exploreRunStart),
    );
    expect(exploreRunTypes).toContain('state: "RUNNING" | "SUCCEEDED" | "FAILED" | "CANCELLED";');
    expect(exploreRunTypes).not.toContain("FINALIZATION_FAILED");

    const agentExploreRunStart = first.generatedTypesText.indexOf("AgentWorkspaceExploreRunResourceBody:");
    const agentExploreRunTypes = first.generatedTypesText.slice(
      agentExploreRunStart,
      first.generatedTypesText.indexOf("AgentWorkspaceExploreRunResourcePath:", agentExploreRunStart),
    );
    expect(agentExploreRunTypes).toContain('state: "RUNNING" | "SUCCEEDED";');
    expect(agentExploreRunTypes).toContain('state: "FAILED" | "CANCELLED";');
    expect(agentExploreRunTypes).toContain("failureCode:");
    expect(agentExploreRunTypes).toContain("failureDetail:");
    expect(agentExploreRunTypes).not.toContain("failureCode?:");
    expect(agentExploreRunTypes).not.toContain("failureDetail?:");

    expect(first.generatedTypesText).toContain('`workspace/semantic-models/${string}`');
    expect(first.generatedTypesText).toContain('`workspace/dashboards/${string}/sessions/${string}`');
    expect(first.generatedTypesText).toContain('`/variables/${string}`');
    expect(first.generatedTypesText.replace(/\s+/g, "")).toContain('patch:[components["schemas"]["AgentWorkspaceCatalogSelectionPatchOperation"]]');
    expect(first.generatedTypesText).toContain("revision?: never;");

    expect(first.generatedTypesText).toContain("DashboardDocument:");
    expect(first.generatedTypesText).toContain("DashboardResult:");
    expect(first.generatedOperationsText).toContain('"contentTypes": [\n          "text/event-stream"');
    const manifest = JSON.parse(first.manifestText) as { dependencyGraph: unknown[] };
    expect(manifest.dependencyGraph).toEqual(expect.arrayContaining([
      expect.objectContaining({
        id: "monomer://schema/dashboard/1",
        sourcePath: "control-service/modules/dashboard/src/main/resources/schema/dashboard.schema.json",
      }),
    ]));
    for (const operationId of [
      "listDashboards",
      "createDashboard",
      "getDashboard",
      "openDashboardSession",
      "getDashboardSession",
      "resolveDashboardSession",
      "getDashboardSessionQueryRun",
      "refreshDashboardSession",
      "watchDashboardSession",
      "getDashboardQueryResult",
    ]) {
      expect(first.generatedOperationsText).toContain(`"operationId": "${operationId}"`);
    }
    // Two complete generations took 7.6s in the loaded suite.
  }, 15_000);
});
