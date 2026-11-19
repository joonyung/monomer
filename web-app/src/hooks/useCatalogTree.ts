import { useCallback, useMemo, useState } from "react";
import { hashKey, partialMatchKey, useQuery, useQueryClient, type QueryKey } from "@tanstack/react-query";
import { catalogAPI } from "@/api/catalog";
import { queryKeys } from "@/lib/query/keys";
import type { RelationDetail, RelationSummary } from "@/lib/types/catalog";
import { usePrincipalId, useWorkspaceId } from "@/hooks/use-workspace";

const PAGE_SIZE = 50;

export interface SelectedSchema {
  catalogName?: string | null;
  schemaName: string;
}

export interface SelectedRelation {
  catalogName?: string | null;
  schemaName: string;
  relationName: string;
}

export interface CatalogMainData {
  schemas: Awaited<ReturnType<typeof catalogAPI.listSchemas>>["schemas"];
  schemasTotalCount: number;
  schemasPage: number;
  schemasPageSize: number;
  loadingSchemas: boolean;
  schemaQuery: string;
  setSchemaQuery: (query: string) => void;
  setSchemasPage: (page: number) => void;
  relations: Awaited<ReturnType<typeof catalogAPI.listRelations>>["relations"];
  relationsTotalCount: number;
  relationsPage: number;
  relationsPageSize: number;
  loadingRelations: boolean;
  relationQuery: string;
  setRelationQuery: (query: string) => void;
  setRelationsPage: (page: number) => void;
  selectedSchema: SelectedSchema | null;
  selectSchema: (schema: SelectedSchema) => void;
  selectedRelation: SelectedRelation | null;
  relationDetail: RelationDetail | null;
  loadingRelationDetail: boolean;
  selectRelation: (relation: RelationSummary) => void;
  error: string | null;
  refresh: () => Promise<void>;
}

export interface CatalogTreeLocation {
  schemaName: string | null;
  tableName: string | null;
}

export interface CatalogTreeNavigation {
  selectSchema: (schemaName: string) => void;
  selectTable: (schemaName: string, tableName: string) => void;
}

interface CatalogListPosition {
  scope: string;
  page: number;
  query: string;
}

export function catalogListPositionInScope(position: CatalogListPosition, scope: string): CatalogListPosition {
  return position.scope === scope ? position : { scope, page: 1, query: "" };
}

export function retainPreviousPageInScope<TData>(
  previous: TData | undefined,
  previousQueryKey: QueryKey | undefined,
  scopeKey: QueryKey,
): TData | undefined {
  return previousQueryKey && partialMatchKey(previousQueryKey, scopeKey) ? previous : undefined;
}

// Page and search belong to one list scope; a new scope starts at page 1 with no search in the same render.
function useCatalogListPosition(scopeKey: QueryKey) {
  const scope = hashKey(scopeKey);
  const [storedPosition, setStoredPosition] = useState<CatalogListPosition>(() => ({ scope, page: 1, query: "" }));
  const position = catalogListPositionInScope(storedPosition, scope);
  if (position !== storedPosition) setStoredPosition(position);

  const setPage = useCallback((page: number) => {
    setStoredPosition((latest) => ({ ...catalogListPositionInScope(latest, scope), page: Math.max(1, page) }));
  }, [scope]);
  const setQuery = useCallback((query: string) => {
    setStoredPosition({ scope, page: 1, query });
  }, [scope]);

  return { page: position.page, query: position.query, setPage, setQuery };
}

