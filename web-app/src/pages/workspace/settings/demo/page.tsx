// Deterministic fixture for Workspace settings General, Members (with ?member= sheets), and Public
// access as an Owner, an Organization Admin who isn't an Owner, and a locked Editor or Explorer.
// Not linked from navigation; reach via /dev/workspace-settings-demo.
import { useNavigate, useSearch } from "@tanstack/react-router";
import { notFound } from "@/lib/navigation";
import { WorkspaceSettingsDemo } from "./WorkspaceSettingsDemo";

const ROUTE = "/dev/workspace-settings-demo";

export default function WorkspaceSettingsDemoPage() {
  if (!import.meta.env.DEV) notFound();
  const { viewer, surface, member } = useSearch({ from: ROUTE });
  const navigate = useNavigate({ from: ROUTE });
  return (
    <WorkspaceSettingsDemo
      viewer={viewer}
      surface={surface}
      selectedMemberId={member ?? null}
      onSelectMember={(userId) => void navigate({ search: (current) => ({ ...current, member: userId ?? undefined }), replace: userId === null })}
    />
  );
}
