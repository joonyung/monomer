import { useRef, useState } from "react";
import { ChevronRight, LayoutGrid, Plus, Search, TriangleAlert } from "lucide-react";
import { Button } from "@/components/ui/Button";
import { Input } from "@/components/ui/Input";
import { StateBlock } from "@/components/ui/StateBlock";
import {
  useManagedWorkspacesQuery,
  useOrganizationRouteContext,
} from "@/components/auth/TenantRouteContext";
import type { ManagedWorkspace, OrganizationMember } from "@/lib/auth/types";
import { webPaths } from "@/lib/web-paths";
import { NewWorkspaceModal } from "./NewWorkspaceModal";
import { OrganizationSettingsShell } from "./OrganizationSettingsShell";
import { MemberAvatar } from "./SettingsPrimitives";
import { WorkspaceSheet } from "./WorkspaceSheet";
import { useOrganizationMembersQuery, useOrganizationUsageQuery } from "./organizationQueries";
import { formatSettingsDate, workspaceOwners } from "./organizationSettingsModel";

const PAGE_SIZE = 10;
const VISIBLE_OWNER_AVATARS = 3;

interface WorkspacesSettingsProps {
  selectedWorkspaceId: string | null;
  onSelectWorkspace: (workspaceId: string | null) => void;
}

export function WorkspacesSettings({ selectedWorkspaceId, onSelectWorkspace }: WorkspacesSettingsProps) {
  const { organizationId } = useOrganizationRouteContext();
  const query = useManagedWorkspacesQuery(organizationId);
  const membersQuery = useOrganizationMembersQuery(organizationId);
  const usage = useOrganizationUsageQuery(organizationId).data;
  const [createOpen, setCreateOpen] = useState(false);
  const [page, setPage] = useState(1);
  const [search, setSearch] = useState("");
  const workspaces = query.data ?? [];
  const members = membersQuery.data ?? [];
  const filtered = workspaces.filter((workspace) =>
    workspace.name.toLowerCase().includes(search.trim().toLowerCase()),
  );
  const pageCount = Math.max(1, Math.ceil(filtered.length / PAGE_SIZE));
  const currentPage = Math.min(page, pageCount);
  const selected = workspaces.find((workspace) => workspace.id === selectedWorkspaceId) ?? null;
  return (
    <OrganizationSettingsShell
      section="workspaces"
      title="Workspaces"
      actions={
        <>
          {usage?.limits ? (
            <span className="type-meta tabular-nums text-foreground-secondary" data-testid="workspace-usage">
              {usage.usage.workspaces} of {usage.limits.workspaces}
            </span>
          ) : null}
          <Button onClick={() => setCreateOpen(true)} data-testid="new-workspace-button">
            <Plus aria-hidden="true" />
            New Workspace
          </Button>
        </>
      }
    >
      {workspaces.length > PAGE_SIZE || search ? (
        <div className="relative mb-4 w-full sm:w-72">
          <Search className="pointer-events-none absolute left-2.5 top-1/2 h-4 w-4 -translate-y-1/2 text-foreground-secondary" aria-hidden="true" />
          <Input
            type="search"
            aria-label="Search workspaces"
            placeholder="Search workspaces"
            value={search}
            onChange={(event) => {
              setSearch(event.target.value);
              setPage(1);
            }}
            className="pl-8"
          />
        </div>
      ) : null}
      {query.isPending ? (
        <StateBlock kind="loading" layout="inline" title="Loading workspaces…" />
      ) : query.isError ? (
        <div className="border-y border-border">
          <StateBlock
            kind="error"
            layout="inline"
            title="Couldn't load workspaces."
            action={<Button variant="secondary" aria-label="Retry loading workspaces" onClick={() => void query.refetch()}>Retry</Button>}
          />
        </div>
      ) : filtered.length === 0 ? (
        <div className="border-y border-border">
          <StateBlock
            kind="empty"
            layout="inline"
            icon={LayoutGrid}
            title={workspaces.length ? "No matching workspaces" : "No workspaces yet"}
            action={search ? (
              <Button variant="ghost" size="sm" onClick={() => { setSearch(""); setPage(1); }}>Clear search</Button>
            ) : null}
          />
        </div>
      ) : (
        <>
          <table className="w-full table-fixed text-left" aria-label="Workspaces">
            <colgroup className="hidden md:table-column-group">
              <col />
              <col className="w-[24%]" />
              <col className="w-[12%]" />
              <col className="w-[16%]" />
              <col className="w-10" />
            </colgroup>
            <thead className="hidden border-b border-border md:table-header-group">
              <tr>
                <th scope="col" className="py-2.5 pl-2 pr-3 type-group text-label">Workspace</th>
                <th scope="col" className="px-3 py-2.5 type-group text-label">Owners</th>
                <th scope="col" className="px-3 py-2.5 text-right type-group text-label">Members</th>
                <th scope="col" className="px-3 py-2.5 type-group text-label">Created</th>
                <th scope="col" className="py-2.5"><span className="sr-only">Open</span></th>
              </tr>
            </thead>
            <tbody className="divide-y divide-border-light border-y border-border md:border-t-0">
              {filtered.slice((currentPage - 1) * PAGE_SIZE, currentPage * PAGE_SIZE).map((workspace) => (
                <WorkspaceRow
                  key={workspace.id}
                  workspace={workspace}
                  owners={membersQuery.isSuccess ? workspaceOwners(workspace.id, members) : null}
                  selected={workspace.id === selectedWorkspaceId}
                  onOpen={() => onSelectWorkspace(workspace.id)}
                />
              ))}
            </tbody>
          </table>
          {pageCount > 1 && (
            <nav aria-label="Workspace pages" className="flex flex-wrap items-center justify-between gap-3 py-3 type-meta text-foreground-secondary">
              <span className="tabular-nums">
                {(currentPage - 1) * PAGE_SIZE + 1}–{Math.min(currentPage * PAGE_SIZE, filtered.length)} of {filtered.length}
              </span>
              <div className="flex items-center gap-3">
                <Button variant="ghost" size="sm" disabled={currentPage === 1} onClick={() => setPage(currentPage - 1)} aria-label="Previous workspace page">
                  Previous
                </Button>
                <span className="tabular-nums">{currentPage} / {pageCount}</span>
                <Button variant="ghost" size="sm" disabled={currentPage === pageCount} onClick={() => setPage(currentPage + 1)} aria-label="Next workspace page">
                  Next
                </Button>
              </div>
            </nav>
          )}
        </>
      )}
      {createOpen && (
        <NewWorkspaceModal
          organizationId={organizationId}
          onClose={() => setCreateOpen(false)}
          onCreated={async () => {
            setPage(1);
            await query.refetch();
          }}
        />
      )}
      {selected && (
        <WorkspaceSheet
          key={selected.id}
          workspace={selected}
          members={members}
          memberHref={(userId) => webPaths.organizationMemberSheet(organizationId, userId)}
          onClose={() => onSelectWorkspace(null)}
        />
      )}
    </OrganizationSettingsShell>
  );
}

