import { createContext, useContext } from "react";
import type { LucideIcon } from "lucide-react";

export type SettingsIdentityKind = "organization" | "workspace" | "user";

export interface SettingsNavigationItem {
  id: string;
  label: string;
  href: string;
  icon: LucideIcon;
  // A count or a short status shown at the item's right edge, such as a member count or "Off".
  count?: number;
  status?: string;
}

// Organization, Workspace, and User settings share one settings sidebar. Each shell describes its
// identity and sections with this model; the application sidebar renders it instead of the
// Workspace navigation while the shell is mounted.
export interface SettingsNavigationModel {
  label: string;
  groupLabel: string;
  testIdPrefix: string;
  identity: { kind: SettingsIdentityKind; name: string; meta: string };
  items: SettingsNavigationItem[];
  activeId: string;
}

export const SettingsNavigationContext = createContext<SettingsNavigationModel | null>(null);

export function useSettingsNavigation(): SettingsNavigationModel | null {
  return useContext(SettingsNavigationContext);
}
