import { useState } from "react";
import { Plus, X } from "lucide-react";
import { Button } from "@/components/ui/Button";
import { IconButton } from "@/components/ui/IconButton";
import { Input, NativeSelect } from "@/components/ui/Input";
import { useManagedWorkspacesQuery } from "@/components/auth/TenantRouteContext";
import { WORKSPACE_ROLES, roleLabel } from "@/lib/auth/roles";
import type { WorkspaceRole } from "@/lib/auth/types";
import { SettingsDialog } from "./SettingsDialog";
import { useInvalidateOrganizationSettings, useOrganizationUsageQuery } from "./organizationQueries";
import { USAGE_COUNTERS, usageWarning } from "./organizationSettingsModel";
import { WorkspaceRolePicker } from "./WorkspaceRolePicker";
import {
  createOrganizationInvitation,
  invitationDeliveryMessage,
  type InvitationDeliveryResult,
  type InvitationWorkspaceAccessInput,
} from "@/lib/auth/invitations";

// Matches Control's limit on Workspace access attached to one invitation.
const MAX_WORKSPACE_ACCESS = 20;
const SAVED_OUTCOMES: readonly InvitationDeliveryResult["outcome"][] = ["SENT", "DELIVERY_FAILED", "INVITATION_PENDING"];

export function InviteMemberModal({
  organizationId,
  onClose,
  onSent,
}: {
  organizationId: string;
  onClose: () => void;
  onSent: (result: InvitationDeliveryResult) => Promise<void>;
}) {
  const usage = useOrganizationUsageQuery(organizationId).data;
  const workspaces = useManagedWorkspacesQuery(organizationId).data ?? [];
  const invalidate = useInvalidateOrganizationSettings();
  // Only near the member limit is the limit worth mentioning here; General owns the full usage.
  const memberWarning = usage?.limits
    ? usageWarning(usage.usage.members, usage.limits.members, USAGE_COUNTERS[0])
    : null;
  const [email, setEmail] = useState("");
  const [access, setAccess] = useState<InvitationWorkspaceAccessInput[]>([]);
  const [adding, setAdding] = useState<{ workspaceId: string; role: WorkspaceRole } | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const workspaceName = (workspaceId: string) => workspaces.find((workspace) => workspace.id === workspaceId)?.name ?? "Workspace";
  const addable = workspaces.filter((workspace) => !access.some((grant) => grant.workspaceId === workspace.id));
  const submit = async () => {
    if (busy || !email.trim() || adding !== null) return;
    setBusy(true);
    setError(null);
    try {
      const result = await createOrganizationInvitation(organizationId, email.trim(), access);
      if (SAVED_OUTCOMES.includes(result.outcome)) {
        await onSent(result);
        onClose();
      } else {
        await invalidate();
        setError(invitationDeliveryMessage(result));
      }
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "Couldn't send the invitation. Please try again.");
    } finally {
      setBusy(false);
    }
  };
  return (
    <SettingsDialog
      title="Invite people"
      busy={busy}
      onClose={onClose}
      footer={
        <>
          <Button
            variant="ghost"
            disabled={busy}
            onClick={onClose}
          >
            Cancel
          </Button>
          <Button
            pending={busy}
            disabled={!email.trim() || adding !== null}
            onClick={() => void submit()}
          >
            Send invitation
          </Button>
        </>
      }
    >
      <form
        onSubmit={(event) => {
          event.preventDefault();
          void submit();
        }}
      >
        <Input
          id="invite-email"
          label="Email"
          type="email"
          required
          autoFocus
          autoComplete="off"
          value={email}
          onChange={(event) => setEmail(event.target.value)}
          disabled={busy}
        />
        <dl className="mt-5 border-y border-border type-body">
          <div className="grid grid-cols-2 gap-3 py-3">
            <dt className="type-meta text-foreground-secondary">Organization role</dt>
            <dd>Member</dd>
          </div>
        </dl>
      </form>
      <section className="mt-5" aria-labelledby="invite-workspace-access">
        <div className="flex items-center justify-between gap-3">
          <div className="min-w-0">
            <h3 id="invite-workspace-access" className="type-item text-heading">Workspace access</h3>
            <p className="mt-1 type-meta text-foreground-secondary">Optional. Granted when they accept.</p>
          </div>
          {adding === null && addable.length > 0 && access.length < MAX_WORKSPACE_ACCESS ? (
            <Button
              type="button"
              variant="secondary"
              size="sm"
              disabled={busy}
              onClick={() => setAdding({ workspaceId: addable[0].id, role: "EXPLORER" })}
            >
              <Plus aria-hidden="true" />
              Add workspace
            </Button>
          ) : null}
        </div>
        {access.length === 0 && adding === null ? (
          <p className="mt-3 type-body text-foreground-secondary">
            {workspaces.length === 0 ? "This Organization has no workspaces yet." : "No workspace access. A Workspace Owner can add them later."}
          </p>
        ) : null}
        {access.length > 0 ? (
          <ul className="mt-3 divide-y divide-border-light border-y border-border" aria-label="Workspace access to grant">
            {access.map((grant) => (
              <li key={grant.workspaceId} className="flex items-center justify-between gap-3 py-2">
                <span className="min-w-0 truncate type-body">
                  {workspaceName(grant.workspaceId)} <span className="text-foreground-secondary">· {roleLabel(grant.role)}</span>
                </span>
                <IconButton
                  icon={X}
                  label={`Remove ${workspaceName(grant.workspaceId)}`}
                  disabled={busy}
                  onClick={() => setAccess(access.filter((candidate) => candidate.workspaceId !== grant.workspaceId))}
                />
              </li>
            ))}
          </ul>
        ) : null}
        {adding ? (
          <form
            className="mt-3 rounded-[6px] border border-border p-4"
            aria-label="Add workspace access"
            onSubmit={(event) => {
              event.preventDefault();
              if (!adding.workspaceId) return;
              setAccess([...access, adding]);
              setAdding(null);
            }}
          >
            <NativeSelect
              label="Workspace"
              value={adding.workspaceId}
              disabled={busy}
              onChange={(event) => setAdding({ ...adding, workspaceId: event.target.value })}
            >
              {addable.map((workspace) => <option key={workspace.id} value={workspace.id}>{workspace.name}</option>)}
            </NativeSelect>
            <WorkspaceRolePicker label="Workspace role" roles={WORKSPACE_ROLES} value={adding.role} disabled={busy} onChange={(role) => setAdding({ ...adding, role })} />
            <div className="flex justify-end gap-2">
              <Button type="button" variant="ghost" size="sm" disabled={busy} onClick={() => setAdding(null)}>Cancel</Button>
              <Button type="submit" size="sm" disabled={busy || !adding.workspaceId}>Add</Button>
            </div>
          </form>
        ) : null}
      </section>
      {memberWarning ? (
        <p className="mt-4 type-body text-foreground-secondary">
          {memberWarning}. An invitation doesn&apos;t reserve a place.
        </p>
      ) : null}
      {error && (
        <p role="alert" className="mt-4 type-body text-error">
          {error}
        </p>
      )}
    </SettingsDialog>
  );
}
