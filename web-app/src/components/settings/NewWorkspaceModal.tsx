import { useState } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { Button } from "@/components/ui/Button";
import { Input, NativeSelect } from "@/components/ui/Input";
import { SettingsDialog } from "./SettingsDialog";
import { createWorkspace } from "@/lib/auth/graphql";
import { useOrganizationMembersQuery } from "./organizationQueries";
import { useAuthStore } from "@/lib/auth/store";
import type { Workspace } from "@/lib/auth/types";

export function NewWorkspaceModal({
  organizationId,
  onClose,
  onCreated,
}: {
  organizationId: string;
  onClose: () => void;
  onCreated: (workspace: Workspace) => void | Promise<void>;
}) {
  const currentUserId = useAuthStore((state) => state.user?.id ?? "");
  const queryClient = useQueryClient();
  const members = useOrganizationMembersQuery(organizationId);
  const [ownerId, setOwnerId] = useState(currentUserId);
  const [name, setName] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const canSubmit =
    !busy &&
    name.trim().length > 0 &&
    members.data?.some(
      (member) => member.user.id === ownerId,
    );
  const submit = async () => {
    if (!canSubmit) return;
    setBusy(true);
    setError(null);
    try {
      const workspace = await createWorkspace(organizationId, name.trim(), ownerId);
      await queryClient.invalidateQueries({ queryKey: ["auth"] });
      await onCreated(workspace);
      onClose();
    } catch (cause) {
      setError(
        cause instanceof Error ? cause.message : "Couldn't create Workspace.",
      );
    } finally {
      setBusy(false);
    }
  };
  return (
    <SettingsDialog
      title="New Workspace"
      onClose={onClose}
      busy={busy}
      footer={
        <>
          <Button variant="ghost" disabled={busy} onClick={onClose}>
            Cancel
          </Button>
          <Button
            disabled={!canSubmit}
            onClick={() => void submit()}
            data-testid="new-workspace-submit"
          >
            Create Workspace
          </Button>
        </>
      }
    >
      <Input
        id="workspace-name"
        label="Workspace name"
        autoFocus
        value={name}
        onChange={(event) => setName(event.target.value)}
        disabled={busy}
        data-testid="new-workspace-name-input"
      />
      <div className="mt-6">
        <NativeSelect
          id="initial-owner"
          label="Initial Owner"
          value={ownerId}
          onChange={(event) => setOwnerId(event.target.value)}
          disabled={busy || !members.data}
        >
          {members.data
            ?.map((member) => (
              <option key={member.user.id} value={member.user.id}>
                {member.user.displayName} · {member.user.email}
                {member.user.id === currentUserId ? " · You" : ""}
              </option>
            ))}
        </NativeSelect>
      </div>
      {members.isError && (
        <Button
          className="mt-3"
          variant="secondary"
          onClick={() => void members.refetch()}
        >
          Couldn't load members. Retry
        </Button>
      )}
      <dl className="mt-6 divide-y divide-border-light border-y border-border type-body">
        <div className="grid grid-cols-2 gap-3 py-3">
          <dt className="type-meta text-foreground-secondary">Membership</dt>
          <dd>Included</dd>
        </div>
        <div className="grid grid-cols-2 gap-3 py-3"><dt className="type-meta text-foreground-secondary">Workspace role</dt><dd>Owner · full workspace access</dd></div>
      </dl>
      {error && (
        <p role="alert" className="mt-4 type-body text-error">
          {error}
        </p>
      )}
    </SettingsDialog>
  );
}
