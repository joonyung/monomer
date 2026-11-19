import { useEffect } from "react";
import { RefreshCw } from "lucide-react";
import { useRouter } from "@/lib/navigation";
import { useAuthStore } from "@/lib/auth/store";
import { useAccessibleWorkspacesQuery } from "@/components/auth/TenantRouteContext";
import { Button } from "@/components/ui/Button";
import { EntryFrame, EntryStatus } from "@/components/auth/EntryFrame";
import { webPaths } from "@/lib/web-paths";

export default function LandingPage() {
  const router = useRouter();
  const status = useAuthStore((state) => state.status);
  const preference = useAuthStore((state) => state.lastAccessibleWorkspaceId);
  const workspacesQuery = useAccessibleWorkspacesQuery();

  useEffect(() => {
    if (status !== "authenticated" || !workspacesQuery.data) return;
    const preferred = preference
      ? workspacesQuery.data.find((workspace) => workspace.id === preference) ?? null
      : null;
    if (preferred) {
      router.replace(webPaths.workspace(preferred.id));
      return;
    }
    if (workspacesQuery.data.length === 1) {
      router.replace(webPaths.workspace(workspacesQuery.data[0].id));
      return;
    }
    router.replace(webPaths.workspaces);
  }, [preference, router, status, workspacesQuery.data]);

  if (workspacesQuery.isError) {
    return (
      <EntryFrame>
        <EntryStatus
          kind="error"
          title="Workspaces couldn't load"
          description="Check your connection and try again."
          actions={
            <Button
              type="button"
              variant="secondary"
              pending={workspacesQuery.isFetching}
              onClick={() => void workspacesQuery.refetch()}
            >
              {!workspacesQuery.isFetching && <RefreshCw aria-hidden="true" />}
              Retry
            </Button>
          }
        />
      </EntryFrame>
    );
  }

  return (
    <EntryFrame>
      <EntryStatus kind="loading" title="Opening Monomer" description="Finding your workspaces…" />
    </EntryFrame>
  );
}
