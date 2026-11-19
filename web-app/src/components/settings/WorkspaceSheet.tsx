import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { ArrowUpRight, Plus, TriangleAlert } from "lucide-react";
import { Button, buttonClassName } from "@/components/ui/Button";
import { NativeSelect } from "@/components/ui/Input";
import { Sheet } from "@/components/ui/Sheet";
import { Link } from "@/lib/navigation";
import {
  useAccessibleWorkspacesQuery,
  useOrganizationRouteContext,
} from "@/components/auth/TenantRouteContext";
import { fetchWorkspaceOwnerCandidates, setWorkspaceMemberRole } from "@/lib/auth/graphql";
import { useAuthStore } from "@/lib/auth/store";
import type { ManagedWorkspace, OrganizationMember, WorkspaceRole } from "@/lib/auth/types";
import { webPaths } from "@/lib/web-paths";
import { MemberAvatar, SheetSectionHeading } from "./SettingsPrimitives";
import { RenameWorkspaceDialog } from "./RenameWorkspaceDialog";
import { REPLACEMENT_WORKSPACE_ROLES, WorkspaceRolePicker } from "./WorkspaceRolePicker";
import { useInvalidateOrganizationSettings } from "./organizationQueries";
import { firstName, formatSettingsDate, workspaceOwners } from "./organizationSettingsModel";

type ReplacementRole = Exclude<WorkspaceRole, "OWNER">;

interface WorkspaceSheetProps {
  workspace: ManagedWorkspace;
  members: OrganizationMember[];
  memberHref: (userId: string) => string;
  onClose: () => void;
}

