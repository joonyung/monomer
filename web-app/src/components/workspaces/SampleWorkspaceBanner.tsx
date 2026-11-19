import { useAccessibleWorkspacesQuery, useOptionalWorkspaceRouteContext } from "@/components/auth/TenantRouteContext";
import { warehouseWorkspace } from "@/lib/auth/sample-workspace";
import { Link } from "@/lib/navigation";
import { webPaths } from "@/lib/web-paths";

export function SampleWorkspaceBanner() {
  const context = useOptionalWorkspaceRouteContext();
  const workspaces = useAccessibleWorkspacesQuery();
  if (!context?.workspace.sample) return null;
  const target = warehouseWorkspace(workspaces.data ?? [], { id: context.workspaceId, organizationId: context.organizationId });
  return <aside aria-label="Sample data" className="flex shrink-0 flex-wrap items-center justify-between gap-x-4 gap-y-1 border-b border-border bg-surface px-5 py-2 type-meta text-foreground-secondary sm:px-8">
    <p>Sample data from Acme Cloud, a fictional company. The data is read-only; you can build dashboards and edit metrics.</p>
    {target ? <Link href={webPaths.connectors(target.id)} className="text-foreground underline underline-offset-4">Connect your warehouse</Link> : null}
  </aside>;
}
