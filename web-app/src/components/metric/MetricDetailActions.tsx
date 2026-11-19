import { Compass, Edit } from "lucide-react";
import { useWorkspaceAccess } from "@/components/auth/WorkspaceAccessProvider";
import { Button, buttonClassName } from "@/components/ui/Button";
import { Link } from "@/lib/navigation";

export function MetricDetailActions({
  exploreHref,
  onEdit,
}: {
  exploreHref: string;
  onEdit: () => void;
}) {
  const workspaceAccess = useWorkspaceAccess();
  const canExplore = workspaceAccess.hasRole("EXPLORER");
  const canEditMetric = workspaceAccess.hasRole("EDITOR");

  return (
    <div className="flex flex-wrap items-center justify-end gap-2">
      {canEditMetric && (
        <Button variant="secondary" size="sm" onClick={onEdit}>
          <Edit aria-hidden="true" />
          Edit
        </Button>
      )}
      {canExplore && (
        <Link href={exploreHref} className={buttonClassName("primary", "sm", "")}>
          <Compass aria-hidden="true" />
          Explore
        </Link>
      )}
    </div>
  );
}
