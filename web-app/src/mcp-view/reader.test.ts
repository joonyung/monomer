import { describe, expect, it, vi } from "vitest";
import { createResourceReader, ResourceReadError, ViewContractMismatchError, type ToolCallResult } from "./reader";
import { DRAFT_PATH, DRAFT_STATUS_PATH, draftRead, draftStatusRead, ORDERS_RUN, readProblem, REVENUE_RUN, revenueResult, WORKSPACE_ID } from "./fixtures";

function readerAnswering(result: ToolCallResult | Error) {
  const callServerTool = vi.fn(async () => {
    if (result instanceof Error) throw result;
    return structuredClone(result);
  });
  return { callServerTool, reader: createResourceReader(callServerTool, WORKSPACE_ID) };
}

describe("monomer_read client", () => {
  it("calls only monomer_read with the workspace and path", async () => {
    const { callServerTool, reader } = readerAnswering(draftRead({ revision: "4", notes: "Notes", formattedTable: false, ordersRun: ORDERS_RUN }));
    const draft = await reader.draft(DRAFT_PATH);
    expect(callServerTool).toHaveBeenCalledExactlyOnceWith({ name: "monomer_read", arguments: { workspaceId: WORKSPACE_ID, path: DRAFT_PATH } });
    expect(draft.revision).toBe("4");
    expect(Object.keys(draft.body.definition.widgets)).toEqual(["notes", "revenue_chart", "orders_table"]);
  });

  it("returns typed status and result bodies", async () => {
    expect((await readerAnswering(draftStatusRead({ revision: "2", ordersRun: ORDERS_RUN })).reader.draftStatus(DRAFT_STATUS_PATH)).draftRevision).toBe("2");
    expect((await readerAnswering(revenueResult).reader.queryResult(`${REVENUE_RUN}/result`)).rows).toHaveLength(3);
  });

  it("stops on a success that declares another view contract", async () => {
    const nextContract = { ...revenueResult, _meta: { "cc.monomer/viewContract": 2 } };
    await expect(readerAnswering(nextContract).reader.queryResult(`${REVENUE_RUN}/result`)).rejects.toBeInstanceOf(ViewContractMismatchError);
    const unversioned = { ...revenueResult, _meta: { "cc.monomer/viewContract": null } };
    await expect(readerAnswering(unversioned).reader.queryResult(`${REVENUE_RUN}/result`)).rejects.toBeInstanceOf(ViewContractMismatchError);
  });

  it("accepts a success whose host did not forward the contract version and checks its shape", async () => {
    const withoutMeta = { ...revenueResult, _meta: undefined };
    expect((await readerAnswering(withoutMeta).reader.queryResult(`${REVENUE_RUN}/result`)).rows).toHaveLength(3);
    const otherMeta = { ...revenueResult, _meta: { "io.example/trace": "abc" } };
    expect((await readerAnswering(otherMeta).reader.queryResult(`${REVENUE_RUN}/result`)).rows).toHaveLength(3);
    const changed = structuredClone(withoutMeta) as ToolCallResult & { structuredContent: { body: Record<string, unknown> } };
    changed.structuredContent.body.rowsVersion = 2;
    await expect(readerAnswering(changed).reader.queryResult(`${REVENUE_RUN}/result`)).rejects.toBeInstanceOf(ViewContractMismatchError);
  });

  it("stops on an envelope for another path, kind or shape", async () => {
    await expect(readerAnswering(revenueResult).reader.queryResult(`${ORDERS_RUN}/result`)).rejects.toBeInstanceOf(ViewContractMismatchError);
    await expect(readerAnswering(revenueResult).reader.draftStatus(`${REVENUE_RUN}/result`)).rejects.toBeInstanceOf(ViewContractMismatchError);
    const changed = structuredClone(revenueResult) as ToolCallResult & { structuredContent: { body: Record<string, unknown> } };
    changed.structuredContent.body.rowsVersion = 2;
    await expect(readerAnswering(changed).reader.queryResult(`${REVENUE_RUN}/result`)).rejects.toBeInstanceOf(ViewContractMismatchError);
  });

  it("reports tool errors and transport failures as read failures", async () => {
    const denied = readerAnswering(readProblem(403, "FORBIDDEN", "No access."));
    await expect(denied.reader.queryResult(`${REVENUE_RUN}/result`)).rejects.toMatchObject({ problem: { status: 403, code: "FORBIDDEN", detail: "No access." } });
    await expect(readerAnswering(new Error("Host closed")).reader.session(DRAFT_PATH)).rejects.toBeInstanceOf(ResourceReadError);
  });
});
