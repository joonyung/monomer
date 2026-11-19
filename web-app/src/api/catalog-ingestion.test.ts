import { afterEach, describe, expect, it, vi } from "vitest";
import { catalogIngestionAPI } from "@/api/catalog";

const WORKSPACE_ID = "83000000-0000-0000-0000-000000000001";
const CONNECTOR_ID = "83000000-0000-0000-0000-000000000002";
const JOB_ID = "83000000-0000-0000-0000-000000000003";

function jsonResponse(status: number, body: unknown, headers: Record<string, string> = {}) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json", ...headers },
  });
}

afterEach(() => vi.unstubAllGlobals());

describe("catalog ingestion API", () => {
  it("reads and writes connector-level Catalog membership at its dedicated endpoint", async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(jsonResponse(200, {
        selectedCatalogNames: ["analytics"],
        committedAt: "2026-09-03T01:02:03Z",
      }))
      .mockResolvedValueOnce(jsonResponse(200, {
        selectedCatalogNames: ["analytics", "raw"],
        committedAt: "2026-09-03T01:03:03Z",
      }));
    vi.stubGlobal("fetch", fetchMock);

    await expect(catalogIngestionAPI.getConnectorCatalogSelection(WORKSPACE_ID, CONNECTOR_ID))
      .resolves.toEqual({ selectedCatalogNames: ["analytics"], committedAt: "2026-09-03T01:02:03Z" });
    await expect(catalogIngestionAPI.saveConnectorCatalogSelection(WORKSPACE_ID, CONNECTOR_ID, {
      selectedCatalogNames: ["analytics", "raw"],
    })).resolves.toEqual({ selectedCatalogNames: ["analytics", "raw"], committedAt: "2026-09-03T01:03:03Z" });

    expect(String(fetchMock.mock.calls[0]?.[0])).toContain(
      `/v2/workspaces/${WORKSPACE_ID}/catalog/connectors/${CONNECTOR_ID}/catalog-selection`,
    );
    expect(fetchMock.mock.calls[1]?.[1]?.method).toBe("PUT");
    expect(JSON.parse(fetchMock.mock.calls[1]?.[1]?.body as string)).toEqual({
      selectedCatalogNames: ["analytics", "raw"],
    });
  });

  it("reads and writes a Catalog selection at the entry-scoped endpoint", async () => {
    const selection = {
      selectedSchemas: [{ schemaName: "marketing", relations: { kind: "SELECTED_RELATIONS" as const, relationNames: ["campaigns"] } }],
    };
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(jsonResponse(200, {
        selectedSchemas: [{ schemaName: "marketing", relations: { kind: "ALL_RELATIONS" } }],
        committedAt: "2026-08-21T01:02:03Z",
      }))
      .mockResolvedValueOnce(jsonResponse(200, {
        ...selection,
        committedAt: "2026-08-21T01:03:03Z",
      }));
    vi.stubGlobal("fetch", fetchMock);

    await expect(catalogIngestionAPI.getSelection(WORKSPACE_ID, CONNECTOR_ID, "analytics"))
      .resolves.toMatchObject({
        selectedSchemas: [{ schemaName: "marketing", relations: { kind: "ALL_RELATIONS" } }],
        committedAt: "2026-08-21T01:02:03Z",
      });
    await expect(catalogIngestionAPI.saveSelection(WORKSPACE_ID, CONNECTOR_ID, "analytics", selection))
      .resolves.toMatchObject({ committedAt: "2026-08-21T01:03:03Z" });

    expect(String(fetchMock.mock.calls[0]?.[0])).toContain(
      `/v2/workspaces/${WORKSPACE_ID}/catalog/connectors/${CONNECTOR_ID}/catalogs/analytics/selection`,
    );
    expect(String(fetchMock.mock.calls[1]?.[0])).toContain(
      `/v2/workspaces/${WORKSPACE_ID}/catalog/connectors/${CONNECTOR_ID}/catalogs/analytics/selection`,
    );
    expect(JSON.parse(fetchMock.mock.calls[1]?.[1]?.body as string)).toEqual(selection);
  });

  it("starts a connector or catalog entry without a client idempotency header", async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(202, {
      jobId: JOB_ID,
      workspaceId: WORKSPACE_ID,
      connectorId: CONNECTOR_ID,
      entryKind: "CATALOG",
      catalogName: "analytics",
    }, { Location: `/v2/workspaces/${WORKSPACE_ID}/catalog/connectors/${CONNECTOR_ID}/ingests/${JOB_ID}`, "Retry-After": "2" }));
    vi.stubGlobal("fetch", fetchMock);

    const result = await catalogIngestionAPI.start(WORKSPACE_ID, CONNECTOR_ID, "CATALOG", "analytics");
    expect(result.jobId).toBe(JOB_ID);
    const startCall = fetchMock.mock.calls[0];
    expect(String(startCall?.[0])).toContain(`/v2/workspaces/${WORKSPACE_ID}/catalog/connectors/${CONNECTOR_ID}/ingests`);
    expect(startCall?.[1]?.method).toBe("POST");
    const startHeaders = new Headers(startCall?.[1]?.headers);
    expect(startHeaders.get("Idempotency-Key")).toBeNull();
    expect(startHeaders.get("Content-Type")).toBe("application/json");
    expect(JSON.parse(fetchMock.mock.calls[0]?.[1]?.body as string)).toEqual({ entryKind: "CATALOG", catalogName: "analytics" });
  });

  it("treats no active job as 204 No Content and polls the durable job resource", async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(new Response(null, { status: 204 }))
      .mockResolvedValueOnce(jsonResponse(200, { jobId: JOB_ID, state: "CANCEL_REQUESTED", entryKind: "CONNECTOR", catalogName: null, tasks: [] }));
    vi.stubGlobal("fetch", fetchMock);

    await expect(catalogIngestionAPI.getActive(WORKSPACE_ID, CONNECTOR_ID, null)).resolves.toBeNull();
    expect(String(fetchMock.mock.calls[0]?.[0])).toContain(`/connectors/${CONNECTOR_ID}/ingests/active`);
    await expect(catalogIngestionAPI.getJob(WORKSPACE_ID, CONNECTOR_ID, JOB_ID)).resolves.toMatchObject({ jobId: JOB_ID, state: "CANCEL_REQUESTED" });
    expect(String(fetchMock.mock.calls[1]?.[0])).toContain(`/ingests/${JOB_ID}`);
  });

  it("surfaces a missing active ingest entry as an error instead of an empty result", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(JSON.stringify({ type: "about:blank", title: "Not found", status: 404, detail: "Catalog entry was not found.", instance: "/test", code: "NOT_FOUND", requestId: "request", traceId: "trace", retryable: false }), { status: 404, headers: { "Content-Type": "application/problem+json" } })));

    await expect(catalogIngestionAPI.getActive(WORKSPACE_ID, CONNECTOR_ID, "archive")).rejects.toThrow("Catalog entry was not found.");
  });

  it("cancels the Catalog ingestion job directly", async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(202, {
      jobId: JOB_ID,
      state: "CANCEL_REQUESTED",
      entryKind: "CONNECTOR",
      catalogName: null,
      tasks: [],
    }));
    vi.stubGlobal("fetch", fetchMock);

    await catalogIngestionAPI.requestCancel(WORKSPACE_ID, CONNECTOR_ID, JOB_ID);
    expect(String(fetchMock.mock.calls[0]?.[0])).toContain(
      `/v2/workspaces/${WORKSPACE_ID}/catalog/connectors/${CONNECTOR_ID}/ingests/${JOB_ID}:cancel`,
    );
    expect(fetchMock.mock.calls[0]?.[1]?.method).toBe("POST");
  });

  it("loads cached selection options with server pagination and search", async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(200, {
      parentKind: "SCHEMA",
      childKind: "RELATION",
      catalogName: "analytics",
      schemaName: "orders",
      fetchedAt: "2026-08-21T01:02:03Z",
      options: [{ name: "orders_12480", relationKind: "TABLE", available: true }],
      totalCount: 12480,
      page: 7,
      pageSize: 50,
      latestJob: null,
    }));
    vi.stubGlobal("fetch", fetchMock);

    const result = await catalogIngestionAPI.getSelectionOptions(WORKSPACE_ID, CONNECTOR_ID, {
      parentKind: "SCHEMA",
      catalogName: "analytics",
      schemaName: "orders",
      page: 7,
      pageSize: 50,
      query: "12480",
    });

    expect(result.totalCount).toBe(12480);
    const url = String(fetchMock.mock.calls[0]?.[0]);
    expect(url).toContain("parentKind=SCHEMA");
    expect(url).toContain("page=7");
    expect(url).toContain("pageSize=50");
    expect(url).toContain("query=12480");
  });

  it("defaults job and error lists to the first page", async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(jsonResponse(200, { jobs: [], totalCount: 0, page: 1, pageSize: 10 }))
      .mockResolvedValueOnce(jsonResponse(200, { errors: [], totalCount: 0, page: 1, pageSize: 10 }));
    vi.stubGlobal("fetch", fetchMock);
    await catalogIngestionAPI.listJobs(WORKSPACE_ID, CONNECTOR_ID, null);
    await catalogIngestionAPI.listErrors(WORKSPACE_ID, CONNECTOR_ID, JOB_ID);
    fetchMock.mock.calls.forEach(([url]) => expect(new URL(String(url)).searchParams.get("page")).toBe("1"));
  });

  it("starts and cancels the durable selection list refresh job", async () => {
    const job = {
      jobId: JOB_ID,
      parentKind: "SCHEMA",
      childKind: "RELATION",
      catalogName: "analytics",
      schemaName: "orders",
      state: "RUNNING",
      requestedAt: "2026-08-21T01:02:03Z",
      terminalAt: null,
      failureCode: null,
      safeMessage: null,
    };
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(jsonResponse(202, job))
      .mockResolvedValueOnce(jsonResponse(202, { ...job, state: "CANCEL_REQUESTED" }));
    vi.stubGlobal("fetch", fetchMock);

    await expect(catalogIngestionAPI.startSelectionListRefresh(WORKSPACE_ID, CONNECTOR_ID, "SCHEMA", "analytics", "orders")).resolves.toMatchObject({ state: "RUNNING" });
    await expect(catalogIngestionAPI.cancelSelectionListRefresh(WORKSPACE_ID, CONNECTOR_ID, JOB_ID)).resolves.toMatchObject({ state: "CANCEL_REQUESTED" });
    expect(JSON.parse(fetchMock.mock.calls[0]?.[1]?.body as string)).toEqual({ parentKind: "SCHEMA", catalogName: "analytics", schemaName: "orders" });
    expect(String(fetchMock.mock.calls[1]?.[0])).toContain(`/selection/list-refresh-jobs/${JOB_ID}/cancel`);
  });
});
