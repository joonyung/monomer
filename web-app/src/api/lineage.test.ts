import { beforeEach, describe, expect, it, vi } from "vitest";
import { lineageAPI, mapLineageFocusCone, mapLineageResourcePage } from "@/api/lineage";
import { useAuthStore } from "@/lib/auth/store";

const workspaceId = "019e501f-0000-7000-8000-000000000011";
const focus = { kind: "METRIC" as const, id: "019e501f-0000-7000-8000-000000000012" };

function graphResponse() {
  return {
    lineageFocusCone: {
      dashboardUsages: [],
      workspaceId,
      focus: { resource: focus, label: "Orders", address: "orders", description: "A governed model", missing: false, connector: null },
      missingDefinitions: [],
      ancestors: [{ resource: { kind: "RELATION", id: "019e501f-0000-7000-8000-000000000013" }, label: "orders_fact", address: "warehouse.analytics.orders_fact", description: null, missing: false, connector: { id: "019e501f-0000-7000-8000-000000000016", displayName: "Northstar", type: "POSTGRESQL" } }],
      descendants: [],
      siblings: [],
      edges: [{ dependent: focus, parent: { kind: "RELATION", id: "019e501f-0000-7000-8000-000000000013" } }],
      siblingEdges: [],
      entityPeers: [{ resource: { resource: { kind: "SEMANTIC_MODEL", id: "019e501f-0000-7000-8000-000000000014" }, label: "Customers", address: "customers", description: null, missing: false, connector: null }, sharedEntities: ["customer"], alreadyInCone: false }],
      detail: {
        relation: null,
        semanticModel: null,
        metric: { type: "SIMPLE", inputs: [], timeGranularity: null },
      },
    },
  };
}

describe("Lineage GraphQL API", () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    useAuthStore.setState({ accessToken: "lineage-token", status: "authenticated" });
  });

  it("maps the membership-readable focus cone without adding client ACL evaluation", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(new Response(JSON.stringify({ data: graphResponse() }), {
      status: 200,
      headers: { "Content-Type": "application/json" },
    }));

    await expect(lineageAPI.getFocusCone(workspaceId, focus)).resolves.toMatchObject({
      workspaceId,
      focus: { resource: focus, label: "Orders" },
      entityPeers: [{ sharedEntities: ["customer"], alreadyInCone: false }],
    });
    const request = JSON.parse(String(fetchMock.mock.calls[0][1]?.body));
    expect(request.query).toContain("lineageFocusCone(workspaceId: $workspaceId, focus: $focus)");
    expect(request.query).toContain("edges { dependent { kind id } parent { kind id } }");
    expect(request.query).not.toContain("connectorAccess");
    expect(request.query).not.toContain("accessEvaluation");
    expect(request.variables).toEqual({ workspaceId, focus });
  });

  it("requests one server-selected default focus and preserves an empty workspace", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch")
      .mockResolvedValueOnce(new Response(JSON.stringify({
        data: { lineageDefaultFocus: graphResponse().lineageFocusCone.focus },
      }), { status: 200, headers: { "Content-Type": "application/json" } }))
      .mockResolvedValueOnce(new Response(JSON.stringify({
        data: { lineageDefaultFocus: null },
      }), { status: 200, headers: { "Content-Type": "application/json" } }));

    await expect(lineageAPI.getDefaultFocus(workspaceId)).resolves.toMatchObject({
      resource: focus,
      label: "Orders",
    });
    await expect(lineageAPI.getDefaultFocus(workspaceId)).resolves.toBeNull();
    const request = JSON.parse(String(fetchMock.mock.calls[0][1]?.body));
    expect(request.query).toContain("lineageDefaultFocus(workspaceId: $workspaceId)");
    expect(request.variables).toEqual({ workspaceId });
  });

  it("fails closed for an unknown resource kind", () => {
    const response = graphResponse().lineageFocusCone;
    expect(() => mapLineageFocusCone({ ...response, focus: { ...response.focus, resource: { ...focus, kind: "UNKNOWN" } } })).toThrow("unknown resource kind");
  });

  it("requests a membership-readable cursor page without exposing UUID entry semantics", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(new Response(JSON.stringify({
      data: {
        lineageResources: {
          items: [graphResponse().lineageFocusCone.focus],
          nextCursor: "cursor-2",
        },
      },
    }), {
      status: 200,
      headers: { "Content-Type": "application/json" },
    }));

    await expect(lineageAPI.listResources(workspaceId, {
      kind: "METRIC",
      query: "orders",
      cursor: "cursor-1",
      pageSize: 20,
    })).resolves.toMatchObject({
      nextCursor: "cursor-2",
      items: [{ label: "Orders" }],
    });
    const request = JSON.parse(String(fetchMock.mock.calls[0][1]?.body));
    expect(request.query).toContain("lineageResources(workspaceId: $workspaceId");
    expect(request.query).toContain("$cursor: String");
    expect(request.query).toContain("cursor: $cursor");
    expect(request.variables).toEqual({ workspaceId, kind: "METRIC", query: "orders", cursor: "cursor-1", pageSize: 20 });
  });

  it("preserves the null cursor at the beginning of a resource search", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(new Response(JSON.stringify({
      data: { lineageResources: { items: [], nextCursor: null } },
    }), { status: 200, headers: { "Content-Type": "application/json" } }));

    await expect(lineageAPI.listResources(workspaceId, {
      kind: "RELATION",
      query: " ",
      cursor: null,
      pageSize: 20,
    })).resolves.toEqual({ items: [], nextCursor: null });
    const request = JSON.parse(String(fetchMock.mock.calls[0][1]?.body));
    expect(request.variables).toEqual({ workspaceId, kind: "RELATION", query: null, cursor: null, pageSize: 20 });
  });

  it("rejects an invalid next cursor", () => {
    expect(() => mapLineageResourcePage({
      items: [],
      nextCursor: 42 as unknown as string,
    })).toThrow("invalid next cursor");
  });

  it("fails closed when the server violates the Relation connector invariant", () => {
    const basePage = {
      nextCursor: null,
    };
    expect(() => mapLineageResourcePage({
      ...basePage,
      items: [{ ...graphResponse().lineageFocusCone.ancestors[0], connector: null }],
    })).toThrow("invalid connector for the resource kind");
    expect(() => mapLineageResourcePage({
      ...basePage,
      items: [{
        ...graphResponse().lineageFocusCone.focus,
        connector: graphResponse().lineageFocusCone.ancestors[0].connector,
      }],
    })).toThrow("invalid connector for the resource kind");
  });
});

it("maps missing definitions separately without rejecting the healthy focus cone", () => {
  const current = graphResponse().lineageFocusCone;
  const missing = { resource: { kind: "METRIC", id: "missing-id" }, label: "gone_metric", address: "gone_metric", description: null, connector: null, missing: true };
  const mapped = mapLineageFocusCone({ ...current, missingDefinitions: [missing], edges: [...current.edges, { dependent: focus, parent: missing.resource }] });
  expect(mapped.focus.missing).toBe(false);
  expect(mapped.missingDefinitions).toEqual([missing]);
  expect(mapped.edges).toHaveLength(2);
});
