import { useEffect, useMemo, useRef, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Plug, X } from "lucide-react";
import { catalogWorkspaceAPI } from "@/api/catalog";
import { connectorsAPI } from "@/api/connectors";
import { useWorkspaceRouteContext } from "@/components/auth/TenantRouteContext";
import { ApiError } from "@/lib/api/client";
import { useWorkspaceAccess } from "@/components/auth/WorkspaceAccessProvider";
import { ConnectorDeleteDialog } from "@/components/connectors/ConnectorDeleteDialog";
import { ConnectorMutationDialog } from "@/components/connectors/ConnectorMutationDialog";
import { useConnectorCreationNotice } from "@/components/connectors/connectorCreationNotice";
import { ConnectorList, type ConnectorSummaries } from "@/components/connectors/ConnectorList";
import { MainLayout } from "@/components/layout/MainLayout";
import { WorkspacePageHeader } from "@/components/layout/WorkspacePageHeader";
import { Button } from "@/components/ui/Button";
import { IconButton } from "@/components/ui/IconButton";
import { StateBlock } from "@/components/ui/StateBlock";
import { useConnectors } from "@/hooks/useConnectors";
import { usePrincipalId, useWorkspaceId } from "@/hooks/use-workspace";
import { useAuthStore } from "@/lib/auth/store";
import { catalogHref, safeReturnTo, type ConnectorEditMode } from "@/lib/catalog/catalog-links";
import { newMutationIdempotencyKey, type ConnectorMutationRequest } from "@/lib/connectors/mutation-contract";
import { useRouter } from "@/lib/navigation";
import { queryKeys } from "@/lib/query/keys";
import { webPaths } from "@/lib/web-paths";
import type { Connector } from "@/lib/types/connector";

function ConnectorListSkeleton() {
  return (
    <div aria-label="Connectors loading">
      <div className="mb-5 flex items-center justify-between gap-4">
        <span className="block h-8 w-full max-w-[360px] rounded-[4px] bg-surface-hover" />
        <span className="block h-2 w-14 rounded-[3px] bg-surface-hover" />
      </div>
      <div className="divide-y divide-border-light border-y border-border">
        {[0, 1, 2].map((row) => (
          <div key={row} className="flex h-[76px] items-center gap-3 px-3 sm:gap-4 sm:px-4">
            <span className="h-7 w-7 flex-shrink-0 rounded-[4px] bg-surface-hover" />
            <span className="min-w-0 flex-1 space-y-2">
              <span className="block h-2 w-[38%] rounded-[3px] bg-surface-hover" />
              <span className="block h-[7px] w-[56%] rounded-[3px] bg-surface-hover" />
            </span>
          </div>
        ))}
      </div>
    </div>
  );
}

export interface ConnectorsTarget {
  connector?: string;
  // Opens the connector straight into editing, e.g. from a Catalog connection failure.
  mode?: ConnectorEditMode;
  // Where to go after that edit is saved or cancelled.
  returnTo?: string;
}

// Free plan workspaces may keep up to 10 connectors; other plans have no connector limit.
const FREE_PLAN_CONNECTOR_LIMIT = 10;

function connectorDetailHref(workspaceId: string, connectorId: string | null): string {
  const base = webPaths.connectors(workspaceId);
  return connectorId ? `${base}?connector=${encodeURIComponent(connectorId)}` : base;
}

export function catalogForConnectorHref(workspaceId: string, connectorId: string): string {
  return catalogHref(workspaceId, { connector: connectorId });
}

