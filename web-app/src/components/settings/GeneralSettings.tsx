import { useState } from "react";
import { ArrowRight, TriangleAlert } from "lucide-react";
import { Button } from "@/components/ui/Button";
import { Input } from "@/components/ui/Input";
import { StateBlock } from "@/components/ui/StateBlock";
import { Link } from "@/lib/navigation";
import { useOrganizationRouteContext } from "@/components/auth/TenantRouteContext";
import { fetchMe, updateOrganizationDisplayName } from "@/lib/auth/graphql";
import { useAuthStore } from "@/lib/auth/store";
import { webPaths } from "@/lib/web-paths";
import { SettingsDialog } from "./SettingsDialog";
import { OrganizationSettingsShell } from "./OrganizationSettingsShell";
import { CopyButton, MemberAvatar, SettingsField } from "./SettingsPrimitives";
import { useOrganizationMembersQuery, useOrganizationUsageQuery } from "./organizationQueries";
import {
  USAGE_COUNTERS,
  formatSettingsDate,
  planLabel,
  usageRatio,
  usageWarning,
} from "./organizationSettingsModel";

const SECTION_HEADING = "type-section text-heading";

export function GeneralSettings() {
  return (
    <OrganizationSettingsShell section="general" title="General">
      <div className="space-y-12">
        <ProfileSection />
        <UsageSection />
        <AdminsSection />
      </div>
    </OrganizationSettingsShell>
  );
}

function ProfileSection() {
  const { organization, organizationId } = useOrganizationRouteContext();
  const [renameOpen, setRenameOpen] = useState(false);
  return (
    <section aria-labelledby="organization-profile-heading">
      <h2 id="organization-profile-heading" className={SECTION_HEADING}>Profile</h2>
      <dl className="mt-4 divide-y divide-border-light border-y border-border">
        <SettingsField
          label="Organization name"
          action={<Button variant="secondary" size="sm" aria-label="Rename Organization" onClick={() => setRenameOpen(true)}>Rename</Button>}
        >
          {organization.displayName}
        </SettingsField>
        <SettingsField
          label="Organization ID"
          valueClassName="break-all type-mono"
          action={<CopyButton value={organizationId} label="Copy Organization ID" copiedAnnouncement="Organization ID copied" failedAnnouncement="Couldn't copy. Select the ID and copy it." />}
        >
          {organizationId}
        </SettingsField>
        <SettingsField label="Created" valueClassName="type-body tabular-nums">
          <time dateTime={organization.createdAt}>{formatSettingsDate(organization.createdAt)}</time>
        </SettingsField>
      </dl>
      {renameOpen && <RenameSettingsDialog onClose={() => setRenameOpen(false)} />}
    </section>
  );
}

function RenameSettingsDialog({ onClose }: { onClose: () => void }) {
  const { organization, organizationId } = useOrganizationRouteContext();
  const [draft, setDraft] = useState(organization.displayName);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const trimmed = draft.trim();
  const save = async () => {
    if (!trimmed || busy) return;
    setBusy(true);
    setError(null);
    try {
      await updateOrganizationDisplayName(organizationId, trimmed);
      const me = await fetchMe(undefined, false);
      useAuthStore.getState().setMe({ user: me.user, organizationMemberships: me.organizationMemberships });
      onClose();
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "Couldn't rename the Organization.");
    } finally {
      setBusy(false);
    }
  };
  return (
    <SettingsDialog
      title="Rename Organization"
      size="sm"
      onClose={onClose}
      busy={busy}
      footer={
        <>
          <Button variant="ghost" disabled={busy} onClick={onClose}>Cancel</Button>
          <Button pending={busy} disabled={!trimmed || trimmed === organization.displayName} onClick={() => void save()}>
            Save
          </Button>
        </>
      }
    >
      <form onSubmit={(event) => { event.preventDefault(); void save(); }}>
        <Input
          id="organization-name"
          label="Organization name"
          autoFocus
          value={draft}
          onChange={(event) => setDraft(event.target.value)}
          disabled={busy}
          helperText="Shown in invitations and the workspace switcher."
        />
      </form>
      {error && <p role="alert" className="mt-4 type-body text-error">{error}</p>}
    </SettingsDialog>
  );
}

