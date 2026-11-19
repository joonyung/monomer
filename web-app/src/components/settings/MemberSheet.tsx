import { useState } from "react";
import { Plus } from "lucide-react";
import { Button } from "@/components/ui/Button";
import { NativeSelect } from "@/components/ui/Input";
import { Sheet } from "@/components/ui/Sheet";
import { Link } from "@/lib/navigation";
import {
  removeOrganizationMember,
  removeWorkspaceMember,
  setWorkspaceMemberRole,
  updateOrganizationMemberRole,
} from "@/lib/auth/graphql";
import { WORKSPACE_ROLES, roleLabel } from "@/lib/auth/roles";
import type { ManagedWorkspace, OrganizationMember, OrganizationMemberWorkspace, WorkspaceRole } from "@/lib/auth/types";
import { WorkspaceRolePicker } from "./WorkspaceRolePicker";
import { MemberAvatar, SegmentedControl, SheetSectionHeading } from "./SettingsPrimitives";
import {
  firstName,
  formatSettingsDate,
  memberAccessConsequences,
  memberRemovalBlock,
  organizationRoleBlock,
} from "./organizationSettingsModel";
import { lastOwnerMessage, workspaceAccessRemovalBlock } from "./workspaceSettingsModel";

type SheetView =
  | { kind: "detail" }
  | { kind: "remove-member" }
  | { kind: "remove-workspace-access"; workspace: OrganizationMemberWorkspace };

// Names the control that takes focus when the sheet returns to its detail view.
type FocusTarget = "remove-member" | "add-workspace" | { removeWorkspaceAccess: string };

interface MemberSheetProps {
  organizationName: string;
  organizationId: string;
  member: OrganizationMember;
  members: OrganizationMember[];
  // Null until the organization's workspaces have loaded, so "already in every workspace" is never guessed.
  workspaces: ManagedWorkspace[] | null;
  isSelf: boolean;
  workspaceHref: (workspaceId: string) => string;
  onClose: () => void;
  onChanged: () => Promise<unknown>;
}

