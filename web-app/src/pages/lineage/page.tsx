import { useCallback, useEffect, useState } from "react";
import { useSearch } from "@tanstack/react-router";
import { useInfiniteQuery, useQuery as useTanStackQuery } from "@tanstack/react-query";
import { metricAPI } from "@/api/metric";
import { lineageAPI, type LineageFocusCone, type LineageResourceRef } from "@/api/lineage";
import type { LineageResourceKind } from "@/api/lineage";
import { useWorkspaceAccess } from "@/components/auth/WorkspaceAccessProvider";
import { MainLayout } from "@/components/layout/MainLayout";
import { LineageSurface } from "@/components/lineage/LineageSurface";
import { usePrincipalId, useWorkspaceId } from "@/hooks/use-workspace";
import { useRouter } from "@/lib/navigation";
import { queryKeys } from "@/lib/query/keys";

export type LineageSearch = {
  kind?: LineageResourceKind;
  id?: string;
};

const lineageQueryKey = (workspaceId: string, principalId: string, focus: LineageResourceRef) =>
  queryKeys.lineage.focus(workspaceId, principalId, focus.kind, focus.id);


const defaultFocusQueryKey = (workspaceId: string, principalId: string) =>
  ["protected", workspaceId, principalId, "lineage", "default-focus"] as const;

const LINEAGE_RESOURCE_PAGE_SIZE = 20;

export function retainPreviousLineageFocus(previous: LineageFocusCone | undefined): LineageFocusCone | undefined {
  return previous;
}

export function lineageHref(workspaceId: string, search: LineageSearch): string {
  const params = new URLSearchParams();
  if (search.kind && search.id) {
    params.set("kind", search.kind);
    params.set("id", search.id);
  }
  const query = params.toString();
  return `/workspaces/${encodeURIComponent(workspaceId)}/lineage${query ? `?${query}` : ""}`;
}

function searchFocus(search: LineageSearch): LineageResourceRef | null {
  return search.kind && search.id ? { kind: search.kind, id: search.id } : null;
}

export default function LineagePage() {
  const workspaceId = useWorkspaceId();
  const principalId = usePrincipalId();
  const router = useRouter();
  const workspaceAccess = useWorkspaceAccess();
  const search = useSearch({ from: "/workspaces/$workspaceId/lineage" }) as LineageSearch;
  const focus = searchFocus(search);
  const metrics = useTanStackQuery({ queryKey: queryKeys.metrics.all(workspaceId, principalId ?? ""), queryFn: () => metricAPI.list(workspaceId), enabled: Boolean(principalId) });
  const [anchorKind, setAnchorKind] = useState<LineageResourceKind>(search.kind ?? "METRIC");
  const [anchorQuery, setAnchorQuery] = useState("");
  const [debouncedAnchorQuery, setDebouncedAnchorQuery] = useState("");
  const defaultFocusQuery = useTanStackQuery({
    queryKey: defaultFocusQueryKey(workspaceId, principalId ?? ""),
    queryFn: ({ signal }) => lineageAPI.getDefaultFocus(workspaceId, signal),
    enabled: Boolean(principalId && !focus),
    retry: false,
  });
  const focusQuery = useTanStackQuery<LineageFocusCone>({
    queryKey: lineageQueryKey(workspaceId, principalId ?? "", focus ?? { kind: "METRIC", id: "no-focus" }),
    queryFn: ({ signal }) => lineageAPI.getFocusCone(workspaceId, focus as LineageResourceRef, signal),
    enabled: Boolean(principalId && focus),
    placeholderData: retainPreviousLineageFocus,
    retry: false,
  });
  useEffect(() => {
    const timer = window.setTimeout(() => setDebouncedAnchorQuery(anchorQuery.trim()), 250);
    return () => window.clearTimeout(timer);
  }, [anchorQuery]);
  useEffect(() => {
    if (focus || !defaultFocusQuery.data) return;
    setAnchorKind(defaultFocusQuery.data.resource.kind);
    router.replace(lineageHref(workspaceId, {
      ...search,
      kind: defaultFocusQuery.data.resource.kind,
      id: defaultFocusQuery.data.resource.id,
    }), { scroll: false });
  }, [defaultFocusQuery.data, focus, router, search, workspaceId]);
  const resourceInventoryQuery = useInfiniteQuery({
    queryKey: ["protected", workspaceId, principalId ?? "", "lineage", "resources", anchorKind, debouncedAnchorQuery],
    queryFn: ({ pageParam, signal }) => lineageAPI.listResources(workspaceId, {
      kind: anchorKind,
      query: debouncedAnchorQuery,
      cursor: pageParam,
      pageSize: LINEAGE_RESOURCE_PAGE_SIZE,
      signal,
    }),
    enabled: Boolean(principalId && focus && debouncedAnchorQuery.length > 0),
    initialPageParam: null as string | null,
    getNextPageParam: (lastPage) => lastPage.nextCursor ?? undefined,
    retry: false,
  });

  const setFocus = useCallback((nextFocus: LineageResourceRef) => {
    setAnchorQuery("");
    setAnchorKind(nextFocus.kind);
    router.push(lineageHref(workspaceId, { ...search, kind: nextFocus.kind, id: nextFocus.id }));
  }, [router, search, workspaceId]);

  const surfaceState = !focus
    ? defaultFocusQuery.isPending || defaultFocusQuery.data
      ? "loading"
      : defaultFocusQuery.isError
        ? "error"
        : "empty"
    : focusQuery.isPending
      ? "loading"
      : focusQuery.isError
        ? "error"
        : focusQuery.data
          ? "ready"
          : "unavailable";
  const surfaceError = focus ? focusQuery.error : defaultFocusQuery.error;

  return <MainLayout>
    <LineageSurface
      workspaceId={workspaceId}
      graph={focusQuery.data ?? null}
      metricDefinitions={metrics.data}
      state={surfaceState}
      errorMessage={surfaceError instanceof Error ? surfaceError.message : null}
      onSetFocus={setFocus}
      onRetry={() => void (focus ? focusQuery.refetch() : defaultFocusQuery.refetch())}
      canCreateSemanticModel={workspaceAccess.hasRole("EDITOR")}
      resourceSearch={focus ? {
        kind: anchorKind,
        query: anchorQuery,
        resources: resourceInventoryQuery.data?.pages.flatMap((page) => page.items) ?? [],
        loading: resourceInventoryQuery.isPending,
        loadingMore: resourceInventoryQuery.isFetchingNextPage,
        error: resourceInventoryQuery.error instanceof Error ? resourceInventoryQuery.error.message : null,
        hasNext: resourceInventoryQuery.hasNextPage,
        onKindChange: setAnchorKind,
        onQueryChange: setAnchorQuery,
        onLoadMore: () => void resourceInventoryQuery.fetchNextPage(),
        onRetry: () => void resourceInventoryQuery.refetch(),
        onSubmit: setFocus,
      } : undefined}
    />
  </MainLayout>;
}
