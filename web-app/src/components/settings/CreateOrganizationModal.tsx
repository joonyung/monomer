import { useState } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { SettingsDialog } from "./SettingsDialog";
import { Button } from "@/components/ui/Button";
import { Input } from "@/components/ui/Input";
import { createOrganization, fetchMe } from "@/lib/auth/graphql";
import { useAuthStore } from "@/lib/auth/store";
import { useRouter } from "@/lib/navigation";
import { webPaths } from "@/lib/web-paths";

export function CreateOrganizationModal({ onClose }: { onClose: () => void }) {
  const [name, setName] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const queryClient = useQueryClient();
  const router = useRouter();
  const create = async () => {
    setBusy(true);
    setError(null);
    try {
      const created = await createOrganization(name.trim());
      const me = await fetchMe(undefined, false);
      useAuthStore
        .getState()
        .setMe({ user: me.user, organizationMemberships: me.organizationMemberships });
      await queryClient.invalidateQueries({ queryKey: ["auth"] });
      onClose();
      router.push(webPaths.workspace(created.defaultWorkspaceId));
    } catch (cause) {
      setError(
        cause instanceof Error
          ? cause.message
          : "Couldn't create Organization.",
      );
    } finally {
      setBusy(false);
    }
  };
  return (
    <SettingsDialog
      title="Create a new Organization"
      description="An Organization holds your team's workspaces, members, and billing plan."
      busy={busy}
      onClose={onClose}
      footer={
        <>
          <Button variant="ghost" disabled={busy} onClick={onClose}>
            Cancel
          </Button>
          <Button disabled={busy || !name.trim()} onClick={() => void create()}>
            Create Organization
          </Button>
        </>
      }
    >
      <Input
        id="organization-name"
        label="Organization name"
        autoFocus
        value={name}
        onChange={(event) => setName(event.target.value)}
        disabled={busy}
      />
      <dl className="mt-6 divide-y divide-border-light border-y border-border type-body">
        <div className="grid grid-cols-2 gap-3 py-3">
          <dt className="type-meta text-foreground-secondary">
            Your Organization role
          </dt>
          <dd>Admin</dd>
        </div>
        <div className="grid grid-cols-2 gap-3 py-3">
          <dt className="type-meta text-foreground-secondary">
            Default Workspace
          </dt>
          <dd>You · Owner</dd>
        </div>
      </dl>
      {error && (
        <p role="alert" className="mt-4 type-body text-error">
          {error}
        </p>
      )}
    </SettingsDialog>
  );
}
