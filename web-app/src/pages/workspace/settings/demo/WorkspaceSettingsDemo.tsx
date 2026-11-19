import { useEffect, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { fetchWorkspaceContext } from "@/api/workspace-context";
import { WorkspaceRouteFixtureProvider, useAccessibleWorkspacesQuery } from "@/components/auth/TenantRouteContext";
import { WorkspaceGeneralSettings } from "@/components/settings/WorkspaceGeneralSettings";
import { WorkspaceMembersSettings } from "@/components/settings/WorkspaceMembersSettings";
import { WorkspacePublicAccessSettings } from "@/components/settings/WorkspacePublicAccessSettings";
import type { WorkspaceSettingsSection } from "@/components/settings/workspaceSettingsModel";
import { StateBlock } from "@/components/ui/StateBlock";
import { useAuthStore } from "@/lib/auth/store";
import { clearQueryCache } from "@/lib/query/client";
import { queryKeys } from "@/lib/query/keys";
import {
  FIXTURE_VIEWER,
  FIXTURE_WORKSPACE_ID,
  fixtureMembership,
  installWorkspaceFixtureFetch,
  type WorkspaceFixtureViewer,
} from "./workspaceFixture";

interface WorkspaceSettingsDemoProps {
  viewer: WorkspaceFixtureViewer;
  surface: WorkspaceSettingsSection;
  selectedMemberId: string | null;
  onSelectMember: (userId: string | null) => void;
}

// Renders the production Workspace settings against the in-memory backend. The workspace and the
// viewer's access come from the same queries the Workspace route gate uses, so renames, public
// access, and the viewer's own role changes flow back into the settings as they do in the product.
export function WorkspaceSettingsDemo(props: WorkspaceSettingsDemoProps) {
  const [seededViewer, setSeededViewer] = useState<WorkspaceFixtureViewer | null>(null);
  useEffect(() => {
    clearQueryCache();
    const restoreFetch = installWorkspaceFixtureFetch(props.viewer);
    useAuthStore.setState({
      status: "authenticated",
      user: FIXTURE_VIEWER,
      organizationMemberships: [fixtureMembership(props.viewer)],
      lastAccessibleWorkspaceId: FIXTURE_WORKSPACE_ID,
      accessToken: "fixture-token",
      accessTokenExpiresAt: new Date(Date.now() + 3_600_000).toISOString(),
      error: null,
    });
    setSeededViewer(props.viewer);
    return restoreFetch;
  }, [props.viewer]);
  if (seededViewer !== props.viewer) return null;
  return <WorkspaceSettingsDemoGate {...props} />;
}

function WorkspaceSettingsDemoGate({ surface, selectedMemberId, onSelectMember }: WorkspaceSettingsDemoProps) {
  const workspaces = useAccessibleWorkspacesQuery();
  const access = useQuery({
    queryKey: queryKeys.workspaceContext.detail(FIXTURE_WORKSPACE_ID, FIXTURE_VIEWER.id),
    queryFn: ({ signal }) => fetchWorkspaceContext(FIXTURE_WORKSPACE_ID, signal),
  });
  const workspace = workspaces.data?.find((candidate) => candidate.id === FIXTURE_WORKSPACE_ID);
  if (workspaces.isPending || access.isPending) return null;
  if (!workspace || !access.data) {
    return <StateBlock kind="empty" layout="page" headingLevel={1} title="You don't have access to this workspace" />;
  }
  return (
    <WorkspaceRouteFixtureProvider workspace={workspace} access={access.data}>
      {surface === "members" ? (
        <WorkspaceMembersSettings selectedMemberId={selectedMemberId} onSelectMember={onSelectMember} />
      ) : surface === "public-access" ? (
        <WorkspacePublicAccessSettings />
      ) : (
        <WorkspaceGeneralSettings />
      )}
    </WorkspaceRouteFixtureProvider>
  );
}