export function WorkspaceSheet({ workspace, members, memberHref, onClose }: WorkspaceSheetProps) {
  const { organizationId } = useOrganizationRouteContext();
  const principalId = useAuthStore((state) => state.user?.id ?? "");
  const accessible = useAccessibleWorkspacesQuery().data ?? [];
  const invalidate = useInvalidateOrganizationSettings();
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [removing, setRemoving] = useState<{ userId: string; role: ReplacementRole } | null>(null);
  const [addingOwnerId, setAddingOwnerId] = useState<string | null>(null);
  const [renameOpen, setRenameOpen] = useState(false);
  const owners = workspaceOwners(workspace.id, members);
  const canOpen = accessible.some((candidate) => candidate.id === workspace.id);
  const candidatesQuery = useQuery({
    queryKey: ["auth", principalId, organizationId, "owner-candidates", workspace.id],
    queryFn: ({ signal }) => fetchWorkspaceOwnerCandidates(organizationId, workspace.id, signal),
    enabled: addingOwnerId !== null,
  });
  const candidates = (candidatesQuery.data ?? []).filter((candidate) => !owners.some((owner) => owner.user.id === candidate.id));

  const run = async (key: string, action: () => Promise<unknown>): Promise<boolean> => {
    setBusy(key);
    setError(null);
    try {
      await action();
      await invalidate();
      return true;
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "Couldn't update owners. Try again.");
      return false;
    } finally {
      setBusy(null);
    }
  };

  return (
    <Sheet
      open
      onOpenChange={(open) => { if (!open) onClose(); }}
      title={workspace.name}
      description={
        <>
          <span className="tabular-nums">{workspace.memberCount}</span> {workspace.memberCount === 1 ? "member" : "members"} · Created{" "}
          <time dateTime={workspace.createdAt}>{formatSettingsDate(workspace.createdAt)}</time>
        </>
      }
      busy={busy !== null}
      closeLabel="Close Workspace"
      testId="workspace-sheet"
    >
      <div className="space-y-8">
        {canOpen ? (
          <Link href={webPaths.workspace(workspace.id)} className={buttonClassName("secondary", "sm", "")}>
            <ArrowUpRight aria-hidden="true" />
            Open Workspace
          </Link>
        ) : null}
        <section aria-labelledby="workspace-owners">
          <SheetSectionHeading
            id="workspace-owners"
            title="Owners"
            action={addingOwnerId === null ? (
              <Button variant="secondary" size="sm" disabled={busy !== null} onClick={() => { setRemoving(null); setAddingOwnerId(""); }}>
                <Plus aria-hidden="true" />
                Add owner
              </Button>
            ) : null}
          />
          {addingOwnerId !== null ? (
            <form
              aria-label="Add owner"
              className="mt-3 rounded-[6px] border border-border p-4"
              onSubmit={async (event) => {
                event.preventDefault();
                if (!addingOwnerId) return;
                if (await run("add-owner", () => setWorkspaceMemberRole(workspace.id, addingOwnerId, "OWNER"))) setAddingOwnerId(null);
              }}
            >
              <NativeSelect
                label="Member"
                value={addingOwnerId}
                disabled={busy !== null || !candidatesQuery.isSuccess}
                onChange={(event) => setAddingOwnerId(event.target.value)}
                helperText={candidatesQuery.isSuccess && candidates.length === 0
                  ? "Every member is already an owner."
                  : "People who aren't in the workspace yet are added as owners."}
              >
                <option value="">{candidatesQuery.isPending ? "Loading members…" : "Select a member"}</option>
                {candidates.map((candidate) => (
                  <option key={candidate.id} value={candidate.id}>{candidate.displayName} · {candidate.email}</option>
                ))}
              </NativeSelect>
              {candidatesQuery.isError ? (
                <p role="alert" className="mt-2 type-meta text-error">
                  Couldn&apos;t load members.{" "}
                  <button type="button" className="underline underline-offset-4" onClick={() => void candidatesQuery.refetch()}>Retry</button>
                </p>
              ) : null}
              <div className="mt-4 flex justify-end gap-2">
                <Button type="button" variant="ghost" size="sm" disabled={busy !== null} onClick={() => setAddingOwnerId(null)}>Cancel</Button>
                <Button type="submit" size="sm" pending={busy === "add-owner"} disabled={!addingOwnerId}>Add owner</Button>
              </div>
            </form>
          ) : null}
          {owners.length === 0 ? (
            <p className="mt-3 flex items-center gap-1.5 type-body text-foreground">
              <TriangleAlert className="h-4 w-4 flex-shrink-0 text-warning" aria-hidden="true" />
              No owner. Add one so someone can manage this workspace.
            </p>
          ) : (
            <ul className="mt-2 divide-y divide-border-light">
              {owners.map((owner) => {
                const name = owner.user.displayName;
                const editing = removing?.userId === owner.user.id ? removing : null;
                return (
                  <li key={owner.user.id} className="py-3">
                    <div className="flex items-center gap-3">
                      <MemberAvatar name={name} />
                      <div className="min-w-0 flex-1">
                        <Link href={memberHref(owner.user.id)} className="block truncate type-body text-foreground underline-offset-4 hover:underline">
                          {name}
                          {owner.user.id === principalId ? <span className="text-foreground-secondary"> · You</span> : null}
                        </Link>
                      </div>
                      {editing === null ? (
                        <Button
                          variant="ghost"
                          size="sm"
                          disabled={busy !== null || owners.length === 1}
                          aria-label={`Remove ${name} as owner`}
                          onClick={() => { setAddingOwnerId(null); setError(null); setRemoving({ userId: owner.user.id, role: "EXPLORER" }); }}
                        >
                          Remove as owner
                        </Button>
                      ) : null}
                    </div>
                    {editing ? (
                      <div className="mt-3 pl-10">
                        <p className="type-meta text-foreground-secondary">
                          Keep {firstName(name)} in {workspace.name} with another role.
                        </p>
                        <WorkspaceRolePicker
                          label={`Role for ${name} after removing owner`}
                          roles={REPLACEMENT_WORKSPACE_ROLES}
                          value={editing.role}
                          disabled={busy !== null}
                          onChange={(role) => setRemoving({ ...editing, role })}
                        />
                        <div className="flex justify-end gap-2">
                          <Button variant="ghost" size="sm" disabled={busy !== null} onClick={() => setRemoving(null)}>Cancel</Button>
                          <Button
                            size="sm"
                            pending={busy === "remove-owner"}
                            onClick={async () => {
                              if (await run("remove-owner", () => setWorkspaceMemberRole(workspace.id, editing.userId, editing.role))) setRemoving(null);
                            }}
                          >
                            Remove as owner
                          </Button>
                        </div>
                      </div>
                    ) : null}
                  </li>
                );
              })}
            </ul>
          )}
          <p className="mt-3 type-meta text-foreground-secondary">
            Owners manage members, connectors, and settings in this workspace. Removing an owner keeps them in the workspace with a role you choose. Every workspace keeps at least one owner.
          </p>
          {error && <p role="alert" className="mt-3 type-body text-error">{error}</p>}
        </section>
        <section aria-labelledby="workspace-public-access">
          <SheetSectionHeading id="workspace-public-access" title="Public access" />
          <p className="mt-3 flex items-center gap-2 type-body text-foreground">
            <span aria-hidden="true" className={`h-1.5 w-1.5 rounded-full ${workspace.publicPages ? "bg-foreground" : "bg-foreground-secondary"}`} />
            {workspace.publicPages ? "Pages are public · Anyone with a link can view" : "Pages are private"}
          </p>
          <p className="mt-2 type-meta text-foreground-secondary">
            Owners change this in workspace settings.
            {canOpen ? (
              <>
                {" "}
                <Link href={webPaths.workspacePublicAccess(workspace.id)} className="text-foreground underline underline-offset-4">
                  Open settings
                </Link>
              </>
            ) : null}
          </p>
        </section>
        <section aria-labelledby="workspace-name">
          <SheetSectionHeading
            id="workspace-name"
            title="Name"
            action={<Button variant="secondary" size="sm" aria-label="Rename Workspace" onClick={() => setRenameOpen(true)}>Rename</Button>}
          />
          <p className="mt-2 break-words type-body text-foreground">{workspace.name}</p>
        </section>
      </div>
      {renameOpen && (
        <RenameWorkspaceDialog
          workspace={workspace}
          onClose={() => setRenameOpen(false)}
          onRenamed={invalidate}
        />
      )}
    </Sheet>
  );
}
