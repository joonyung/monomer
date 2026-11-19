import { useEffect, useState } from "react";
import { useNavigate, useSearch } from "@tanstack/react-router";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { semanticModelAPI } from "@/api/semantic-model";
import { metricAPI } from "@/api/metric";
import {
  SemanticsExperience,
  type SemanticsView,
} from "@/components/semantics/SemanticsExperience";
import { MainLayout } from "@/components/layout/MainLayout";
import { useConnectors } from "@/hooks/useConnectors";
import { usePrincipalId, useWorkspaceId } from "@/hooks/use-workspace";
import { queryKeys } from "@/lib/query/keys";
import { timeSpineAPI } from "@/api/time-spine";
import type { TimeSpineDefinition } from "@/lib/types/time-spine";

const MODEL_ROOT_PAGE_SIZE = 5;

export default function SemanticsPage() {
  const workspaceId = useWorkspaceId();
  const principalId = usePrincipalId();
  const queryClient = useQueryClient();
  const { connectors, loading: connectorsLoading, error: connectorsError } = useConnectors({ workspaceId });
  const search = useSearch({ from: "/workspaces/$workspaceId/semantics" });
  const navigate = useNavigate({ from: "/workspaces/$workspaceId/semantics" });
  const [searchInput, setSearchInput] = useState(search.q);

  useEffect(() => {
    setSearchInput(search.q);
  }, [search.q]);

  useEffect(() => {
    const timer = window.setTimeout(() => {
      if (searchInput === search.q) return;
      void navigate({
        to: ".",
        replace: true,
        search: (current) => ({
          ...current,
          q: searchInput.trim(),
          modelPage: 0,
        }),
      });
    }, 250);
    return () => window.clearTimeout(timer);
  }, [navigate, search.q, searchInput]);

  const semanticModelsQuery = useQuery({
    queryKey: queryKeys.semanticModels.all(workspaceId, principalId ?? ""),
    queryFn: () => semanticModelAPI.list(workspaceId),
    enabled: Boolean(principalId),
  });

  const metricsQuery = useQuery({
    queryKey: queryKeys.metrics.all(workspaceId, principalId ?? ""),
    queryFn: () => metricAPI.list(workspaceId),
    enabled: Boolean(principalId),
  });

  const timeSpinesQuery = useQuery({
    queryKey: queryKeys.timeSpines.all(workspaceId, principalId ?? ""),
    queryFn: () => timeSpineAPI.list(workspaceId),
    enabled: Boolean(principalId),
  });

  const saveTimeSpineMutation = useMutation({
    mutationFn: ({ connectorId, definition }: { connectorId: string; definition: TimeSpineDefinition }) =>
      timeSpineAPI.replace(workspaceId, connectorId, definition),
    onSuccess: async () => {
      if (!principalId) return;
      await queryClient.invalidateQueries({
        queryKey: queryKeys.timeSpines.all(workspaceId, principalId),
      });
    },
  });

  const updateSearch = (patch: {
    modelFilter?: string;
    kindFilter?: ""|"simple"|"ratio"|"derived"|"cumulative"|"conversion";
    statusFilter?: ""|"broken"|"connector";
    sort?: "name"|"updated";
    view?: SemanticsView;
    connectorId?: string;
    modelPage?: number;
    q?: string;
  }) => {
    void navigate({
      to: ".",
      replace: true,
      search: (current) => ({ ...current, ...patch }),
    });
  };

  const changeView = (view: SemanticsView) => {
    setSearchInput("");
    updateSearch({
      view,
      q: "",
      connectorId: view === "setup" ? search.connectorId : undefined,
      modelPage: 0,
    });
  };

  return (
    <MainLayout>
      <SemanticsExperience
        workspaceId={workspaceId}
        view={search.view}
        filters={search}
        onFilterChange={(patch)=>updateSearch(patch as Parameters<typeof updateSearch>[0])}
        searchQuery={searchInput}
        semanticModels={{
          items: semanticModelsQuery.data ?? [],
          loading: semanticModelsQuery.isPending,
          error: semanticModelsQuery.error?.message ?? null,
        }}
        metrics={{
          items: metricsQuery.data ?? [],
          loading: metricsQuery.isPending,
          error: metricsQuery.error?.message ?? null,
        }}
        modelPage={search.modelPage}
        modelPageSize={MODEL_ROOT_PAGE_SIZE}
        connectors={connectors}
        connectorsLoaded={!connectorsLoading && !connectorsError}
        timeSpines={{
          items: timeSpinesQuery.data ?? [],
          loading: timeSpinesQuery.isPending || connectorsLoading,
          error: connectorsError ?? (timeSpinesQuery.error instanceof Error
            ? timeSpinesQuery.error.message
            : timeSpinesQuery.error
              ? "Failed to fetch Time Spines."
              : null),
        }}
        selectedConnectorId={search.connectorId}
        onViewChange={changeView}
        onSearchQueryChange={setSearchInput}
        onModelPageChange={(modelPage) => updateSearch({ modelPage })}
        onConnectorChange={(connectorId) => updateSearch({
          view: "setup",
          connectorId,
        })}
        onTimeSpineSave={async (connectorId, definition) => {
          const response = await saveTimeSpineMutation.mutateAsync({ connectorId, definition });
          return response.definition;
        }}
      />
    </MainLayout>
  );
}
