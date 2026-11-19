import { useState } from "react";
import { useRouter } from "@/lib/navigation";
import { webPaths } from "@/lib/web-paths";
import { Button } from "@/components/ui/Button";
import { NativeSelect } from "@/components/ui/Input";
import { Sheet } from "@/components/ui/Sheet";
import { WORKSPACE_ROLES, roleLabel } from "@/lib/auth/roles";
import type { OrganizationMember, WorkspaceRole } from "@/lib/auth/types";
import { MemberAvatar, SheetSectionHeading } from "./SettingsPrimitives";
import { WorkspaceRolePicker } from "./WorkspaceRolePicker";
import { workspaceMemberMutations, type WorkspaceSettingsContext } from "./workspaceSettingsQueries";
import {
  isSoleOwner,
  lastOwnerMessage,
  needsSelfDemotionConfirmation,
  otherOwnerName,
  selfDemotionMessage,
  type WorkspaceSettingsMember,
} from "./workspaceSettingsModel";

type SheetView = { kind: "detail" } | { kind: "remove" } | { kind: "demote"; role: WorkspaceRole };

const REMOVE_ACTION_CLASS =
  "type-control text-error underline-offset-4 transition-colors hover:text-error-hover hover:underline disabled:cursor-not-allowed disabled:text-foreground-secondary disabled:no-underline";

function useSheetAction(onChanged: () => Promise<unknown>) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const run = async (action: () => Promise<unknown>, failure: string): Promise<boolean> => {
    setBusy(true);
    setError(null);
    try {
      await action();
      await onChanged();
      return true;
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : failure);
      return false;
    } finally {
      setBusy(false);
    }
  };
  return { busy, error, setError, run };
}

export function WorkspaceMemberSheet({
  context,
  member,
  members,
  isSelf,
  onClose,
  onChanged,
}: {
  context: WorkspaceSettingsContext;
  member: WorkspaceSettingsMember;
  members: WorkspaceSettingsMember[];
  isSelf: boolean;
  onClose: () => void;
  onChanged: () => Promise<unknown>;
}) {
  const [view, setView] = useState<SheetView>({ kind: "detail" });
  const [returnToRemove, setReturnToRemove] = useState(false);
  const router = useRouter();
  const { busy, error, setError, run } = useSheetAction(onChanged);
  const mutations = workspaceMemberMutations(context);
  const name = member.user.displayName;
  const soleOwner = isSoleOwner(member, members);
  const close = (open: boolean) => { if (!open) onClose(); };
  const backToDetail = () => { setError(null); setView({ kind: "detail" }); };

  const changeRole = (role: WorkspaceRole) => {
    if (role === member.role) return;
    if (needsSelfDemotionConfirmation(member, isSelf, role)) {
      setError(null);
      setView({ kind: "demote", role });
      return;
    }
    void run(() => mutations.setRole(member.user.id, role), "Couldn't change the role. Try again.");
  };

  if (view.kind === "demote") {
    return (
      <Sheet
        open
        onOpenChange={close}
        title={`Change your role to ${roleLabel(view.role)}?`}
        description={context.workspaceName}
        leading={<MemberAvatar name={name} size="lg" />}
        role="alertdialog"
        busy={busy}
        closeLabel="Close member"
        testId="workspace-member-sheet"
        footer={
          <div className="ml-auto flex gap-2">
            <Button key="keep-role" autoFocus variant="secondary" disabled={busy} onClick={backToDetail}>Keep Owner</Button>
            <Button
              key="change-role"
              pending={busy}
              onClick={async () => {
                if (await run(() => mutations.setRole(member.user.id, view.role), "Couldn't change your role. Try again.")) onClose();
              }}
            >
              Change my role
            </Button>
          </div>
        }
      >
        <p className="type-body text-foreground">
          {selfDemotionMessage(otherOwnerName(members, member.user.id), context.organizationRole === "ADMIN")}
        </p>
        {error && <p role="alert" className="mt-4 type-body text-error">{error}</p>}
      </Sheet>
    );
  }

  if (view.kind === "remove") {
    return (
      <Sheet
        open
        onOpenChange={close}
        title={isSelf ? `Leave ${context.workspaceName}?` : `Remove ${name}?`}
        description={isSelf ? undefined : `from ${context.workspaceName}`}
        leading={<MemberAvatar name={name} size="lg" />}
        role="alertdialog"
        busy={busy}
        closeLabel="Close member"
        testId="workspace-member-sheet"
        footer={
          <div className="ml-auto flex gap-2">
            <Button
              key="keep-member"
              autoFocus
              variant="secondary"
              disabled={busy}
              onClick={() => { backToDetail(); setReturnToRemove(true); }}
            >
              {isSelf ? "Stay" : "Keep member"}
            </Button>
            <Button
              key="remove-member"
              variant="danger"
              pending={busy}
              onClick={async () => {
                if (await run(() => mutations.remove(member.user.id), isSelf ? "Couldn't leave this workspace. Try again." : "Couldn't remove this member. Try again.")) {
                  // Leaving ends access to this workspace, so its settings can no longer render.
                  if (isSelf) router.push(webPaths.workspaces); else onClose();
                }
              }}
            >
              {isSelf ? "Leave workspace" : "Remove member"}
            </Button>
          </div>
        }
      >
        <ul className="list-disc space-y-2 pl-5 type-body text-foreground">
          <li>{isSelf ? `You lose access to ${context.workspaceName} and its resources` : `Loses access to ${context.workspaceName} and its resources`}</li>
          <li>{isSelf ? `You stay a member of ${context.organizationName}` : `Stays a member of ${context.organizationName}`}</li>
        </ul>
        {error && <p role="alert" className="mt-4 type-body text-error">{error}</p>}
      </Sheet>
    );
  }

  return (
    <Sheet
      open
      onOpenChange={close}
      title={name}
      description={member.user.email || undefined}
      leading={<MemberAvatar name={name} size="lg" />}
      busy={busy}
      closeLabel="Close member"
      testId="workspace-member-sheet"
      footer={
        <button
          key="remove"
          type="button"
          autoFocus={returnToRemove}
          disabled={busy || soleOwner}
          onClick={() => { setError(null); setView({ kind: "remove" }); }}
          className={REMOVE_ACTION_CLASS}
        >
          {isSelf ? `Leave ${context.workspaceName}` : `Remove from ${context.workspaceName}`}
        </button>
      }
    >
      <div className="space-y-8">
        <section aria-labelledby="workspace-member-role">
          <SheetSectionHeading id="workspace-member-role" title={`Role in ${context.workspaceName}`} />
          <WorkspaceRolePicker
            label="Workspace role"
            hideLabel
            roles={WORKSPACE_ROLES}
            value={member.role}
            disabled={busy || soleOwner}
            onChange={changeRole}
          />
          {soleOwner ? <p className="type-meta text-foreground">{lastOwnerMessage(name, context.workspaceName)}</p> : null}
        </section>
        {member.organizationRole ? (
          <section aria-labelledby="workspace-member-organization">
            <SheetSectionHeading id="workspace-member-organization" title="Organization" />
            <p className="mt-2 type-body text-foreground">
              {context.organizationName} · {member.organizationRole === "ADMIN" ? "Admin" : "Member"}
            </p>
          </section>
        ) : null}
        {error && <p role="alert" className="type-body text-error">{error}</p>}
      </div>
    </Sheet>
  );
}

