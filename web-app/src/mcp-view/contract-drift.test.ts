import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";
import type { z } from "zod";
import { AgentActor, AgentApp, DraftBody, DraftReadyResult, DraftStatusBody, QueryResultBody, QueryRunBody, SessionBody } from "./contract";

// The view mirrors the agent read shapes it consumes as strict Zod objects. If the owning contract adds,
// removes, or makes a field optional, this fails before the view starts closing as out of date in chat.

interface Schema {
  properties?: Record<string, Schema>;
  required?: string[];
  additionalProperties?: Schema | boolean;
  items?: Schema;
  oneOf?: Schema[];
}

const definitions = (JSON.parse(readFileSync(fileURLToPath(new URL("../../../contracts/agent-workspace.schema.json", import.meta.url)), "utf8")) as { $defs: Record<string, Schema> }).$defs;

function contractFields(schema: Schema | undefined) {
  if (!schema?.properties) throw new Error("The contract shape has no properties.");
  return { properties: Object.keys(schema.properties).sort(), required: [...(schema.required ?? [])].sort() };
}

function viewFields(schema: unknown) {
  const shape = (schema as z.ZodObject).shape as Record<string, z.ZodType>;
  const properties = Object.keys(shape).sort();
  return { properties, required: properties.filter((name) => !shape[name].safeParse(undefined).success) };
}

const runningQueryRun = (SessionBody.shape.runningQueryRuns as unknown as z.ZodArray<z.ZodType>).element;
const targetQueryRun = (DraftStatusBody.shape.targetQueryRuns as unknown as z.ZodRecord).valueType;
const resultColumn = (QueryResultBody.shape.columns as unknown as z.ZodArray<z.ZodType>).element;
const [runBody, failedRunBody] = QueryRunBody.options;

const mirrored: [string, Schema | undefined, unknown][] = [
  ["DashboardDraftResourceBody", definitions.DashboardDraftResourceBody, DraftBody],
  ["DashboardDraftReadyResult", definitions.DashboardDraftReadyResult, DraftReadyResult],
  ["DashboardDraftStatusResourceBody", definitions.DashboardDraftStatusResourceBody, DraftStatusBody],
  ["DashboardDraftStatusResourceBody.targetQueryRuns item", definitions.DashboardDraftStatusResourceBody?.properties?.targetQueryRuns?.additionalProperties as Schema, targetQueryRun],
  ["DashboardSessionResourceBody", definitions.DashboardSessionResourceBody, SessionBody],
  ["DashboardSessionResourceBody.runningQueryRuns item", definitions.DashboardSessionResourceBody?.properties?.runningQueryRuns?.items, runningQueryRun],
  ["DashboardQueryRunResourceBody (not FAILED)", definitions.DashboardQueryRunResourceBody?.oneOf?.[0], runBody],
  ["DashboardQueryRunResourceBody (FAILED)", definitions.DashboardQueryRunResourceBody?.oneOf?.[1], failedRunBody],
  ["DashboardQueryResultResourceBody", definitions.DashboardQueryResultResourceBody, QueryResultBody],
  ["DashboardQueryResultResourceBody.columns item", definitions.DashboardQueryResultResourceBody?.properties?.columns?.items, resultColumn],
  ["AgentActor", definitions.AgentActor, AgentActor],
  ["AgentApp", definitions.AgentApp, AgentApp],
];

describe("view contract drift", () => {
  it.each(mirrored)("%s has the contract's fields and required fields", (_name, contract, view) => {
    expect(viewFields(view)).toEqual(contractFields(contract));
  });

  it("mirrors the FAILED run branch by its state", () => {
    expect(definitions.DashboardQueryRunResourceBody?.oneOf?.[1]?.properties?.state).toEqual({ const: "FAILED" });
  });
});
