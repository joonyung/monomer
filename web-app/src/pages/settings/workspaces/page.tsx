import { useNavigate, useSearch } from "@tanstack/react-router";
import { WorkspacesSettings } from "@/components/settings/WorkspacesSettings";

const ROUTE = "/organizations/$organizationId/settings/workspaces";

export default function SettingsWorkspacesPage() {
  const { workspace } = useSearch({ from: ROUTE });
  const navigate = useNavigate({ from: ROUTE });
  return (
    <WorkspacesSettings
      selectedWorkspaceId={workspace ?? null}
      // Opening pushes history so Back closes the sheet; closing replaces it so Back doesn't reopen it.
      onSelectWorkspace={(workspaceId) => void navigate({ search: (current) => ({ ...current, workspace: workspaceId ?? undefined }), replace: workspaceId === null })}
    />
  );
}
