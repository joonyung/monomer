import { useRef, useState } from "react";
import { ChevronRight, Plus, Search, Users } from "lucide-react";
import { Button } from "@/components/ui/Button";
import { Input } from "@/components/ui/Input";
import { StateBlock } from "@/components/ui/StateBlock";
import { roleLabel } from "@/lib/auth/roles";
import { useAuthStore } from "@/lib/auth/store";
import { MemberAvatar, SegmentedControl } from "./SettingsPrimitives";
import { AddWorkspaceMemberSheet, WorkspaceMemberSheet } from "./WorkspaceMemberSheets";
import { WorkspaceSettingsShell } from "./WorkspaceSettingsShell";
import {
  useInvalidateWorkspaceSettings,
  useWorkspaceMembersSource,
  useWorkspaceSettingsContext,
} from "./workspaceSettingsQueries";
import {
  WORKSPACE_MEMBER_FILTERS,
  filterWorkspaceMembers,
  workspaceMemberFilterCounts,
  type WorkspaceMemberFilter,
  type WorkspaceSettingsMember,
} from "./workspaceSettingsModel";

interface WorkspaceMembersSettingsProps {
  selectedMemberId: string | null;
  onSelectMember: (userId: string | null) => void;
}

export function WorkspaceMembersSettings(props: WorkspaceMembersSettingsProps) {
  const [addOpen, setAddOpen] = useState(false);
  return (
    <WorkspaceSettingsShell
      section="members"
      title="Members"
      actions={
        <Button onClick={() => setAddOpen(true)}>
          <Plus aria-hidden="true" />
          Add member
        </Button>
      }
    >
      <WorkspaceMembersInventory {...props} addOpen={addOpen} onCloseAdd={() => setAddOpen(false)} />
    </WorkspaceSettingsShell>
  );
}

function WorkspaceMembersInventory({
  selectedMemberId,
  onSelectMember,
  addOpen,
  onCloseAdd,
}: WorkspaceMembersSettingsProps & { addOpen: boolean; onCloseAdd: () => void }) {
  const context = useWorkspaceSettingsContext();
  const principalId = useAuthStore((state) => state.user?.id ?? "");
  const source = useWorkspaceMembersSource(context);
  const invalidate = useInvalidateWorkspaceSettings(context.workspaceId);
  const [search, setSearch] = useState("");
  const [filter, setFilter] = useState<WorkspaceMemberFilter>("all");
  const members = source.members ?? [];
  const counts = workspaceMemberFilterCounts(members);
  const visibleMembers = filterWorkspaceMembers(members, search, filter);
  const selected = members.find((member) => member.user.id === selectedMemberId) ?? null;
  return (
    <>
      <div className="mb-4 flex flex-wrap items-center gap-3">
        <div className="relative w-full sm:w-72">
          <Search className="pointer-events-none absolute left-2.5 top-1/2 h-4 w-4 -translate-y-1/2 text-foreground-secondary" aria-hidden="true" />
          <Input
            type="search"
            aria-label="Search Workspace members"
            placeholder="Search name or email"
            value={search}
            onChange={(event) => setSearch(event.target.value)}
            className="pl-8"
          />
        </div>
        <SegmentedControl
          label="Filter members"
          value={filter}
          onChange={setFilter}
          options={WORKSPACE_MEMBER_FILTERS.map((option) => ({ ...option, count: counts[option.value] }))}
        />
      </div>
      {source.isError ? (
        <StateBlock
          kind="error"
          layout="inline"
          title="Couldn't load members."
          action={<Button variant="secondary" aria-label="Retry loading members" onClick={source.refetch}>Retry</Button>}
        />
      ) : source.isPending ? (
        <StateBlock kind="loading" layout="inline" title="Loading members…" />
      ) : visibleMembers.length === 0 ? (
        <div className="border-y border-border">
          <StateBlock
            kind="empty"
            layout="inline"
            icon={Users}
            title={members.length === 0 ? "No members." : "No matching members."}
            action={search || filter !== "all" ? (
              <Button variant="ghost" size="sm" onClick={() => { setSearch(""); setFilter("all"); }}>Clear filters</Button>
            ) : null}
          />
        </div>
      ) : (
        <table className="w-full table-fixed text-left" aria-label="Workspace members">
          <colgroup className="hidden md:table-column-group">
            <col />
            <col className="w-[20%]" />
            <col className="w-10" />
          </colgroup>
          <thead className="hidden border-b border-border md:table-header-group">
            <tr>
              <th scope="col" className="py-2.5 pl-2 pr-3 type-group text-label">Member</th>
              <th scope="col" className="px-3 py-2.5 type-group text-label">Role</th>
              <th scope="col" className="py-2.5"><span className="sr-only">Open</span></th>
            </tr>
          </thead>
          <tbody className="divide-y divide-border-light border-y border-border md:border-t-0">
            {visibleMembers.map((member) => (
              <WorkspaceMemberRow
                key={member.user.id}
                member={member}
                isSelf={member.user.id === principalId}
                selected={member.user.id === selectedMemberId}
                onOpen={() => onSelectMember(member.user.id)}
              />
            ))}
          </tbody>
        </table>
      )}
      {selected && (
        <WorkspaceMemberSheet
          key={selected.user.id}
          context={context}
          member={selected}
          members={members}
          isSelf={selected.user.id === principalId}
          onClose={() => onSelectMember(null)}
          onChanged={invalidate}
        />
      )}
      {addOpen && (
        <AddWorkspaceMemberSheet
          context={context}
          candidates={source.candidates}
          candidatesError={source.candidatesError}
          onRetryCandidates={source.refetch}
          onClose={onCloseAdd}
          onChanged={invalidate}
        />
      )}
    </>
  );
}

function WorkspaceMemberRow({
  member,
  isSelf,
  selected,
  onOpen,
}: {
  member: WorkspaceSettingsMember;
  isSelf: boolean;
  selected: boolean;
  onOpen: () => void;
}) {
  const role = roleLabel(member.role);
  const openButton = useRef<HTMLButtonElement>(null);
  return (
    <tr
      // A pointer click anywhere on the row opens the sheet from the row's named button, so focus
      // returns to that button when the sheet closes.
      onClick={() => { openButton.current?.focus(); onOpen(); }}
      className={`cursor-pointer text-foreground transition-colors ${selected ? "bg-surface" : "hover:bg-surface/60"}`}
    >
      <td className="relative py-3 pl-2 pr-3">
        {selected ? <span aria-hidden="true" className="absolute inset-y-2 left-0 w-[2px] rounded-r-sm bg-accent-marker" /> : null}
        <div className="flex min-w-0 items-center gap-3">
          <MemberAvatar name={member.user.displayName} />
          <div className="min-w-0">
            <button
              ref={openButton}
              type="button"
              aria-label={`Manage ${member.user.displayName}`}
              onClick={(event) => { event.stopPropagation(); onOpen(); }}
              className="block max-w-full truncate text-left type-body underline-offset-4 focus-visible:underline"
            >
              {member.user.displayName}
              {isSelf ? <span className="text-foreground-secondary"> · You</span> : null}
            </button>
            <p className="truncate type-meta text-foreground-secondary" title={member.user.email}>
              <span className="md:hidden">{role}{member.user.email ? " · " : ""}</span>
              {member.user.email}
            </p>
          </div>
        </div>
      </td>
      <td className="hidden px-3 py-3 type-body md:table-cell">{role}</td>
      <td className="w-10 py-3 pr-2 text-right">
        <ChevronRight className="ml-auto h-4 w-4 text-foreground-secondary" aria-hidden="true" />
      </td>
    </tr>
  );
}
