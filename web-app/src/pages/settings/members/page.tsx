import { useNavigate, useSearch } from "@tanstack/react-router";
import { MembersSettings } from "@/components/settings/MembersSettings";

const ROUTE = "/organizations/$organizationId/settings/members";

export default function SettingsMembersPage() {
  const { member } = useSearch({ from: ROUTE });
  const navigate = useNavigate({ from: ROUTE });
  return (
    <MembersSettings
      selectedMemberId={member ?? null}
      // Opening pushes history so Back closes the sheet; closing replaces it so Back doesn't reopen it.
      onSelectMember={(userId) => void navigate({ search: (current) => ({ ...current, member: userId ?? undefined }), replace: userId === null })}
    />
  );
}
