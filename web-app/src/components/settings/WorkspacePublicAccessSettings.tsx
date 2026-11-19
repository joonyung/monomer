import { useState } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { TriangleAlert } from "lucide-react";
import { publicPagesKey } from "@/api/public-pages";
import { Button } from "@/components/ui/Button";
import { setWorkspacePublicPages } from "@/lib/auth/graphql";
import { SettingsDialog } from "./SettingsDialog";
import { WorkspaceSettingsShell } from "./WorkspaceSettingsShell";
import {
  useInvalidateWorkspaceSettings,
  useWorkspaceMembersSource,
  useWorkspaceSettingsContext,
} from "./workspaceSettingsQueries";
import { publicAccessStatus, publisherCount, publisherWarning } from "./workspaceSettingsModel";

const PUBLIC_SCOPE = [
  "Every Page in this workspace, now and in the future",
  "Past and future results of dashboards added to Pages",
  "Uploaded documents and their replacements",
];

export function WorkspacePublicAccessSettings() {
  return (
    <WorkspaceSettingsShell section="public-access" title="Public access">
      <PublicAccessControl />
    </WorkspaceSettingsShell>
  );
}

function PublicAccessControl() {
  const context = useWorkspaceSettingsContext();
  const source = useWorkspaceMembersSource(context);
  const invalidate = useInvalidateWorkspaceSettings(context.workspaceId);
  const queryClient = useQueryClient();
  const [pending, setPending] = useState(false);
  const [confirming, setConfirming] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [confirmError, setConfirmError] = useState<string | null>(null);
  const publicPages = context.publicPages;
  const editable = context.capabilities.canEditPublicAccess;

  const change = async (enabled: boolean): Promise<boolean> => {
    setPending(true);
    try {
      await setWorkspacePublicPages(context.workspaceId, enabled);
      queryClient.removeQueries({ queryKey: publicPagesKey(context.workspaceId) });
      await invalidate();
      return true;
    } catch (cause) {
      const message = cause instanceof Error ? cause.message : "Couldn't change public access. Try again.";
      if (enabled) setConfirmError(message);
      else setError(message);
      return false;
    } finally {
      setPending(false);
    }
  };

  return (
    <section aria-labelledby="public-access-title" className="max-w-[760px]">
      <div className="flex items-start justify-between gap-6 border-y border-border py-4">
        <div className="min-w-0">
          <h2 id="public-access-title" className="type-item text-heading">Anyone with a link can view Pages</h2>
          <ul id="public-access-scope" className="mt-3 list-disc space-y-1.5 pl-5 type-body text-foreground">
            {PUBLIC_SCOPE.map((item) => <li key={item}>{item}</li>)}
          </ul>
        </div>
        {editable ? (
          <label className="flex flex-shrink-0 items-center gap-2 type-control">
            <input
              type="checkbox"
              role="switch"
              aria-label="Public Pages"
              aria-describedby="public-access-scope"
              checked={publicPages}
              disabled={pending}
              onChange={(event) => {
                setError(null);
                if (event.target.checked) {
                  setConfirmError(null);
                  setConfirming(true);
                } else {
                  void change(false);
                }
              }}
              className="h-4 w-4 accent-accent"
            />
            {pending && !confirming ? "Saving…" : publicAccessStatus(publicPages)}
          </label>
        ) : (
          <p className="flex flex-shrink-0 items-center gap-2 type-control text-foreground" data-testid="public-access-status">
            <span aria-hidden="true" className={`h-1.5 w-1.5 rounded-full ${publicPages ? "bg-foreground" : "bg-foreground-secondary"}`} />
            {publicAccessStatus(publicPages)}
          </p>
        )}
      </div>
      <p className="mt-4 flex items-start gap-2 type-body text-foreground">
        <TriangleAlert className="mt-0.5 h-4 w-4 flex-shrink-0 text-warning" aria-hidden="true" />
        <span>{source.members ? publisherWarning(publisherCount(source.members)) : "While on, anyone with Explorer or higher can publish by adding to Pages. Visitors can't edit anything."}</span>
      </p>
      <p className="mt-3 type-meta text-foreground-secondary">
        Turning this off stops new public access. Downloads and link previews already shared may remain.
      </p>
      {!editable ? (
        <p className="mt-3 type-meta text-foreground-secondary">Only workspace owners can change public access.</p>
      ) : null}
      {error && <p role="alert" className="mt-3 type-body text-error">{error}</p>}
      {confirming && (
        <SettingsDialog
          title={`Make all Pages in ${context.workspaceName} public?`}
          size="sm"
          role="alertdialog"
          busy={pending}
          onClose={() => setConfirming(false)}
          footer={
            <>
              <Button variant="ghost" disabled={pending} onClick={() => setConfirming(false)}>Cancel</Button>
              <Button
                pending={pending}
                onClick={async () => {
                  setConfirmError(null);
                  if (await change(true)) setConfirming(false);
                }}
              >
                Make public
              </Button>
            </>
          }
        >
          <p className="type-body">Anyone with a link can view:</p>
          <ul className="mt-2 list-disc space-y-1.5 pl-5 type-body">
            {PUBLIC_SCOPE.map((item) => <li key={item}>{item}</li>)}
          </ul>
          <p className="mt-3 type-body text-foreground-secondary">Visitors can&apos;t edit anything.</p>
          {confirmError && <p role="alert" className="mt-3 type-body text-error">{confirmError}</p>}
        </SettingsDialog>
      )}
    </section>
  );
}