export function MemberSheet({
  organizationName,
  organizationId,
  member,
  members,
  workspaces,
  isSelf,
  workspaceHref,
  onClose,
  onChanged,
}: MemberSheetProps) {
  const [view, setView] = useState<SheetView>({ kind: "detail" });
  const [returnFocusTo, setReturnFocusTo] = useState<FocusTarget | null>(null);
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const name = member.user.displayName;
  const removalBlock = memberRemovalBlock(member, members, isSelf);
  const roleBlock = organizationRoleBlock(member, members, isSelf);

  const run = async (key: string, action: () => Promise<unknown>): Promise<boolean> => {
    setBusy(key);
    setError(null);
    try {
      await action();
      await onChanged();
      return true;
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "Couldn't update this member. Try again.");
      return false;
    } finally {
      setBusy(null);
    }
  };

  const backToDetail = (focusTarget: FocusTarget) => {
    setError(null);
    setReturnFocusTo(focusTarget);
    setView({ kind: "detail" });
  };

  if (view.kind === "remove-workspace-access") {
    const workspace = view.workspace;
    const confirm = async () => {
      // The removed row disappears, so focus moves to the next likely action in Workspace access.
      if (await run("remove-workspace-access", () => removeWorkspaceMember(workspace.id, member.user.id))) backToDetail("add-workspace");
    };
    return (
      <Sheet
        open
        onOpenChange={(open) => { if (!open) onClose(); }}
        title={`Remove ${name} from ${workspace.name}?`}
        leading={<MemberAvatar name={name} size="lg" />}
        role="alertdialog"
        busy={busy !== null}
        closeLabel="Close member"
        testId="member-sheet"
        footer={
          <>
            <Button
              key="keep-workspace-access"
              autoFocus
              variant="secondary"
              disabled={busy !== null}
              onClick={() => backToDetail({ removeWorkspaceAccess: workspace.id })}
            >
              Keep
            </Button>
            <Button key="confirm-workspace-access-removal" variant="danger" pending={busy !== null} onClick={() => void confirm()}>
              Remove from {workspace.name}
            </Button>
          </>
        }
      >
        <ul className="list-disc space-y-2 pl-5 type-body text-foreground">
          <li>{`Loses access to ${workspace.name} and its resources`}</li>
          <li>{`Stays a member of ${organizationName}`}</li>
        </ul>
        {error && <p role="alert" className="mt-4 type-body text-error">{error}</p>}
      </Sheet>
    );
  }

  if (view.kind === "remove-member") {
    const confirm = async () => {
      if (await run("remove-member", () => removeOrganizationMember(organizationId, member.user.id))) onClose();
    };
    return (
      <Sheet
        open
        onOpenChange={(open) => { if (!open) onClose(); }}
        title={`Remove ${name}?`}
        description={`from ${organizationName}`}
        leading={<MemberAvatar name={name} size="lg" />}
        role="alertdialog"
        busy={busy !== null}
        closeLabel="Close member"
        testId="member-sheet"
        footer={
          <>
            <Button key="keep-member" autoFocus variant="secondary" disabled={busy !== null} onClick={() => backToDetail("remove-member")}>Keep member</Button>
            <Button key="confirm-member-change" variant="danger" pending={busy !== null} onClick={() => void confirm()}>
              Remove member
            </Button>
          </>
        }
      >
        <ul className="list-disc space-y-2 pl-5 type-body text-foreground">
          {memberAccessConsequences(member, organizationName).map((consequence) => <li key={consequence}>{consequence}</li>)}
        </ul>
        <p className="mt-5 type-meta text-foreground-secondary">
          {`To bring ${firstName(name)} back, send a new invitation.`}
        </p>
        {error && <p role="alert" className="mt-4 type-body text-error">{error}</p>}
      </Sheet>
    );
  }

  return (
    <Sheet
      open
      onOpenChange={(open) => { if (!open) onClose(); }}
      title={name}
      description={<>{member.user.email} · Joined <time dateTime={member.joinedAt}>{formatSettingsDate(member.joinedAt)}</time></>}
      leading={<MemberAvatar name={name} size="lg" />}
      busy={busy !== null}
      closeLabel="Close member"
      testId="member-sheet"
      footer={
        <>
          <button
            key="remove"
            type="button"
            autoFocus={returnFocusTo === "remove-member"}
            disabled={busy !== null || removalBlock !== null}
            onClick={() => { setError(null); setView({ kind: "remove-member" }); }}
            className="type-control text-error underline-offset-4 transition-colors hover:text-error-hover hover:underline disabled:cursor-not-allowed disabled:text-foreground-secondary disabled:no-underline"
          >
            Remove from Organization
          </button>
        </>
      }
    >
      <div className="space-y-8">
        <section aria-labelledby="member-organization-role">
          <SheetSectionHeading id="member-organization-role" title="Organization role" />
          <div className="mt-3">
            <SegmentedControl
              label="Organization role"
              value={member.role === "ADMIN" ? "ADMIN" : "MEMBER"}
              disabled={busy !== null || roleBlock !== null}
              options={[{ value: "MEMBER", label: "Member" }, { value: "ADMIN", label: "Admin" }]}
              onChange={(role) => {
                if (role !== member.role) void run("role", () => updateOrganizationMemberRole(organizationId, member.user.id, role));
              }}
            />
          </div>
          <p className="mt-3 type-meta text-foreground-secondary">
            {roleBlock ?? "Admins manage people and workspaces. Workspace data access comes only from workspace roles."}
          </p>
        </section>
        <WorkspaceAccess
          member={member}
          workspaces={workspaces}
          isSelf={isSelf}
          busy={busy}
          returnFocusTo={returnFocusTo}
          workspaceHref={workspaceHref}
          onSetRole={(workspaceId, role, key) => run(key, () => setWorkspaceMemberRole(workspaceId, member.user.id, role))}
          onRemove={(workspace) => { setError(null); setView({ kind: "remove-workspace-access", workspace }); }}
        />
        {removalBlock ? (
          <section aria-labelledby="member-access">
            <SheetSectionHeading id="member-access" title="Access" />
            <p className="mt-3 type-body text-foreground">{removalBlock.reason}</p>
            {removalBlock.soleOwnerWorkspaces.length > 0 ? (
              <ul className="mt-2 space-y-1">
                {removalBlock.soleOwnerWorkspaces.map((workspace) => (
                  <li key={workspace.id}>
                    <Link href={workspaceHref(workspace.id)} className="type-body text-foreground underline underline-offset-4">
                      Choose another owner for {workspace.name} first
                    </Link>
                  </li>
                ))}
              </ul>
            ) : null}
          </section>
        ) : null}
        {error && <p role="alert" className="type-body text-error">{error}</p>}
      </div>
    </Sheet>
  );
}

