import { History, MoreHorizontal, Trash2 } from "lucide-react";
import { IconButton } from "@/components/ui/IconButton";
import { Menu, MenuContent, MenuItem, MenuTrigger } from "@/components/ui/Menu";
import { Link } from "@/lib/navigation";

export function DefinitionActionsMenu({ historyHref, canDelete, onDelete }: { historyHref: string; canDelete: boolean; onDelete: () => void }) {
  return <Menu><MenuTrigger asChild><IconButton size="md" label="Definition actions" icon={MoreHorizontal} /></MenuTrigger>
    <MenuContent className="min-w-[180px]"><MenuItem asChild><Link href={historyHref}><History aria-hidden="true" />History</Link></MenuItem>
      {canDelete && <MenuItem icon={Trash2} onSelect={onDelete} className="text-error">Delete</MenuItem>}
    </MenuContent>
  </Menu>;
}
