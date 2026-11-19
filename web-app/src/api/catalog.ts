import { createBrowserControlClient } from "@/api/control/client";
import type { components } from "@/api/control/generated";
import type {
  CatalogIngestAccepted,
  CatalogIngestErrorPage,
  CatalogIngestJob,
  CatalogIngestJobPage,
  CatalogListOptionPage,
  CatalogListRefreshJob,
  CatalogListRefreshParentKind,
  CatalogSelection,
  CatalogSelectionView,
  CatalogConnectorSummary,
  CatalogSummary,
  CatalogTableSearchHit,
  ConnectorCatalogSelection,
  ConnectorCatalogSelectionView,
  RelationDetail,
  RelationListPage,
  SchemaListPage,
} from "@/lib/types/catalog";

const client = createBrowserControlClient();

type CatalogListResponse = components["schemas"]["CatalogList"];
type CatalogSchemaListResponse = components["schemas"]["CatalogSchemaList"];
type CatalogRelationListResponse = components["schemas"]["CatalogRelationList"];
type CatalogRelationResponse = components["schemas"]["CatalogRelation"];
type CatalogSelectionResponse = components["schemas"]["CatalogSelectionView"];
type ConnectorCatalogSelectionResponse = components["schemas"]["ConnectorCatalogSelectionView"];
type CatalogIngestJobResponse = components["schemas"]["CatalogIngestJob"];
type CatalogIngestJobPageResponse = components["schemas"]["CatalogIngestJobPage"];
type CatalogIngestErrorPageResponse = components["schemas"]["CatalogIngestErrorPage"];
type CatalogIngestAcceptedResponse = components["schemas"]["CatalogIngestAccepted"];
type CatalogListOptionPageResponse = components["schemas"]["CatalogListOptionPage"];
type CatalogListRefreshJobResponse = components["schemas"]["CatalogListRefreshJob"];
type CatalogConnectorSummaryListResponse = components["schemas"]["CatalogConnectorSummaryList"];
type CatalogTableSearchResponse = components["schemas"]["CatalogTableSearchResult"];

function mapSelection(value: CatalogSelectionResponse): CatalogSelectionView {
  return {
    selectedSchemas: value.selectedSchemas.map((entry) => ({
      schemaName: entry.schemaName,
      relations: entry.relations.kind === "ALL_RELATIONS"
        ? { kind: "ALL_RELATIONS" }
        : { kind: "SELECTED_RELATIONS", relationNames: [...entry.relations.relationNames] },
    })),
    committedAt: value.committedAt,
  };
}

function mapConnectorCatalogSelection(value: ConnectorCatalogSelectionResponse): ConnectorCatalogSelectionView {
  return {
    selectedCatalogNames: [...value.selectedCatalogNames],
    committedAt: value.committedAt,
  };
}

function dataOrThrow<T>(
  response: { status: number; contentType: string | null; data: unknown },
  expectedStatus = 200,
): T {
  if (response.status !== expectedStatus || response.contentType !== "application/json") {
    const problem = response.data as { detail?: string; title?: string } | undefined;
    throw new Error(problem?.detail ?? problem?.title ?? `Catalog request failed (${response.status}).`);
  }
  return response.data as T;
}

function mapCatalogSummary(value: components["schemas"]["CatalogSummary"]): CatalogSummary {
  return value;
}

function mapSchemaPage(value: CatalogSchemaListResponse): SchemaListPage {
  return {
    schemas: value.schemas,
    totalCount: value.totalCount,
    page: value.page,
    pageSize: value.pageSize,
  };
}

function mapRelationPage(value: CatalogRelationListResponse): RelationListPage {
  return {
    relations: value.relations,
    totalCount: value.totalCount,
    page: value.page,
    pageSize: value.pageSize,
  };
}

export interface ListSchemasOptions {
  catalogName?: string;
  page?: number;
  pageSize?: number;
  query?: string;
}

export interface ListRelationsOptions {
  catalogName?: string;
  page?: number;
  pageSize?: number;
  query?: string;
}

export interface ListSelectionOptions {
  parentKind: CatalogListRefreshParentKind;
  catalogName?: string | null;
  schemaName?: string;
  page?: number;
  pageSize?: number;
  query?: string;
}

function mapSelectionOptions(value: CatalogListOptionPageResponse): CatalogListOptionPage {
  return value;
}

function mapListRefreshJob(value: CatalogListRefreshJobResponse): CatalogListRefreshJob {
  return value;
}

