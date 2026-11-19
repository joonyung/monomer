import { useState } from "react";
import { Button } from "@/components/ui/Button";
import { StateBlock } from "@/components/ui/StateBlock";
import {
  mutateOrganizationInvitation,
  type InvitationDeliveryResult,
  type OrganizationInvitation,
} from "@/lib/auth/invitations";
import { SettingsDialog } from "./SettingsDialog";
import { useOrganizationInvitationsQuery } from "./organizationQueries";
import { invitationDeliveryFailed, invitationExpiryLabel, invitationWorkspaceAccessLabel } from "./organizationSettingsModel";

// Pending invitations are work an Admin can act on now, so they sit above the member table and
// disappear entirely when there are none.
export function PendingInvitations({
  organizationId,
  onResult,
}: {
  organizationId: string;
  onResult: (result: InvitationDeliveryResult) => Promise<void>;
}) {
  const query = useOrganizationInvitationsQuery(organizationId);
  const [expanded, setExpanded] = useState(false);
  if (query.isPending) return null;
  if (query.isError) {
    return (
      <div className="mb-8 rounded-[6px] border border-border">
        <StateBlock
          kind="error"
          layout="inline"
          title="Couldn't load invitations."
          action={<Button variant="secondary" size="sm" aria-label="Retry loading invitations" onClick={() => void query.refetch()}>Retry</Button>}
        />
      </div>
    );
  }
  const invitations = query.data;
  if (invitations.length === 0) return null;
  const now = new Date();
  return (
    <section aria-labelledby="pending-invitations-heading" className="mb-8 rounded-[6px] border border-border">
      <div className="flex flex-wrap items-center justify-between gap-x-4 gap-y-1 px-4 py-3">
        <h2 id="pending-invitations-heading" className="type-control text-heading">
          Pending invitations <span className="tabular-nums text-foreground-secondary">· {invitations.length}</span>
        </h2>
        <p className="hidden type-meta text-foreground-secondary md:block">People join as Members after they accept.</p>
        <Button
          variant="ghost"
          size="sm"
          className="md:hidden"
          aria-expanded={expanded}
          aria-controls="pending-invitations-list"
          onClick={() => setExpanded((value) => !value)}
        >
          {expanded ? "Hide" : "Show"}
        </Button>
      </div>
      <ul id="pending-invitations-list" className={`${expanded ? "block" : "hidden"} divide-y divide-border-light border-t border-border-light md:block`}>
        {invitations.map((invitation) => (
          <InvitationRow key={invitation.id} invitation={invitation} now={now} onResult={onResult} />
        ))}
      </ul>
    </section>
  );
}

function InvitationRow({
  invitation,
  now,
  onResult,
}: {
  invitation: OrganizationInvitation;
  now: Date;
  onResult: (result: InvitationDeliveryResult) => Promise<void>;
}) {
  const [busy, setBusy] = useState<"resend" | "cancel" | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [revokeOpen, setRevokeOpen] = useState(false);
  const failed = invitationDeliveryFailed(invitation);
  const act = async (action: "resend" | "cancel") => {
    setBusy(action);
    setError(null);
    try {
      await onResult(await mutateOrganizationInvitation(action, invitation.organizationId, invitation.id));
      setRevokeOpen(false);
    } catch {
      setError("Couldn't update this invitation. Try again.");
    } finally {
      setBusy(null);
    }
  };
  return (
    <li className="grid grid-cols-[minmax(0,1fr)_auto] items-center gap-x-4 gap-y-1 px-4 py-3 md:grid-cols-[minmax(0,1fr)_160px_auto]">
      <div className="min-w-0">
        <p className="break-all type-body text-foreground">{invitation.deliveryEmail}</p>
        <p className="type-meta text-foreground-secondary">
          {failed ? <span className="text-error">Email not delivered</span> : null}
          {failed && invitation.invitedBy ? " · " : null}
          {invitation.invitedBy ? `Invited by ${invitation.invitedBy.displayName}` : null}
        </p>
        {invitation.workspaceAccess.length > 0 ? (
          <p className="type-meta text-foreground-secondary">{invitationWorkspaceAccessLabel(invitation.workspaceAccess)}</p>
        ) : null}
      </div>
      <p className="col-start-1 row-start-2 type-meta tabular-nums text-foreground-secondary md:col-start-2 md:row-start-1">
        <time dateTime={invitation.expiresAt}>{invitationExpiryLabel(invitation, now)}</time>
      </p>
      <div className="col-start-2 row-span-2 row-start-1 flex gap-1 md:col-start-3 md:row-span-1">
        <Button
          variant="ghost"
          size="sm"
          pending={busy === "resend"}
          disabled={busy !== null}
          aria-label={`${failed ? "Retry sending" : "Resend"} invitation to ${invitation.deliveryEmail}`}
          onClick={() => void act("resend")}
        >
          {failed ? "Retry send" : "Resend"}
        </Button>
        <Button
          variant="ghost"
          size="sm"
          disabled={busy !== null}
          aria-label={`Revoke invitation to ${invitation.deliveryEmail}`}
          onClick={() => setRevokeOpen(true)}
        >
          Revoke
        </Button>
      </div>
      {error && !revokeOpen && <p role="alert" className="col-span-full type-meta text-error">{error}</p>}
      {revokeOpen && (
        <SettingsDialog
          title="Revoke invitation?"
          size="sm"
          role="alertdialog"
          description={invitation.deliveryEmail}
          onClose={() => setRevokeOpen(false)}
          busy={busy === "cancel"}
          footer={
            <>
              <Button variant="ghost" disabled={busy === "cancel"} onClick={() => setRevokeOpen(false)}>Keep invitation</Button>
              <Button variant="danger" pending={busy === "cancel"} onClick={() => void act("cancel")}>Revoke invitation</Button>
            </>
          }
        >
          <p className="type-body">The invitation link stops working. You can send a new invitation later.</p>
          {error && <p role="alert" className="mt-4 type-body text-error">{error}</p>}
        </SettingsDialog>
      )}
    </li>
  );
}
