import { useRef, useState } from "react";
import { ChevronRight, Plus, Search, Users } from "lucide-react";
import { Button } from "@/components/ui/Button";
import { Input } from "@/components/ui/Input";
import { StateBlock } from "@/components/ui/StateBlock";
import { useAuthStore } from "@/lib/auth/store";
import {
  useManagedWorkspacesQuery,
  useOrganizationRouteContext,
} from "@/components/auth/TenantRouteContext";
import { invitationDeliveryMessage, type InvitationDeliveryResult } from "@/lib/auth/invitations";
import type { OrganizationMember } from "@/lib/auth/types";
import { webPaths } from "@/lib/web-paths";
import { InviteMemberModal } from "./InviteMemberModal";
import { MemberSheet } from "./MemberSheet";
import { PendingInvitations } from "./PendingInvitations";
import { OrganizationSettingsShell } from "./OrganizationSettingsShell";
import { MemberAvatar, SegmentedControl } from "./SettingsPrimitives";
import {
  useInvalidateOrganizationSettings,
  useOrganizationMembersQuery,
} from "./organizationQueries";
import {
  filterMembers,
  formatSettingsDate,
  memberFilterCounts,
  workspaceAccessSummary,
  type MemberFilter,
} from "./organizationSettingsModel";

const VISIBLE_WORKSPACE_NAMES = 3;

interface MembersSettingsProps {
  selectedMemberId: string | null;
  onSelectMember: (userId: string | null) => void;
}

export function MembersSettings({ selectedMemberId, onSelectMember }: MembersSettingsProps) {
  const { organizationId, organization } = useOrganizationRouteContext();
  const principalId = useAuthStore((state) => state.user?.id ?? "");
  const membersQuery = useOrganizationMembersQuery(organizationId);
  const workspacesQuery = useManagedWorkspacesQuery(organizationId);
  const invalidate = useInvalidateOrganizationSettings();
  const [inviteOpen, setInviteOpen] = useState(false);
  const [search, setSearch] = useState("");
  const [filter, setFilter] = useState<MemberFilter>("all");
  const [message, setMessage] = useState<string | null>(null);
  const members = membersQuery.data ?? [];
  const counts = memberFilterCounts(members);
  const visibleMembers = filterMembers(members, search, filter);
  const selected = members.find((member) => member.user.id === selectedMemberId) ?? null;
  const invitationChanged = async (result: InvitationDeliveryResult) => {
    setMessage(invitationDeliveryMessage(result));
    await invalidate();
  };
  return (
    <OrganizationSettingsShell
      section="members"
      title="Members"
      actions={
        <Button onClick={() => setInviteOpen(true)}>
          <Plus aria-hidden="true" />
          Invite people
        </Button>
      }
    >
      {message && <p role="status" className="mb-4 type-body text-foreground-secondary">{message}</p>}
      <PendingInvitations organizationId={organizationId} onResult={invitationChanged} />
      <div className="mb-4 flex flex-wrap items-center gap-3">
        <div className="relative w-full sm:w-72">
          <Search className="pointer-events-none absolute left-2.5 top-1/2 h-4 w-4 -translate-y-1/2 text-foreground-secondary" aria-hidden="true" />
          <Input
            type="search"
            aria-label="Search Organization members"
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
          options={[
            { value: "all", label: "All", count: counts.all },
            { value: "admins", label: "Admins", count: counts.admins },
          ]}
        />
      </div>
      {membersQuery.isPending ? (
        <StateBlock kind="loading" layout="inline" title="Loading members…" />
      ) : membersQuery.isError ? (
        <StateBlock
          kind="error"
          layout="inline"
          title="Couldn't load members."
          action={<Button variant="secondary" aria-label="Retry loading members" onClick={() => void membersQuery.refetch()}>Retry</Button>}
        />
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
        <table className="w-full table-fixed text-left" aria-label="Organization members">
          <colgroup className="hidden md:table-column-group">
            <col />
            <col className="w-[14%]" />
            <col className="w-[30%]" />
            <col className="w-[15%]" />
            <col className="w-10" />
          </colgroup>
          <thead className="hidden border-b border-border md:table-header-group">
            <tr>
              <th scope="col" className="py-2.5 pl-2 pr-3 type-group text-label">Member</th>
              <th scope="col" className="px-3 py-2.5 type-group text-label">Role</th>
              <th scope="col" className="px-3 py-2.5 type-group text-label">Workspace access</th>
              <th scope="col" className="px-3 py-2.5 type-group text-label">Joined</th>
              <th scope="col" className="py-2.5"><span className="sr-only">Open</span></th>
            </tr>
          </thead>
          <tbody className="divide-y divide-border-light border-y border-border md:border-t-0">
            {visibleMembers.map((member) => (
              <MemberRow
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
      {inviteOpen && (
        <InviteMemberModal
          organizationId={organizationId}
          onClose={() => setInviteOpen(false)}
          onSent={invitationChanged}
        />
      )}
      {selected && (
        <MemberSheet
          key={selected.user.id}
          organizationId={organizationId}
          organizationName={organization.displayName}
          member={selected}
          members={members}
          workspaces={workspacesQuery.isSuccess ? workspacesQuery.data : null}
          isSelf={selected.user.id === principalId}
          workspaceHref={(workspaceId) => webPaths.organizationWorkspaceSheet(organizationId, workspaceId)}
          onClose={() => onSelectMember(null)}
          onChanged={invalidate}
        />
      )}
    </OrganizationSettingsShell>
  );
}

function MemberRow({
  member,
  isSelf,
  selected,
  onOpen,
}: {
  member: OrganizationMember;
  isSelf: boolean;
  selected: boolean;
  onOpen: () => void;
}) {
  const role = member.role === "ADMIN" ? "Admin" : "Member";
  const access = workspaceAccessSummary(member.workspaces, VISIBLE_WORKSPACE_NAMES);
  const accessText = access ? access.names : "No workspace yet";
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
            <p className="truncate type-meta text-foreground-secondary">
              <span className="hidden md:inline">{member.user.email}</span>
              <span className="md:hidden">
                {role} · {access ? `${access.names}${access.overflow ? ` +${access.overflow}` : ""}` : "No workspace yet"}
              </span>
            </p>
          </div>
        </div>
      </td>
      <td className="hidden px-3 py-3 type-body md:table-cell">{role}</td>
      <td className="hidden px-3 py-3 md:table-cell">
        <p className="truncate type-body">
          <span className={!access ? "text-foreground-secondary" : undefined}>{accessText}</span>
          {access?.overflow ? (
            <span className="type-meta tabular-nums text-foreground-secondary"> +{access.overflow}</span>
          ) : null}
        </p>
      </td>
      <td className="hidden px-3 py-3 type-body tabular-nums md:table-cell">
        <time dateTime={member.joinedAt}>{formatSettingsDate(member.joinedAt)}</time>
      </td>
      <td className="w-10 py-3 pr-2 text-right">
        <ChevronRight className="ml-auto h-4 w-4 text-foreground-secondary" aria-hidden="true" />
      </td>
    </tr>
  );
}