function UsageSection() {
  const { organization, organizationId } = useOrganizationRouteContext();
  const query = useOrganizationUsageQuery(organizationId);
  const limits = query.data?.limits ?? null;
  return (
    <section aria-labelledby="organization-usage-heading">
      <div className="flex flex-wrap items-baseline justify-between gap-x-4 gap-y-1">
        <h2 id="organization-usage-heading" className={SECTION_HEADING}>Usage</h2>
        {limits ? (
          <p className="type-meta text-foreground-secondary">{planLabel(organization.plan)} limits during the public beta</p>
        ) : null}
      </div>
      {query.isPending ? (
        <StateBlock kind="loading" layout="inline" title="Loading usage…" />
      ) : query.isError ? (
        <StateBlock
          kind="error"
          layout="inline"
          title="Couldn't load usage."
          action={<Button variant="secondary" size="sm" onClick={() => void query.refetch()}>Retry</Button>}
        />
      ) : (
        <ul className="mt-4 grid divide-y divide-border-light border-y border-border sm:grid-cols-3 sm:divide-x sm:divide-y-0">
          {USAGE_COUNTERS.map((counter) => (
            <UsageCounter
              key={counter.key}
              label={counter.label}
              used={query.data.usage[counter.key]}
              limit={limits ? limits[counter.key] : null}
              warning={usageWarning(query.data.usage[counter.key], limits ? limits[counter.key] : null, counter)}
            />
          ))}
        </ul>
      )}
    </section>
  );
}

function UsageCounter({ label, used, limit, warning }: {
  label: string;
  used: number;
  limit: number | null;
  warning: string | null;
}) {
  return (
    <li className="py-5 sm:px-5 sm:first:pl-0">
      <p className="type-meta text-foreground-secondary">{label}</p>
      <p className="mt-2 flex items-baseline gap-1.5">
        <span className="type-kpi text-[28px] text-heading">{used}</span>
        {limit !== null ? (
          <span className="type-body tabular-nums text-foreground-secondary">
            <span aria-hidden="true">/ </span>
            <span className="sr-only">of </span>
            {limit}
          </span>
        ) : null}
      </p>
      {limit !== null ? (
        <div aria-hidden="true" className="mt-3 h-0.5 w-full bg-surface-hover">
          <div className="h-full bg-foreground" style={{ width: `${usageRatio(used, limit) * 100}%` }} />
        </div>
      ) : null}
      {warning ? (
        <p className="mt-2 flex items-center gap-1.5 type-meta text-warning">
          <TriangleAlert className="h-3.5 w-3.5 flex-shrink-0" aria-hidden="true" />
          {warning}
        </p>
      ) : null}
    </li>
  );
}

function AdminsSection() {
  const { organizationId } = useOrganizationRouteContext();
  const principalId = useAuthStore((state) => state.user?.id ?? "");
  const query = useOrganizationMembersQuery(organizationId);
  const admins = (query.data ?? []).filter((member) => member.role === "ADMIN");
  return (
    <section aria-labelledby="organization-admins-heading">
      <div className="flex flex-wrap items-baseline justify-between gap-x-4 gap-y-1">
        <h2 id="organization-admins-heading" className={SECTION_HEADING}>Admins</h2>
        <Link
          href={webPaths.organizationMembers(organizationId)}
          className="inline-flex items-center gap-1 type-control text-foreground-secondary underline-offset-4 hover:text-foreground hover:underline"
        >
          Manage in Members
          <ArrowRight className="h-3.5 w-3.5" aria-hidden="true" />
        </Link>
      </div>
      <p className="mt-2 max-w-2xl type-body text-foreground-secondary">
        Admins manage people and workspaces. Being an Admin doesn&apos;t grant access to workspace data.
      </p>
      {query.isPending ? (
        <StateBlock kind="loading" layout="inline" title="Loading admins…" />
      ) : query.isError ? (
        <StateBlock
          kind="error"
          layout="inline"
          title="Couldn't load admins."
          action={<Button variant="secondary" size="sm" onClick={() => void query.refetch()}>Retry</Button>}
        />
      ) : (
        <ul className="mt-4 divide-y divide-border-light border-y border-border" aria-labelledby="organization-admins-heading">
          {admins.map((admin) => (
            <li key={admin.user.id} className="flex items-center gap-3 py-3">
              <MemberAvatar name={admin.user.displayName} />
              <div className="min-w-0 flex-1">
                <p className="truncate type-body text-foreground">
                  {admin.user.displayName}
                  {admin.user.id === principalId ? <span className="text-foreground-secondary"> · You</span> : null}
                </p>
                <p className="truncate type-meta text-foreground-secondary">{admin.user.email}</p>
              </div>
              <p className="hidden flex-shrink-0 type-meta tabular-nums text-foreground-secondary sm:block">
                Joined <time dateTime={admin.joinedAt}>{formatSettingsDate(admin.joinedAt)}</time>
              </p>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