// The selected schema and table come from the URL; selecting one navigates instead of holding local state.
export function useCatalogTree(
  connectorId: string | null,
  selectedCatalogName: string | null,
  location: CatalogTreeLocation,
  navigation: CatalogTreeNavigation,
): CatalogMainData {
  const workspaceId = useWorkspaceId();
  const principalId = usePrincipalId();
  const queryClient = useQueryClient();
  const selectedSchema = useMemo<SelectedSchema | null>(
    () => location.schemaName ? { catalogName: selectedCatalogName, schemaName: location.schemaName } : null,
    [location.schemaName, selectedCatalogName],
  );
  const selectedRelation = useMemo<SelectedRelation | null>(
    () => location.schemaName && location.tableName
      ? { catalogName: selectedCatalogName, schemaName: location.schemaName, relationName: location.tableName }
      : null,
    [location.schemaName, location.tableName, selectedCatalogName],
  );
  const schemasScopeKey = queryKeys.catalog.schemasAll(
    workspaceId ?? "",
    principalId ?? "",
    connectorId ?? "",
    selectedCatalogName,
  );
  const relationsScopeKey = queryKeys.catalog.relationsAll(
    workspaceId ?? "",
    principalId ?? "",
    connectorId ?? "",
    selectedSchema?.catalogName ?? selectedCatalogName,
    selectedSchema?.schemaName ?? "",
  );
  const {
    page: schemasPage,
    query: schemaQuery,
    setPage: setSchemasPage,
    setQuery: setSchemaQuery,
  } = useCatalogListPosition(schemasScopeKey);
  const {
    page: relationsPage,
    query: relationQuery,
    setPage: setRelationsPage,
    setQuery: setRelationQuery,
  } = useCatalogListPosition(relationsScopeKey);

  const schemasQuery = useQuery({
    queryKey: queryKeys.catalog.schemas(
      workspaceId ?? "",
      principalId ?? "",
      connectorId ?? "",
      selectedCatalogName,
      schemasPage,
      PAGE_SIZE,
      schemaQuery,
    ),
    queryFn: () => catalogAPI.listSchemas(workspaceId as string, connectorId as string, {
      catalogName: selectedCatalogName ?? undefined,
      page: schemasPage,
      pageSize: PAGE_SIZE,
      query: schemaQuery.trim() || undefined,
    }),
    enabled: Boolean(workspaceId && principalId && connectorId),
    placeholderData: (previous, previousQuery) => retainPreviousPageInScope(previous, previousQuery?.queryKey, schemasScopeKey),
  });

  const relationsQuery = useQuery({
    queryKey: queryKeys.catalog.relations(
      workspaceId ?? "",
      principalId ?? "",
      connectorId ?? "",
      selectedSchema?.catalogName ?? selectedCatalogName,
      selectedSchema?.schemaName ?? "",
      relationsPage,
      PAGE_SIZE,
      relationQuery,
    ),
    queryFn: () => catalogAPI.listRelations(
      workspaceId as string,
      connectorId as string,
      selectedSchema?.schemaName as string,
      {
        catalogName: selectedSchema?.catalogName ?? selectedCatalogName ?? undefined,
        page: relationsPage,
        pageSize: PAGE_SIZE,
        query: relationQuery.trim() || undefined,
      },
    ),
    enabled: Boolean(workspaceId && principalId && connectorId && selectedSchema),
    placeholderData: (previous, previousQuery) => retainPreviousPageInScope(previous, previousQuery?.queryKey, relationsScopeKey),
  });

  const relationDetailQuery = useQuery({
    queryKey: queryKeys.catalog.relation(
      workspaceId ?? "",
      principalId ?? "",
      connectorId ?? "",
      selectedRelation?.catalogName ?? selectedCatalogName,
      selectedRelation?.schemaName ?? "",
      selectedRelation?.relationName ?? "",
    ),
    queryFn: () => catalogAPI.getRelation(
      workspaceId as string,
      connectorId as string,
      selectedRelation?.relationName as string,
      selectedRelation?.schemaName as string,
      selectedRelation?.catalogName ?? selectedCatalogName ?? undefined,
    ),
    enabled: Boolean(workspaceId && principalId && connectorId && selectedRelation),
  });

  const { selectSchema: navigateToSchema, selectTable: navigateToTable } = navigation;
  const selectSchema = useCallback((schema: SelectedSchema) => navigateToSchema(schema.schemaName), [navigateToSchema]);
  const selectRelation = useCallback((relation: RelationSummary) => navigateToTable(relation.schemaName, relation.name), [navigateToTable]);

  const refresh = useCallback(async () => {
    const requests: Promise<unknown>[] = [schemasQuery.refetch()];
    if (selectedSchema) requests.push(relationsQuery.refetch());
    if (selectedRelation) requests.push(relationDetailQuery.refetch());
    await Promise.all(requests);
    await queryClient.invalidateQueries({ queryKey: queryKeys.catalog.all(workspaceId ?? "", principalId ?? "") });
  }, [principalId, queryClient, relationDetailQuery, relationsQuery, schemasQuery, selectedRelation, selectedSchema, workspaceId]);

  return useMemo(() => ({
    schemas: schemasQuery.data?.schemas ?? [],
    schemasTotalCount: schemasQuery.data?.totalCount ?? 0,
    schemasPage: schemasQuery.data?.page ?? schemasPage,
    schemasPageSize: PAGE_SIZE,
    loadingSchemas: schemasQuery.isPending && schemasQuery.fetchStatus === "fetching",
    schemaQuery,
    setSchemaQuery,
    setSchemasPage,
    relations: relationsQuery.data?.relations ?? [],
    relationsTotalCount: relationsQuery.data?.totalCount ?? 0,
    relationsPage: relationsQuery.data?.page ?? relationsPage,
    relationsPageSize: PAGE_SIZE,
    loadingRelations: relationsQuery.isPending && relationsQuery.fetchStatus === "fetching",
    relationQuery,
    setRelationQuery,
    setRelationsPage,
    selectedSchema,
    selectSchema,
    selectedRelation,
    relationDetail: relationDetailQuery.data ?? null,
    loadingRelationDetail: relationDetailQuery.isPending && relationDetailQuery.fetchStatus === "fetching",
    selectRelation,
    error: schemasQuery.error?.message ?? relationsQuery.error?.message ?? relationDetailQuery.error?.message ?? null,
    refresh,
  }), [relationDetailQuery.data, relationDetailQuery.error, relationDetailQuery.fetchStatus, relationDetailQuery.isPending, relationsQuery.data, relationsQuery.error, relationsQuery.fetchStatus, relationsQuery.isPending, relationQuery, relationsPage, refresh, schemaQuery, schemasPage, schemasQuery.data, schemasQuery.error, schemasQuery.fetchStatus, schemasQuery.isPending, selectRelation, selectSchema, selectedRelation, selectedSchema, setRelationQuery, setRelationsPage, setSchemaQuery, setSchemasPage]);
}