export function AddWorkspaceMemberSheet({
  context,
  candidates,
  candidatesError,
  onRetryCandidates,
  onClose,
  onChanged,
}: {
  context: WorkspaceSettingsContext;
  candidates: OrganizationMember[] | undefined;
  candidatesError: boolean;
  onRetryCandidates: () => void;
  onClose: () => void;
  onChanged: () => Promise<unknown>;
}) {
  const [candidateId, setCandidateId] = useState("");
  const [role, setRole] = useState<WorkspaceRole>("EXPLORER");
  const { busy, error, run } = useSheetAction(onChanged);
  const mutations = workspaceMemberMutations(context);
  const none = candidates !== undefined && candidates.length === 0;
  const submit = async () => {
    if (!candidateId || busy) return;
    if (await run(() => mutations.add(candidateId, role), "Couldn't add this member. Try again.")) onClose();
  };
  return (
    <Sheet
      open
      onOpenChange={(open) => { if (!open) onClose(); }}
      title="Add member"
      description={`to ${context.workspaceName}`}
      busy={busy}
      closeLabel="Close"
      testId="workspace-add-member-sheet"
      footer={
        <div className="ml-auto flex gap-2">
          <Button variant="ghost" disabled={busy} onClick={onClose}>Cancel</Button>
          <Button pending={busy} disabled={!candidateId} onClick={() => void submit()}>Add member</Button>
        </div>
      }
    >
      <form aria-label="Add member" onSubmit={(event) => { event.preventDefault(); void submit(); }}>
        <NativeSelect
          label="Organization member"
          value={candidateId}
          disabled={busy || candidates === undefined || none}
          onChange={(event) => setCandidateId(event.target.value)}
          helperText={none ? `Only people in ${context.organizationName} can be added. To add someone new, an Organization Admin must invite them first.` : undefined}
        >
          <option value="">{candidates === undefined ? "Loading members…" : none ? "No Organization members to add" : "Select a member"}</option>
          {(candidates ?? []).map((candidate) => (
            <option key={candidate.user.id} value={candidate.user.id}>{candidate.user.displayName} · {candidate.user.email}</option>
          ))}
        </NativeSelect>
        {candidatesError ? (
          <p role="alert" className="mt-2 type-meta text-error">
            Couldn&apos;t load Organization members.{" "}
            <button type="button" className="underline underline-offset-4" onClick={onRetryCandidates}>Retry</button>
          </p>
        ) : null}
        <WorkspaceRolePicker
          label="Workspace role"
          roles={WORKSPACE_ROLES}
          value={role}
          disabled={busy}
          onChange={setRole}
        />
      </form>
      {error && <p role="alert" className="type-body text-error">{error}</p>}
    </Sheet>
  );
}
