import type { AccessibleWorkspace, Workspace } from "./types";

/** The seeded sample Dashboards in the order the Quick start opens them (2026-10-16 sample follow-ups, D14). */
const SAMPLE_SEED_DASHBOARD_TITLES = ["Board metrics", "Revenue movements", "Customer segments", "Product engagement"] as const;

export function visibleWorkspaces<T extends Pick<Workspace, "sample">>(workspaces: T[]): T[] {
  return workspaces.filter((workspace) => workspace.sample?.state !== "PROVISIONING");
}

/**
 * Puts the first Dashboard titled exactly like each seed first, in seed order. Every other Dashboard, including another
 * one with a seed's title, keeps the API order.
 */
export function sampleDashboardsInSeedOrder<T extends { title: string }>(dashboards: readonly T[]): T[] {
  const seeds = SAMPLE_SEED_DASHBOARD_TITLES.flatMap((title) => {
    const seed = dashboards.find((dashboard) => dashboard.title === title);
    return seed ? [seed] : [];
  });
  return [...seeds, ...dashboards.filter((dashboard) => !seeds.includes(dashboard))];
}

/**
 * Where the sample banner sends a reader to connect their own warehouse: a visible, non-sample Workspace in the
 * sample's Organization, preferring one the reader owns. Null hides the link; it never leads to another Organization.
 */
export function warehouseWorkspace(workspaces: AccessibleWorkspace[], sample: Pick<Workspace, "id" | "organizationId">): AccessibleWorkspace | null {
  const candidates = visibleWorkspaces(workspaces).filter((workspace) => workspace.id !== sample.id && !workspace.sample && workspace.organizationId === sample.organizationId);
  return candidates.find((workspace) => workspace.role === "OWNER") ?? candidates[0] ?? null;
}