function WorkspaceAccess({
  member,
  workspaces,
  isSelf,
  busy,
  returnFocusTo,
  workspaceHref,
  onSetRole,
  onRemove,
}: {
  member: OrganizationMember;
  workspaces: ManagedWorkspace[] | null;
  isSelf: boolean;
  busy: string | null;
  returnFocusTo: FocusTarget | null;
  workspaceHref: (workspaceId: string) => string;
  onSetRole: (workspaceId: string, role: WorkspaceRole, key: string) => Promise<boolean>;
  onRemove: (workspace: OrganizationMemberWorkspace) => void;
}) {
  const [editing, setEditing] = useState<{ workspaceId: string; role: WorkspaceRole } | null>(null);
  const [adding, setAdding] = useState<{ workspaceId: string; role: WorkspaceRole } | null>(null);
  const memberWorkspaces = [...member.workspaces].sort((left, right) => left.name.localeCompare(right.name));
  const addable = (workspaces ?? [])
    .filter((workspace) => !member.workspaces.some((current) => current.id === workspace.id))
    .sort((left, right) => left.name.localeCompare(right.name));
  const name = firstName(member.user.displayName);
  return (
    <section aria-labelledby="member-workspace-access">
      <SheetSectionHeading
        id="member-workspace-access"
        title="Workspace access"
        action={!adding ? (
          <Button
            variant="secondary"
            size="sm"
            autoFocus={returnFocusTo === "add-workspace"}
            disabled={busy !== null || workspaces === null || addable.length === 0}
            title={workspaces === null ? "Workspaces are still loading." : addable.length === 0 ? `${name} is already in every workspace.` : undefined}
            onClick={() => { setEditing(null); setAdding({ workspaceId: addable[0]?.id ?? "", role: "EXPLORER" }); }}
          >
            <Plus aria-hidden="true" />
            Add to Workspace
          </Button>
        ) : null}
      />
      {adding ? (
        <form
          className="mt-3 rounded-[6px] border border-border p-4"
          aria-label="Add to Workspace"
          onSubmit={async (event) => {
            event.preventDefault();
            if (!adding.workspaceId) return;
            if (await onSetRole(adding.workspaceId, adding.role, "add")) setAdding(null);
          }}
        >
          <NativeSelect
            label="Workspace"
            value={adding.workspaceId}
            disabled={busy !== null}
            onChange={(event) => setAdding({ ...adding, workspaceId: event.target.value })}
          >
            {addable.map((workspace) => <option key={workspace.id} value={workspace.id}>{workspace.name}</option>)}
          </NativeSelect>
          <WorkspaceRolePicker label="Workspace role" roles={WORKSPACE_ROLES} value={adding.role} disabled={busy !== null} onChange={(role) => setAdding({ ...adding, role })} />
          <div className="flex justify-end gap-2">
            <Button type="button" variant="ghost" size="sm" disabled={busy !== null} onClick={() => setAdding(null)}>Cancel</Button>
            <Button type="submit" size="sm" pending={busy === "add"} disabled={!adding.workspaceId}>Add to Workspace</Button>
          </div>
        </form>
      ) : null}
      {memberWorkspaces.length === 0 ? (
        <p className="mt-3 type-body text-foreground-secondary">Not in any workspace yet.</p>
      ) : (
        <ul className="mt-2 divide-y divide-border-light">
          {memberWorkspaces.map((workspace) => (
            <WorkspaceAccessRow
              key={workspace.id}
              workspace={workspace}
              memberName={name}
              removalBlock={workspaceAccessRemovalBlock(member.user.displayName, workspace, isSelf)}
              editing={editing?.workspaceId === workspace.id ? editing.role : null}
              busy={busy}
              focusRemove={typeof returnFocusTo === "object" && returnFocusTo?.removeWorkspaceAccess === workspace.id}
              workspaceHref={workspaceHref}
              onEdit={(role) => { setAdding(null); setEditing(role ? { workspaceId: workspace.id, role } : null); }}
              onRemove={() => onRemove(workspace)}
              onSave={async (role) => {
                if (await onSetRole(workspace.id, role, `role-${workspace.id}`)) setEditing(null);
              }}
            />
          ))}
        </ul>
      )}
      {memberWorkspaces.length > 0 ? (
        <p className="mt-3 type-meta text-foreground-secondary">Workspace owners can also change these roles in workspace settings.</p>
      ) : null}
    </section>
  );
}

