import type { ReactNode } from "react";
import { CircleAlert, KeyRound, Pencil, RotateCw } from "lucide-react";
import { useWorkspaceAccess } from "@/components/auth/WorkspaceAccessProvider";
import { failureExplanation } from "@/components/catalog/catalogCopy";
import { CONNECTOR_TYPE_META } from "@/components/connectors/connectorTypeMeta";
import { useWorkspaceOwnersQuery } from "@/components/settings/workspaceSettingsQueries";
import { Button, buttonClassName } from "@/components/ui/Button";
import { useWorkspaceId } from "@/hooks/use-workspace";
import { connectorEditHref } from "@/lib/catalog/catalog-links";
import { Link } from "@/lib/navigation";
import type { Connector } from "@/lib/types/connector";

interface ConnectionRecoveryProps {
  failureCode: string | null;
  safeMessage: string | null;
  connector: Pick<Connector, "id" | "type">;
  // What the failure left unchanged, such as "Nothing was saved to the catalog."
  consequence: string | null;
  // The exact Catalog URL an Owner returns to after fixing the connector.
  returnTo: string | null;
  onRetry: (() => void) | null;
  retrying: boolean;
  // Further destinations for this failure, such as the failed table.
  links: ReactNode;
  density: "panel" | "row";
}

// One recovery block for warehouse failures: the cause in human words, then the fix the viewer can make.
// Owners fix the connector and come back; everyone else learns who can.
export function ConnectionRecovery({ failureCode, safeMessage, connector, consequence, returnTo, onRetry, retrying, links, density }: ConnectionRecoveryProps) {
  const workspaceId = useWorkspaceId();
  const owner = useWorkspaceAccess().hasRole("OWNER");
  const explanation = failureExplanation(failureCode, safeMessage, CONNECTOR_TYPE_META[connector.type].label);
  const connectorRepairAvailable = explanation.fix !== "access";
  const ownersQuery = useWorkspaceOwnersQuery(workspaceId, connectorRepairAvailable && !owner);
  const ownerNames = ownersQuery.data?.map((candidate) => candidate.displayName).join(", ") ?? "";
  const size = density === "panel" ? "md" : "sm";
  const replaceCredentials = <Link key="credentials" href={connectorEditHref(workspaceId, connector.id, "rotate", returnTo)} className={buttonClassName(explanation.fix === "credentials" ? "primary" : "secondary", size, "")}><KeyRound aria-hidden="true" />Replace credentials</Link>;
  const editSettings = <Link key="settings" href={connectorEditHref(workspaceId, connector.id, "revise", returnTo)} className={buttonClassName(explanation.fix === "settings" ? "primary" : "secondary", size, "")}><Pencil aria-hidden="true" />Edit settings</Link>;
  const ownerActions = owner && connectorRepairAvailable ? (explanation.fix === "credentials" ? [replaceCredentials, editSettings] : [editSettings, replaceCredentials]) : [];
  const detail = [explanation.detail, consequence].filter(Boolean).join(" ");
  return (
    <div data-testid="connection-recovery" role={density === "panel" ? "alert" : undefined} className={density === "panel" ? "flex gap-3 rounded-[6px] border border-border bg-surface px-4 py-4" : "flex gap-2"}>
      <CircleAlert aria-hidden="true" className={`flex-shrink-0 text-error ${density === "panel" ? "mt-0.5 h-4 w-4" : "mt-px h-3.5 w-3.5"}`} />
      <div className="min-w-0 flex-1">
        <p className={`${density === "panel" ? "type-item" : "type-meta"} break-words text-foreground`}>{explanation.title}</p>
        {detail ? <p className={`${density === "panel" ? "type-body" : "type-status"} mt-1 break-words text-foreground-secondary`}>{detail}</p> : null}
        {connectorRepairAvailable && !owner ? <p className={`${density === "panel" ? "type-body" : "type-status"} mt-1 text-foreground-secondary`}>{ownerNames ? `Ask a workspace owner (${ownerNames}).` : "Ask a workspace owner to fix this connector."}</p> : null}
        {ownerActions.length > 0 || onRetry || links ? (
          <div className="mt-3 flex flex-wrap items-center gap-2">
            {ownerActions}
            {onRetry ? <Button type="button" variant={ownerActions.length > 0 ? "ghost" : "secondary"} size={size} onClick={onRetry} pending={retrying}><RotateCw aria-hidden="true" />{retrying ? "Trying again…" : "Try again"}</Button> : null}
            {links}
          </div>
        ) : null}
        {owner && connectorRepairAvailable && returnTo ? <p className="type-status mt-2 text-foreground-secondary">After you fix it, you come back here.</p> : null}
      </div>
    </div>
  );
}
