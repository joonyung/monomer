import { useState } from "react";
import { Button } from "@/components/ui/Button";
import { Input } from "@/components/ui/Input";
import { renameWorkspace } from "@/lib/auth/graphql";
import { SettingsDialog } from "./SettingsDialog";

export function RenameWorkspaceDialog({
  workspace,
  onClose,
  onRenamed,
}: {
  workspace: { id: string; name: string };
  onClose: () => void;
  onRenamed: () => Promise<unknown>;
}) {
  const [draft, setDraft] = useState(workspace.name);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const trimmed = draft.trim();
  const save = async () => {
    if (!trimmed || busy) return;
    setBusy(true);
    setError(null);
    try {
      await renameWorkspace(workspace.id, trimmed);
      await onRenamed();
      onClose();
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "Couldn't rename the workspace.");
    } finally {
      setBusy(false);
    }
  };
  return (
    <SettingsDialog
      title="Rename Workspace"
      size="sm"
      onClose={onClose}
      busy={busy}
      footer={
        <>
          <Button variant="ghost" disabled={busy} onClick={onClose}>Cancel</Button>
          <Button pending={busy} disabled={!trimmed || trimmed === workspace.name} onClick={() => void save()}>Save</Button>
        </>
      }
    >
      <form onSubmit={(event) => { event.preventDefault(); void save(); }}>
        <Input id="workspace-name-input" label="Workspace name" autoFocus value={draft} disabled={busy} onChange={(event) => setDraft(event.target.value)} />
      </form>
      {error && <p role="alert" className="mt-4 type-body text-error">{error}</p>}
    </SettingsDialog>
  );
}
