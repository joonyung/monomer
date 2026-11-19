import { useMemo, useState } from "react";
import { Building2, ChevronRight, Search } from "lucide-react";
import { CreateOrganizationModal } from "@/components/settings/CreateOrganizationModal";
import { useAccessibleWorkspacesQuery } from "@/components/auth/TenantRouteContext";
import { useSignOut } from "@/components/auth/UserMenu";
import { MonomerLogo } from "@/components/brand/MonomerLogo";
import { Button } from "@/components/ui/Button";
import { Input } from "@/components/ui/Input";
import { StateBlock } from "@/components/ui/StateBlock";
import {
  WORKSPACE_SEARCH_THRESHOLD,
  filterWorkspaceGroups,
  groupWorkspacesByOrganization,
  noWorkspaceAccessMessage,
  organizationRoleLabel,
  type OrganizationWorkspaceGroup,
} from "@/components/workspaces/workspaceDirectory";
import { useAuthStore } from "@/lib/auth/store";
import { roleLabel } from "@/lib/auth/roles";
import { lastOpenedLabel, readWorkspaceVisits, type WorkspaceVisits } from "@/lib/auth/workspace-visits";
import { Link } from "@/lib/navigation";
import { webPaths } from "@/lib/web-paths";

export default function WorkspacesPage() {
  const [createOpen, setCreateOpen] = useState(false);
  const [search, setSearch] = useState("");
  const user = useAuthStore((state) => state.user);
  const organizationMemberships = useAuthStore((state) => state.organizationMemberships);
  const signOut = useSignOut();
  const workspacesQuery = useAccessibleWorkspacesQuery();
  const workspaces = useMemo(() => workspacesQuery.data ?? [], [workspacesQuery.data]);
  const visits = useMemo(() => (user ? readWorkspaceVisits(user.id) : {}), [user]);
  const groups = useMemo(
    () => groupWorkspacesByOrganization(organizationMemberships, workspaces),
    [organizationMemberships, workspaces],
  );
  const visibleGroups = filterWorkspaceGroups(groups, search);
  const showSearch = workspaces.length > WORKSPACE_SEARCH_THRESHOLD;
  const now = new Date();

  return (
    <main className="flex min-h-screen flex-col bg-background px-4 py-6 sm:px-8">
      <MonomerLogo compact className="h-6 w-auto self-start" />
      <div className="mx-auto mt-12 w-full max-w-[720px] pb-12 sm:mt-20">
        <header className="flex flex-wrap items-end justify-between gap-4">
          <h1 className="type-display text-heading">Workspaces</h1>
          <div className="flex w-full flex-wrap items-center gap-2 sm:w-auto">
            {showSearch ? (
              <div className="relative min-w-0 flex-1 sm:w-56 sm:flex-none">
                <Search className="pointer-events-none absolute left-2.5 top-1/2 h-4 w-4 -translate-y-1/2 text-foreground-secondary" aria-hidden="true" />
                <Input
                  type="search"
                  aria-label="Search workspaces"
                  placeholder="Search workspaces"
                  value={search}
                  onChange={(event) => setSearch(event.target.value)}
                  className="pl-8"
                />
              </div>
            ) : null}
            <Button variant="secondary" onClick={() => setCreateOpen(true)}>New Organization</Button>
          </div>
        </header>

        <section className="mt-8" aria-label="Workspaces by Organization">
          {workspacesQuery.isPending ? (
            <StateBlock kind="loading" layout="inline" title="Loading workspaces…" />
          ) : workspacesQuery.isError ? (
            <StateBlock
              kind="error"
              layout="inline"
              title="Workspaces couldn't load."
              description="Check your connection and try again."
              action={<Button variant="secondary" aria-label="Retry loading workspaces" onClick={() => void workspacesQuery.refetch()}>Retry</Button>}
            />
          ) : groups.length === 0 ? (
            <div className="rounded-md border border-dashed border-border">
              <StateBlock
                kind="empty"
                layout="inline"
                icon={Building2}
                headingLevel={2}
                title="You're not in an Organization yet"
                description="Open the invitation link a colleague sent you, or create a new Organization to start with your own workspace."
              />
            </div>
          ) : visibleGroups.length === 0 ? (
            <StateBlock kind="empty" layout="inline" icon={Search} headingLevel={2} title="No workspaces match your search." />
          ) : (
            <div className="space-y-8">
              {visibleGroups.map((group) => (
                <OrganizationSection key={group.membership.organization.id} group={group} visits={visits} now={now} />
              ))}
            </div>
          )}
        </section>

        <p className="mt-10 type-meta text-foreground-secondary">
          Signed in as {user?.email ?? "unknown"} ·{" "}
          <button
            type="button"
            onClick={() => void signOut()}
            className="underline underline-offset-4 hover:text-foreground"
          >
            Sign out
          </button>
        </p>
        {createOpen ? <CreateOrganizationModal onClose={() => setCreateOpen(false)} /> : null}
      </div>
    </main>
  );
}

function OrganizationSection({ group, visits, now }: { group: OrganizationWorkspaceGroup; visits: WorkspaceVisits; now: Date }) {
  const { membership, workspaces } = group;
  const headingId = `organization-${membership.organization.id}`;
  return (
    <section aria-labelledby={headingId} data-testid={`workspaces-organization-${membership.organization.id}`}>
      <div className="flex flex-wrap items-baseline justify-between gap-x-4 gap-y-1 border-b border-border pb-2">
        <h2 id={headingId} className="min-w-0 truncate type-item text-heading">{membership.organization.displayName}</h2>
        <p className="type-meta text-foreground-secondary">
          {organizationRoleLabel(membership.role)}
          {membership.role === "ADMIN" ? (
            <>
              {" · "}
              <Link href={webPaths.organizationSettings(membership.organization.id)} className="underline underline-offset-4 hover:text-foreground">
                Organization settings
              </Link>
            </>
          ) : null}
        </p>
      </div>
      {workspaces.length === 0 ? (
        <p className="py-4 type-body text-foreground-secondary">{noWorkspaceAccessMessage(membership.role)}</p>
      ) : (
        <ul className="divide-y divide-border-light">
          {workspaces.map((workspace) => {
            const openedAt = visits[workspace.id];
            return (
              <li key={workspace.id}>
                <Link
                  href={webPaths.workspace(workspace.id)}
                  data-testid={`workspace-row-${workspace.id}`}
                  className="group grid grid-cols-[minmax(0,1fr)_auto] items-center gap-x-3 px-1 py-3 transition-colors hover:bg-surface/60 sm:grid-cols-[minmax(0,1fr)_7rem_8rem_auto]"
                >
                  <span className="min-w-0">
                    <span className="block truncate type-control text-foreground">{workspace.name}</span>
                    <span className="block truncate type-status text-foreground-secondary sm:hidden">
                      {[roleLabel(workspace.role), openedAt ? lastOpenedLabel(openedAt, now) : null].filter(Boolean).join(" · ")}
                    </span>
                  </span>
                  <span className="hidden type-meta text-foreground sm:block">{roleLabel(workspace.role)}</span>
                  <span className="hidden type-meta text-foreground-secondary sm:block">
                    {openedAt ? lastOpenedLabel(openedAt, now) : ""}
                  </span>
                  <ChevronRight className="h-4 w-4 text-foreground-secondary" aria-hidden="true" />
                </Link>
              </li>
            );
          })}
        </ul>
      )}
    </section>
  );
}