export default function ConnectorsPage({ target = {} }: { target?: ConnectorsTarget }) {
  const workspaceId = useWorkspaceId();
  const router = useRouter();
  const announceCreatedConnector = useConnectorCreationNotice((state) => state.announce);
  const principalId = usePrincipalId();
  const workspaceAccess = useWorkspaceAccess();
  const { organizationId, workspace } = useWorkspaceRouteContext();
  const canMutate = workspaceAccess.hasRole("OWNER") && !workspace.sample;
  const canChooseTables = workspaceAccess.hasRole("EDITOR");
  const organizationPlan = useAuthStore((state) => state.organizationMemberships.find((membership) => membership.organization.id === organizationId)?.organization.plan ?? null);
  const queryClient = useQueryClient();
  const { connectors, loading, error, retry } = useConnectors();
  const summariesQuery = useQuery({
    queryKey: queryKeys.catalog.connectorSummaries(workspaceId, principalId ?? ""),
    queryFn: ({ signal }) => catalogWorkspaceAPI.listConnectorSummaries(workspaceId, signal),
    enabled: Boolean(workspaceId && principalId),
    retry: false,
  });
  const summaries = useMemo<ConnectorSummaries>(() => summariesQuery.data
    ? { status: "loaded", byConnectorId: new Map(summariesQuery.data.map((summary) => [summary.connectorId, summary])) }
    : summariesQuery.isError ? { status: "error" } : { status: "loading" }, [summariesQuery.data, summariesQuery.isError]);
  const returnTo = safeReturnTo(workspaceId, target.returnTo);
  const [dialogMode, setDialogMode] = useState<"create" | "detail" | "revise" | "rotate" | "delete" | null>(null);
  const [deletedNotice, setDeletedNotice] = useState<string | null>(null);
  const [selectedConnector, setSelectedConnector] = useState<Connector | null>(null);
  const [mutationIdempotencyKey, setMutationIdempotencyKey] = useState("");
  const requestedConnectorId = target.connector ?? null;

  const mutation = useMutation({
    mutationFn: async (request: ConnectorMutationRequest) => {
      if (!workspaceId) {
        throw new Error("Workspace is required.");
      }
      if ("kind" in request && selectedConnector) {
        await connectorsAPI.rotateCredentials({ workspaceId, connectorId: selectedConnector.id, request, idempotencyKey: mutationIdempotencyKey });
        return;
      }
      if ("kind" in request) throw new Error("Connector is required.");
      if (dialogMode === "revise" && selectedConnector) {
        return connectorsAPI.revise({
          workspaceId,
          connectorId: selectedConnector.id,
          request,
          idempotencyKey: mutationIdempotencyKey,
        });
      }
      if (!("connectorType" in request)) throw new Error("Connector type is required.");
      return connectorsAPI.create({
        workspaceId,
        request,
        idempotencyKey: mutationIdempotencyKey,
      });
    },
    onSuccess: async (response) => {
      if ((dialogMode === "rotate" || dialogMode === "revise") && returnTo) {
        if (workspaceId && principalId) {
          await queryClient.invalidateQueries({ queryKey: queryKeys.connectors.all(workspaceId, principalId) });
        }
        setDialogMode(null);
        setSelectedConnector(null);
        router.push(returnTo);
        return;
      }
      if (dialogMode === "rotate") {
        setDialogMode("detail");
      } else if (dialogMode === "revise" && response) {
        setSelectedConnector({
          id: response.connectorId,
          workspaceId: response.workspaceId,
          name: response.displayName,
          type: response.type,
          properties: response.properties,
          createdAtEpochMs: Date.parse(response.createdAt),
          updatedAtEpochMs: Date.parse(response.updatedAt),
        });
        setDialogMode("detail");
      } else {
        setDialogMode(null);
        setSelectedConnector(null);
      }
      if (workspaceId && principalId) {
        await queryClient.invalidateQueries({
          queryKey: queryKeys.connectors.all(workspaceId, principalId),
        });
      }
      if (dialogMode === "create" && response && workspaceId) {
        announceCreatedConnector({ connectorId: response.connectorId, connectorName: response.displayName });
        router.push(catalogHref(workspaceId, { connector: response.connectorId, choose: true }));
      }
    },
  });

  const openedEditMode = useRef<string | null>(null);
  useEffect(() => {
    if (!requestedConnectorId) return;
    const requested = connectors.find((connector) => connector.id === requestedConnectorId);
    if (!requested) return;
    const editKey = target.mode && canMutate && requested.type !== "DUCKDB" && !requested.sample ? `${requested.id}:${target.mode}` : null;
    if (editKey && openedEditMode.current !== editKey) {
      openedEditMode.current = editKey;
      setSelectedConnector(requested);
      setDialogMode(target.mode ?? "detail");
      setMutationIdempotencyKey(newMutationIdempotencyKey(target.mode ?? "revise"));
      return;
    }
    setSelectedConnector((current) => current?.id === requested.id ? current : requested);
    setDialogMode((current) => current === null || current === "create" ? "detail" : current);
  }, [canMutate, connectors, requestedConnectorId, target.mode]);

  const openDialog = (mode: "create" | "revise" | "rotate", connector: Connector | null) => {
    if (!canMutate || connector?.type === "DUCKDB" || connector?.sample) return;
    setSelectedConnector(connector);
    setDialogMode(mode);
    setMutationIdempotencyKey(newMutationIdempotencyKey(mode));
    mutation.reset();
  };

  const openDetails = (connector: Connector) => {
    setSelectedConnector(connector);
    setDialogMode("detail");
    mutation.reset();
    router.replace(connectorDetailHref(workspaceId, connector.id), { scroll: false });
  };

  const closeDialog = () => {
    setDialogMode(null);
    setSelectedConnector(null);
    mutation.reset();
    if (requestedConnectorId) router.replace(connectorDetailHref(workspaceId, null), { scroll: false });
  };

  const handleDeleted = async (deleted: Connector) => {
    setDialogMode(null);
    setSelectedConnector(null);
    setDeletedNotice(`${deleted.name} was deleted.`);
    if (requestedConnectorId) router.replace(connectorDetailHref(workspaceId, null), { scroll: false });
    if (workspaceId && principalId) {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: queryKeys.connectors.all(workspaceId, principalId) }),
        queryClient.invalidateQueries({ queryKey: queryKeys.catalog.connectorSummaries(workspaceId, principalId) }),
      ]);
    }
  };

  const mutationError =
    mutation.error instanceof ApiError
      ? mutation.error.message
      : mutation.error instanceof Error
        ? mutation.error.message
        : mutation.error
          ? "Connector mutation failed."
          : null;

  return (
    <MainLayout>
      <div className="flex h-full flex-col bg-background">
        <WorkspacePageHeader
          section="Sources"
          title="Connectors"
          actions={
            canMutate ? (
              <Button
                type="button"
                onClick={() => openDialog("create", null)}
              >
                Create connector
              </Button>
            ) : undefined
          }
        />

        <div className="min-h-0 flex-1 overflow-y-auto">
          <div className="mx-auto max-w-[1080px] px-5 py-6 sm:px-8">
            {deletedNotice ? (
              <div className="mb-4 flex items-start justify-between gap-3 border-b border-border pb-3">
                <p role="status" className="type-body pt-1.5 text-foreground">{deletedNotice}</p>
                <IconButton label="Dismiss message" icon={X} onClick={() => setDeletedNotice(null)} />
              </div>
            ) : null}
            {error && connectors.length === 0 ? (
              <StateBlock
                kind="error"
                layout="page"
                title={<span role="heading" aria-level={2}>Connectors could not be loaded</span>}
                description={error}
                action={<Button type="button" variant="secondary" onClick={retry}>Retry connector list</Button>}
              />
            ) : loading && connectors.length === 0 ? (
              <ConnectorListSkeleton />
            ) : connectors.length === 0 ? (
              <StateBlock
                kind="empty"
                layout="page"
                icon={Plug}
                title={<span role="heading" aria-level={2}>No connectors yet</span>}
                description={canMutate
                  ? "Connect this workspace’s first data source to browse its tables in Catalog."
                  : "Only workspace owners can add connectors. Ask an owner to connect a data source."}
                action={canMutate ? <Button type="button" onClick={() => openDialog("create", null)}>Create connector</Button> : undefined}
              />
            ) : (
              <ConnectorList
                workspaceId={workspaceId}
                connectors={connectors}
                summaries={summaries}
                connectorLimit={organizationPlan === "FREE" ? FREE_PLAN_CONNECTOR_LIMIT : null}
                canChooseTables={canChooseTables}
                onOpen={openDetails}
              />
            )}
          </div>
        </div>
      </div>

      <ConnectorMutationDialog
        open={dialogMode !== null && dialogMode !== "delete"}
        mode={dialogMode === "delete" ? "detail" : dialogMode ?? "create"}
        connector={dialogMode !== "create" ? selectedConnector : null}
        canEdit={canMutate && selectedConnector?.type !== "DUCKDB" && !selectedConnector?.sample}
        catalogHref={selectedConnector && workspaceId ? catalogForConnectorHref(workspaceId, selectedConnector.id) : null}
        submitting={mutation.isPending}
        error={mutationError}
        onClose={() => {
          if (mutation.isPending) return;
          if ((dialogMode === "revise" || dialogMode === "rotate") && returnTo) {
            mutation.reset();
            router.push(returnTo);
            return;
          }
          closeDialog();
        }}
        onEdit={() => {
          if (!selectedConnector) return;
          openDialog("revise", selectedConnector);
        }}
        onRotate={() => { if (selectedConnector) openDialog("rotate", selectedConnector); }}
        onDelete={() => {
          if (!selectedConnector) return;
          mutation.reset();
          setDialogMode("delete");
        }}
        onCancel={() => {
          if ((dialogMode === "revise" || dialogMode === "rotate") && returnTo) {
            mutation.reset();
            router.push(returnTo);
            return;
          }
          if (dialogMode === "revise" || dialogMode === "rotate") {
            setDialogMode("detail");
            mutation.reset();
            return;
          }
          closeDialog();
        }}
        onSubmit={(request) => {
          mutation.mutate(request);
        }}
      />
      <ConnectorDeleteDialog
        open={dialogMode === "delete"}
        workspaceId={workspaceId}
        principalId={principalId ?? ""}
        connector={dialogMode === "delete" ? selectedConnector : null}
        onCancel={() => setDialogMode("detail")}
        onDeleted={(deleted) => { void handleDeleted(deleted); }}
      />
    </MainLayout>
  );
}