export const catalogAPI = {
  async listCatalogs(workspaceId: string, connectorId: string): Promise<CatalogSummary[]> {
    const response = await client.request("listCatalogs", {
      path: { workspaceId, connectorId },
    }, undefined);
    const body = dataOrThrow<CatalogListResponse>(response);
    return body.catalogs.map(mapCatalogSummary);
  },

  async listSchemas(
    workspaceId: string,
    connectorId: string,
    options: ListSchemasOptions = {},
  ): Promise<SchemaListPage> {
    const response = await client.request("listCatalogSchemas", {
      path: { workspaceId, connectorId },
      query: {
        catalog: options.catalogName,
        page: options.page,
        pageSize: options.pageSize,
        query: options.query,
      },
    }, undefined);
    return mapSchemaPage(dataOrThrow<CatalogSchemaListResponse>(response));
  },

  async listRelations(
    workspaceId: string,
    connectorId: string,
    schemaName: string,
    options: ListRelationsOptions = {},
  ): Promise<RelationListPage> {
    const response = await client.request("listCatalogRelations", {
      path: { workspaceId, connectorId, schemaName },
      query: {
        catalog: options.catalogName,
        page: options.page,
        pageSize: options.pageSize,
        query: options.query,
      },
    }, undefined);
    return mapRelationPage(dataOrThrow<CatalogRelationListResponse>(response));
  },

  async getRelation(
    workspaceId: string,
    connectorId: string,
    relationName: string,
    schemaName: string,
    catalogName?: string,
  ): Promise<RelationDetail> {
    const response = await client.request("getCatalogRelation", {
      path: { workspaceId, connectorId, relationName },
      query: { catalog: catalogName, schema: schemaName },
    }, undefined);
    return dataOrThrow<CatalogRelationResponse>(response);
  },
};

