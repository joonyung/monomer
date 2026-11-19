import type { z } from "zod";
import {
  DraftBody,
  DraftStatusBody,
  problemFromContent,
  QueryResultBody,
  QueryRunBody,
  READ_TOOL_NAME,
  resourceEnvelope,
  SessionBody,
  VIEW_CONTRACT,
  VIEW_CONTRACT_META_KEY,
  type ReadProblem,
} from "./contract";

export interface ToolCallResult {
  isError?: boolean;
  content?: unknown;
  structuredContent?: unknown;
  _meta?: unknown;
}
export type CallServerTool = (request: { name: string; arguments: Record<string, unknown> }) => Promise<ToolCallResult>;

export class ViewContractMismatchError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "ViewContractMismatchError";
  }
}

export class ResourceReadError extends Error {
  constructor(readonly problem: ReadProblem | null) {
    super(problem?.detail ?? "The resource could not be read.");
    this.name = "ResourceReadError";
  }
}

const DraftEnvelope = resourceEnvelope("DASHBOARD_DRAFT", DraftBody);
const DraftStatusEnvelope = resourceEnvelope("DASHBOARD_DRAFT_STATUS", DraftStatusBody);
const SessionEnvelope = resourceEnvelope("DASHBOARD_SESSION", SessionBody);
const QueryRunEnvelope = resourceEnvelope("DASHBOARD_QUERY_RUN", QueryRunBody);
const QueryResultEnvelope = resourceEnvelope("DASHBOARD_QUERY_RESULT", QueryResultBody);

export interface ResourceReader {
  draft(path: string): Promise<{ revision: string; body: DraftBody }>;
  draftStatus(path: string): Promise<DraftStatusBody>;
  session(path: string): Promise<SessionBody>;
  queryRun(path: string): Promise<QueryRunBody>;
  queryResult(path: string): Promise<QueryResultBody>;
}

// Hosts are not known to forward result _meta on app-initiated calls, so a missing version is
// accepted and the strict envelope shape decides; a different version always stops the view.
function declaresOtherContract(meta: unknown): boolean {
  if (meta === null || typeof meta !== "object" || !Object.hasOwn(meta, VIEW_CONTRACT_META_KEY)) return false;
  return (meta as Record<string, unknown>)[VIEW_CONTRACT_META_KEY] !== VIEW_CONTRACT;
}

// Every read goes through monomer_read only. A read that declares another view contract, or whose
// envelope no longer matches the contract shape, means the server moved on: the view stops.
export function createResourceReader(callServerTool: CallServerTool, workspaceId: string): ResourceReader {
  async function read<Envelope extends z.ZodType<{ path: string }>>(path: string, envelope: Envelope): Promise<z.infer<Envelope>> {
    let result: ToolCallResult;
    try {
      result = await callServerTool({ name: READ_TOOL_NAME, arguments: { workspaceId, path } });
    } catch {
      throw new ResourceReadError(null);
    }
    if (result.isError === true) throw new ResourceReadError(problemFromContent(result.content));
    if (declaresOtherContract(result._meta)) {
      throw new ViewContractMismatchError(`The read of ${path} declared another view contract.`);
    }
    const parsed = envelope.safeParse(result.structuredContent);
    if (!parsed.success || parsed.data.path !== path) {
      throw new ViewContractMismatchError(`The read of ${path} returned an unexpected resource shape.`);
    }
    return parsed.data;
  }
  return {
    async draft(path) {
      const envelope = await read(path, DraftEnvelope);
      if (envelope.revision === undefined) throw new ViewContractMismatchError("The Draft read did not carry its revision.");
      return { revision: envelope.revision, body: envelope.body };
    },
    async draftStatus(path) {
      return (await read(path, DraftStatusEnvelope)).body;
    },
    async session(path) {
      return (await read(path, SessionEnvelope)).body;
    },
    async queryRun(path) {
      return (await read(path, QueryRunEnvelope)).body;
    },
    async queryResult(path) {
      return (await read(path, QueryResultEnvelope)).body;
    },
  };
}
