import { ROLE_DESCRIPTIONS, roleLabel } from "@/lib/auth/roles";
import type { WorkspaceRole } from "@/lib/auth/types";

// The one Workspace role control: every role choice shows the selected role's consequence. Workspace
// member sheets, Organization member sheets, and owner replacement all use it.
export function WorkspaceRolePicker<Role extends WorkspaceRole>({
  label,
  roles,
  value,
  onChange,
  disabled,
  hideLabel = false,
}: {
  label: string;
  // When a section heading already names the choice, the legend stays for assistive technology only.
  hideLabel?: boolean;
  roles: readonly Role[];
  value: Role;
  onChange: (role: Role) => void;
  disabled: boolean;
}) {
  return (
    <fieldset disabled={disabled} className="my-4">
      <legend className={hideLabel ? "sr-only" : "mb-2 type-body"}>{label}</legend>
      <div className={`grid grid-cols-2 gap-1 rounded-[4px] border border-border p-1 ${roles.length === 4 ? "sm:grid-cols-4" : "sm:grid-cols-3"}`}>
        {roles.map((role) => (
          <button
            type="button"
            key={role}
            aria-pressed={value === role}
            onClick={() => onChange(role)}
            className={`min-h-9 rounded-[4px] px-2 type-control underline-offset-4 focus-visible:underline disabled:cursor-not-allowed ${
              value === role ? "bg-surface text-heading" : "text-foreground-secondary enabled:hover:text-foreground"
            }`}
          >
            {roleLabel(role)}
          </button>
        ))}
      </div>
      <p className="mt-2 type-body text-foreground-secondary">{ROLE_DESCRIPTIONS[value]}</p>
    </fieldset>
  );
}

export const REPLACEMENT_WORKSPACE_ROLES: readonly Exclude<WorkspaceRole, "OWNER">[] = ["VIEWER", "EXPLORER", "EDITOR"];