export const catalogIngestionAPI = {
  async getConnectorCatalogSelection(workspaceId: string, connectorId: string): Promise<ConnectorCatalogSelectionView> {
    const response = await client.request("getConnectorCatalogSelection", {
      path: { workspaceId, connectorId },
    }, undefined);
    return mapConnectorCatalogSelection(dataOrThrow<ConnectorCatalogSelectionResponse>(response));
  },

  async saveConnectorCatalogSelection(
    workspaceId: string,
    connectorId: string,
    selection: ConnectorCatalogSelection,
  ): Promise<ConnectorCatalogSelectionView> {
    const response = await client.request("saveConnectorCatalogSelection", {
      path: { workspaceId, connectorId },
      body: selection,
    }, undefined);
    return mapConnectorCatalogSelection(dataOrThrow<ConnectorCatalogSelectionResponse>(response));
  },

  async getSelection(workspaceId: string, connectorId: string, catalogName: string | null): Promise<CatalogSelectionView | null> {
    const response = catalogName === null
      ? await client.request("getCatalogSelection", {
        path: { workspaceId, connectorId },
      }, undefined)
      : await client.request("getCatalogEntrySelection", {
        path: { workspaceId, connectorId, catalogName },
      }, undefined);
    if (response.status === 404) return null;
    return mapSelection(dataOrThrow<CatalogSelectionResponse>(response));
  },

  async saveSelection(
    workspaceId: string,
    connectorId: string,
    catalogName: string | null,
    selection: CatalogSelection,
  ): Promise<CatalogSelectionView> {
    const response = catalogName === null
      ? await client.request("saveCatalogSelection", {
        path: { workspaceId, connectorId },
        body: selection,
      }, undefined)
      : await client.request("saveCatalogEntrySelection", {
        path: { workspaceId, connectorId, catalogName },
        body: selection,
      }, undefined);
    return mapSelection(dataOrThrow<CatalogSelectionResponse>(response));
  },

  async getSelectionOptions(
    workspaceId: string,
    connectorId: string,
    options: ListSelectionOptions,
  ): Promise<CatalogListOptionPage> {
    const response = await client.request("getCatalogSelectionOptions", {
      path: { workspaceId, connectorId },
      query: {
        parentKind: options.parentKind,
        catalogName: options.catalogName,
        schemaName: options.schemaName,
        page: options.page,
        pageSize: options.pageSize,
        query: options.query,
      },
    }, undefined);
    return mapSelectionOptions(dataOrThrow<CatalogListOptionPageResponse>(response));
  },

  async getSelectionListRefresh(workspaceId: string, connectorId: string, jobId: string, parentKind: CatalogListRefreshParentKind, catalogName: string | null, schemaName: string | null): Promise<CatalogListRefreshJob> {
    const response = await client.request("getCatalogSelectionListRefresh", {
      path: { workspaceId, connectorId, jobId },
      query: { parentKind, catalogName: catalogName ?? undefined, schemaName: schemaName ?? undefined },
    }, undefined);
    return mapListRefreshJob(dataOrThrow<CatalogListRefreshJobResponse>(response));
  },

  async startSelectionListRefresh(
    workspaceId: string,
    connectorId: string,
    parentKind: CatalogListRefreshParentKind,
    catalogName: string | null,
    schemaName: string | null,
  ): Promise<CatalogListRefreshJob> {
    const response = await client.request("startCatalogSelectionListRefresh", {
      path: { workspaceId, connectorId },
      body: { parentKind, catalogName, schemaName },
    }, undefined);
    return mapListRefreshJob(dataOrThrow<CatalogListRefreshJobResponse>(response, 202));
  },

  async cancelSelectionListRefresh(
    workspaceId: string,
    connectorId: string,
    jobId: string,
  ): Promise<CatalogListRefreshJob> {
    const response = await client.request("cancelCatalogSelectionListRefresh", {
      path: { workspaceId, connectorId, jobId },
    }, undefined);
    return mapListRefreshJob(dataOrThrow<CatalogListRefreshJobResponse>(response, 202));
  },

  async start(
    workspaceId: string,
    connectorId: string,
    entryKind: "CONNECTOR" | "CATALOG",
    catalogName: string | null,
  ): Promise<CatalogIngestAccepted> {
    const response = await client.request("startCatalogIngest", {
      path: { workspaceId, connectorId },
      body: { entryKind, catalogName },
    }, undefined);
    return dataOrThrow<CatalogIngestAcceptedResponse>(response, 202);
  },

  async getActive(
    workspaceId: string,
    connectorId: string,
    catalogName: string | null,
  ): Promise<CatalogIngestJob | null> {
    const response = await client.request("getActiveCatalogIngest", {
      path: { workspaceId, connectorId },
      query: { catalogName: catalogName ?? undefined },
    }, undefined);
    if (response.status === 204) return null;
    return dataOrThrow<CatalogIngestJobResponse>(response);
  },

  async getJob(workspaceId: string, connectorId: string, jobId: string): Promise<CatalogIngestJob> {
    const response = await client.request("getCatalogIngest", {
      path: { workspaceId, connectorId, jobId },
    }, undefined);
    return dataOrThrow<CatalogIngestJobResponse>(response);
  },

  async listJobs(
    workspaceId: string,
    connectorId: string,
    catalogName: string | null,
    page = 1,
    pageSize = 10,
  ): Promise<CatalogIngestJobPage> {
    const response = await client.request("listCatalogIngests", {
      path: { workspaceId, connectorId },
      query: { catalogName: catalogName ?? undefined, page, pageSize },
    }, undefined);
    return dataOrThrow<CatalogIngestJobPageResponse>(response);
  },

  async listErrors(
    workspaceId: string,
    connectorId: string,
    jobId: string,
    page = 1,
    pageSize = 10,
  ): Promise<CatalogIngestErrorPage> {
    const response = await client.request("listCatalogIngestErrors", {
      path: { workspaceId, connectorId, jobId },
      query: { page, pageSize },
    }, undefined);
    return dataOrThrow<CatalogIngestErrorPageResponse>(response);
  },

  async requestCancel(workspaceId: string, connectorId: string, jobId: string): Promise<void> {
    const response = await client.request("cancelCatalogIngest", {
      path: { workspaceId, connectorId, jobId },
    }, undefined);
    if (response.status !== 202) {
      const problem = response.data as { detail?: string; title?: string } | undefined;
      throw new Error(problem?.detail ?? problem?.title ?? `Cancel request failed (${response.status}).`);
    }
  },
};

export const catalogWorkspaceAPI = {
  async listConnectorSummaries(workspaceId: string, signal?: AbortSignal): Promise<CatalogConnectorSummary[]> {
    const response = await client.request("listCatalogConnectorSummaries", {
      path: { workspaceId },
    }, signal);
    return dataOrThrow<CatalogConnectorSummaryListResponse>(response).connectors;
  },

  async searchTables(workspaceId: string, query: string, limit: number, signal?: AbortSignal): Promise<CatalogTableSearchHit[]> {
    const response = await client.request("searchCatalogTables", {
      path: { workspaceId },
      query: { query, limit },
    }, signal);
    return dataOrThrow<CatalogTableSearchResponse>(response).tables;
  },
};
