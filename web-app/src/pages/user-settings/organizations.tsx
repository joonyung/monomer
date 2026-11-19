import { ArrowRight, Building2 } from "lucide-react";
import { StateBlock } from "@/components/ui/StateBlock";
import { Link } from "@/lib/navigation";
import { useAuthStore } from "@/lib/auth/store";
import { webPaths } from "@/lib/web-paths";
import { UserSettingsShell } from "./shell";

export default function OrganizationsPage() {
  return (
    <UserSettingsShell section="organizations" title="Organizations">
      <OrganizationList />
    </UserSettingsShell>
  );
}

function OrganizationList() {
  const memberships = useAuthStore((state) => state.organizationMemberships);
  if (memberships.length === 0) {
    return (
      <div className="border-y border-border">
        <StateBlock kind="empty" layout="inline" icon={Building2} title="You don't belong to any Organization yet." />
      </div>
    );
  }
  return (
    <ul className="max-w-[760px] divide-y divide-border-light border-y border-border" aria-label="Your Organizations">
      {memberships.map((membership) => {
        const admin = membership.role === "ADMIN";
        return (
          <li key={membership.organization.id} className="flex flex-wrap items-center gap-x-4 gap-y-2 py-3">
            <span className="min-w-0 flex-1 truncate type-body text-foreground">{membership.organization.displayName}</span>
            <span className="type-body text-foreground-secondary">{admin ? "Admin" : "Member"}</span>
            {admin ? (
              <Link
                href={webPaths.organizationSettings(membership.organization.id)}
                aria-label={`Organization settings for ${membership.organization.displayName}`}
                className="inline-flex items-center gap-1 type-control text-foreground-secondary underline-offset-4 hover:text-foreground hover:underline"
              >
                Organization settings
                <ArrowRight className="h-3.5 w-3.5" aria-hidden="true" />
              </Link>
            ) : null}
          </li>
        );
      })}
    </ul>
  );
}
