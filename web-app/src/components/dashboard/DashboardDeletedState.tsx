import { StateBlock } from "@/components/ui/StateBlock";
import { buttonClassName } from "@/components/ui/Button";
import { Link } from "@/lib/navigation";
import { webPaths } from "@/lib/web-paths";

export function DashboardDeletedState({ workspaceId, dashboardId, canReadHistory }: { workspaceId: string; dashboardId: string; canReadHistory: boolean }) {
  return <StateBlock kind="empty" layout="page" title="This dashboard was deleted." action={<div className="flex flex-wrap gap-2"><Link href={webPaths.dashboards(workspaceId)} className={buttonClassName("secondary", "md", "")}>Back to Dashboards</Link>{canReadHistory && <Link href={webPaths.dashboardHistory(workspaceId, dashboardId)} className={buttonClassName("primary", "md", "")}>View history</Link>}</div>} />;
}
