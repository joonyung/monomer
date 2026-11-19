import { useNavigate, useSearch } from "@tanstack/react-router";
import { WorkspaceMembersSettings } from "@/components/settings/WorkspaceMembersSettings";

const ROUTE = "/workspaces/$workspaceId/settings/members";

export default function WorkspaceSettingsMembersPage() {
  const { member } = useSearch({ from: ROUTE });
  const navigate = useNavigate({ from: ROUTE });
  return (
    <WorkspaceMembersSettings
      selectedMemberId={member ?? null}
      // Opening pushes history so Back closes the sheet; closing replaces it so Back doesn't reopen it.
      onSelectMember={(userId) => void navigate({ search: (current) => ({ ...current, member: userId ?? undefined }), replace: userId === null })}
    />
  );
}
