import { MainLayout } from "@/components/layout/MainLayout";
import { WorkspacePageHeader } from "@/components/layout/WorkspacePageHeader";
import { usePrincipalId, useWorkspaceId } from "@/hooks/use-workspace";
import { WorkspaceActivity } from "./WorkspaceActivity";

export default function ActivityPage() {
  const workspaceId = useWorkspaceId();
  const principalId = usePrincipalId();
  return <MainLayout>
    <div className="flex h-full flex-col bg-background">
      <WorkspacePageHeader section="Workspace" title="Activity" />
      <div className="min-h-0 flex-1 overflow-y-auto">
        {principalId && <WorkspaceActivity key={`${workspaceId}:${principalId}`} workspaceId={workspaceId} principalId={principalId} />}
      </div>
    </div>
  </MainLayout>;
}