function WorkspaceAccessRow({
  workspace,
  memberName,
  removalBlock,
  editing,
  busy,
  focusRemove,
  workspaceHref,
  onEdit,
  onRemove,
  onSave,
}: {
  workspace: OrganizationMemberWorkspace;
  memberName: string;
  removalBlock: string | null;
  editing: WorkspaceRole | null;
  busy: string | null;
  focusRemove: boolean;
  workspaceHref: (workspaceId: string) => string;
  onEdit: (role: WorkspaceRole | null) => void;
  onRemove: () => void;
  onSave: (role: WorkspaceRole) => Promise<void>;
}) {
  const soleOwner = workspace.role === "OWNER" && workspace.ownerCount === 1;
  const blocked = editing !== null && soleOwner && editing !== "OWNER";
  return (
    <li className="py-3">
      <div className="flex items-center gap-3">
        <span className="min-w-0 flex-1 truncate type-body text-foreground">{workspace.name}</span>
        <span className="type-body text-foreground-secondary">{roleLabel(workspace.role)}</span>
        {editing === null ? (
          <>
            <Button
              variant="ghost"
              size="sm"
              disabled={busy !== null}
              aria-label={`Change role in ${workspace.name}`}
              onClick={() => onEdit(workspace.role)}
            >
              Change
            </Button>
            <Button
              variant="ghost"
              size="sm"
              autoFocus={focusRemove}
              disabled={busy !== null || removalBlock !== null}
              title={removalBlock ?? undefined}
              aria-label={`Remove from ${workspace.name}`}
              onClick={onRemove}
            >
              Remove
            </Button>
          </>
        ) : null}
      </div>
      {editing !== null ? (
        <div className="mt-1 pl-10">
          <WorkspaceRolePicker label="Workspace role" roles={WORKSPACE_ROLES} value={editing} disabled={busy !== null} onChange={(role) => onEdit(role)} />
          {blocked ? (
            <p className="mb-3 type-meta text-foreground">
              {lastOwnerMessage(memberName, workspace.name)}{" "}
              <Link href={workspaceHref(workspace.id)} className="underline underline-offset-4">Manage owners</Link>
            </p>
          ) : null}
          <div className="flex justify-end gap-2">
            <Button variant="ghost" size="sm" disabled={busy !== null} onClick={() => onEdit(null)}>Cancel</Button>
            <Button
              size="sm"
              pending={busy === `role-${workspace.id}`}
              disabled={blocked || editing === workspace.role}
              onClick={() => void onSave(editing)}
            >
              Save role
            </Button>
          </div>
        </div>
      ) : null}
    </li>
  );
}