function WorkspaceRow({
  workspace,
  owners,
  selected,
  onOpen,
}: {
  workspace: ManagedWorkspace;
  owners: OrganizationMember[] | null;
  selected: boolean;
  onOpen: () => void;
}) {
  // A pointer click anywhere on the row opens the sheet from the row's named button, so focus
  // returns to that button when the sheet closes.
  const openButton = useRef<HTMLButtonElement>(null);
  return (
    <tr onClick={() => { openButton.current?.focus(); onOpen(); }} className={`cursor-pointer transition-colors ${selected ? "bg-surface" : "hover:bg-surface/60"}`}>
      <td className="relative py-3 pl-2 pr-3">
        {selected ? <span aria-hidden="true" className="absolute inset-y-2 left-0 w-[2px] rounded-r-sm bg-accent-marker" /> : null}
        <div className="flex min-w-0 items-center gap-3">
          <div className="min-w-0">
            <button
              ref={openButton}
              type="button"
              aria-label={`Manage ${workspace.name}`}
              onClick={(event) => { event.stopPropagation(); onOpen(); }}
              className="block max-w-full break-words text-left type-body text-foreground underline-offset-4 focus-visible:underline"
            >
              {workspace.name}
            </button>
            <p className="type-meta tabular-nums text-foreground-secondary md:hidden">
              {owners !== null && owners.length === 0 ? "No owner · " : ""}
              {workspace.memberCount} {workspace.memberCount === 1 ? "member" : "members"} · {formatSettingsDate(workspace.createdAt)}
            </p>
          </div>
        </div>
      </td>
      <td className="hidden px-3 py-3 md:table-cell">
        <OwnerSummary owners={owners} />
      </td>
      <td className="hidden px-3 py-3 text-right type-body tabular-nums text-foreground md:table-cell">{workspace.memberCount}</td>
      <td className="hidden px-3 py-3 type-body tabular-nums text-foreground md:table-cell">
        <time dateTime={workspace.createdAt}>{formatSettingsDate(workspace.createdAt)}</time>
      </td>
      <td className="w-10 py-3 pr-2 text-right">
        <ChevronRight className="ml-auto h-4 w-4 text-foreground-secondary" aria-hidden="true" />
      </td>
    </tr>
  );
}

function OwnerSummary({ owners }: { owners: OrganizationMember[] | null }) {
  if (owners === null) return <span className="type-meta text-foreground-secondary">—</span>;
  if (owners.length === 0) {
    return (
      <span className="flex items-center gap-1.5 type-body text-foreground">
        <TriangleAlert className="h-4 w-4 flex-shrink-0 text-warning" aria-hidden="true" />
        No owner
      </span>
    );
  }
  const names = owners.map((owner) => owner.user.displayName).join(", ");
  const hidden = owners.length - VISIBLE_OWNER_AVATARS;
  return (
    <span className="flex items-center" title={names}>
      <span className="sr-only">{names}</span>
      <span aria-hidden="true" className="flex -space-x-1.5">
        {owners.slice(0, VISIBLE_OWNER_AVATARS).map((owner) => (
          <span key={owner.user.id} className="rounded-full ring-2 ring-background">
            <MemberAvatar name={owner.user.displayName} />
          </span>
        ))}
      </span>
      {hidden > 0 ? <span aria-hidden="true" className="ml-2 type-meta tabular-nums text-foreground-secondary">+{hidden}</span> : null}
    </span>
  );
}
