import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";
import type { ActivityItem } from "@/api/overview";
import { seededDefinitionActivity } from "@/pages/overview/OverviewActivity";
import { ActivityView } from "./WorkspaceActivity";

const alex = "019e8000-0000-7000-8000-000000000b02";
const mina = "019e8000-0000-7000-8000-000000000b03";
const claude = { clientId: "https://claude.ai/oauth/mcp-oauth-client-metadata", name: "Claude", verifiedHost: "claude.ai" };
const now = Date.parse("2026-10-04T00:05:00Z");

const agentRun: ActivityItem = {
  id: "019e8000-0000-7000-8000-000000000001", kind: "EXPLORE", title: "orders · SELECT region", description: "Explore", state: "SUCCEEDED",
  startedAt: "2026-10-04T00:00:00Z", occurredAt: "2026-10-04T00:01:00Z", actorId: mina, actorName: "Mina Park", actorKind: "USER",
  credentialSurface: "AGENT", agentApp: claude, failureReason: null, href: "/workspaces/w/explore?run=019e8000-0000-7000-8000-000000000001",
};
const webChange: ActivityItem = {
  ...agentRun, id: "019e8000-0000-7000-8000-000000000002", kind: "METRIC", title: "Revenue", description: "Metric saved · revenue",
  state: "UPDATED", startedAt: null, actorId: alex, actorName: "Alex Morgan", credentialSurface: "WEB", agentApp: null,
  href: "/workspaces/w/metrics/019e8000-0000-7000-8000-000000000002",
};

function render(overrides: Partial<Parameters<typeof ActivityView>[0]> = {}) {
  return renderToStaticMarkup(<ActivityView
    scope="all"
    onScopeChange={() => undefined}
    items={[agentRun, webChange]}
    clock={now}
    sampleWorkspace={false}
    status="ready"
    notice={null}
    moreRunning={false}
    hasMore={false}
    loadingMore={false}
    onRetry={() => undefined}
    onLoadMore={() => undefined}
    {...overrides}
  />);
}

describe("Activity page list", () => {
  it("lists each row with the person, the agent app badge, its state, and a link to the work", () => {
    const markup = render({ hasMore: true });
    expect(markup).toContain('data-change-id="EXPLORE:019e8000-0000-7000-8000-000000000001"');
    expect(markup).toContain('href="/workspaces/w/explore?run=019e8000-0000-7000-8000-000000000001"');
    expect(markup).toMatch(/Mina Park.*via Claude \(claude\.ai\)/);
    expect(markup).toContain('data-agent-badge="claude"');
    expect(markup).toContain("Succeeded");
    expect(markup).toContain("4m ago");
    expect(markup).toMatch(/Revenue.*Metric saved · revenue.*Alex Morgan/);
    expect(markup).toContain(">Load more activity<");
  });

  it("offers only the scope tabs with the current scope pressed", () => {
    const agents = render({ scope: "agents" });
    expect(agents).toMatch(/<button type="button" aria-pressed="true">Agents<\/button>/);
    expect(agents).toMatch(/<button type="button" aria-pressed="false">All<\/button>/);
    expect(agents).not.toContain("<select");
  });

  it("shows loading, failure, and empty states that say what to do next", () => {
    expect(render({ status: "loading" })).toContain("Loading activity…");
    const failed = render({ status: "error" });
    expect(failed).toContain('role="alert"');
    expect(failed).toContain("Activity couldn’t load.");
    expect(failed).toContain(">Retry<");
    expect(failed).not.toContain("Load more activity");
    const empty = render({ items: [] });
    expect(empty).toContain("No activity yet");
    expect(empty).not.toContain("Show all activity");
    const filtered = render({ items: [], scope: "agents" });
    expect(filtered).toContain("No activity matches these filters");
    expect(filtered).toContain(">Show all activity<");
    expect(render({ items: [], hasMore: true })).toContain("No activity in loaded results");
  });

  it("says when sources are unavailable or more work is running than listed", () => {
    const markup = render({ notice: "Some activity sources are unavailable.", moreRunning: true });
    expect(markup).toContain("Some activity sources are unavailable.");
    expect(markup).toContain("More work is running than this page lists.");
  });
});

it("shows each create, edit and delete change separately, with deletion linking to history", () => {
  const changes: ActivityItem[] = [
    { ...webChange, id: "019e8000-0000-7000-8000-000000000057", state: "CREATED", description: "Metric created · revenue" },
    { ...webChange, id: "019e8000-0000-7000-8000-000000000058", state: "UPDATED", description: "Metric edited · revenue" },
    { ...webChange, id: "019e8000-0000-7000-8000-000000000059", state: "DELETED", description: "Metric deleted · revenue", actorId: mina, actorName: "Mina Park", credentialSurface: "AGENT", agentApp: claude, href: "/workspaces/w/metrics/original-id/history" },
  ];
  const markup = render({ items: changes });
  expect(markup.match(/data-change-id=/g)).toHaveLength(3);
  expect(markup).toContain("Metric created · revenue");
  expect(markup).toContain("Metric edited · revenue");
  expect(markup).toContain("Metric deleted · revenue");
  expect(markup).toContain('href="/workspaces/w/metrics/original-id/history"');
  expect(markup).toContain("Mina Park");
  expect(markup).toContain("via Claude");
  expect(markup).not.toContain("Created");
  expect(markup).not.toContain("Deleted");
});

it("keeps Dashboard create, Apply and deletion records and links deleted rows to History", () => {
  const changes: ActivityItem[] = [
    { ...webChange, id: "dashboard-created", kind: "DASHBOARD", state: "CREATED", description: "Dashboard created" },
    { ...webChange, id: "dashboard-applied", kind: "DASHBOARD", state: "APPLIED", description: "Dashboard edited", credentialSurface: "AGENT", agentApp: claude },
    { ...webChange, id: "dashboard-deleted", kind: "DASHBOARD", state: "DELETED", description: "Dashboard deleted", href: "/workspaces/w/dashboards/original-id/history" },
  ];
  const markup = render({ items: changes });
  expect(markup.match(/data-change-id=/g)).toHaveLength(3);
  expect(markup).toContain("Dashboard created"); expect(markup).toContain("Dashboard edited"); expect(markup).toContain("Dashboard deleted");
  expect(markup).toContain('href="/workspaces/w/dashboards/original-id/history"');
  expect(markup).not.toContain("Unknown status");
  expect(markup).not.toContain(">Created<"); expect(markup).not.toContain(">Deleted<");
});

it("names Monomer for a sample Workspace's definition change with no recorded person or surface, and keeps runs and Pages unrecorded", () => {
  const unrecorded = { actorId: null, actorName: null, actorKind: "UNRECORDED", credentialSurface: null, agentApp: null } as const;
  const seeded: ActivityItem[] = [
    { ...webChange, ...unrecorded, id: "seeded-metric", state: "CREATED", description: "Metric created · revenue" },
    { ...webChange, ...unrecorded, id: "seeded-model", kind: "SEMANTIC_MODEL", state: "CREATED", description: "Model created · orders" },
    { ...webChange, ...unrecorded, id: "seeded-dashboard", kind: "DASHBOARD", state: "CREATED", description: "Dashboard created" },
  ];
  const others: ActivityItem[] = [
    { ...agentRun, ...unrecorded, id: "unrecorded-run" },
    { ...agentRun, ...unrecorded, id: "unrecorded-session", kind: "DASHBOARD_SESSION" },
    { ...webChange, ...unrecorded, id: "unrecorded-page", kind: "PAGE", state: "UPDATED", description: "Page edited" },
    { ...webChange, ...unrecorded, id: "agent-metric", credentialSurface: "AGENT", agentApp: claude },
  ];
  const sample = render({ items: [...seeded, ...others], sampleWorkspace: true });
  expect(sample.match(/<span class="ov-avatar ov-avatar-system" aria-hidden="true">M<\/span><span class="ov-person-name">Monomer<\/span>/g)).toHaveLength(3);
  expect(sample.match(/>Not recorded</g)).toHaveLength(others.length);
  expect(render({ items: seeded }).match(/>Not recorded</g)).toHaveLength(seeded.length);
  expect(render({ items: seeded })).not.toContain("Monomer");
  for (const item of seeded) expect(seededDefinitionActivity(item, true)).toBe(true);
  for (const item of [...others, webChange]) expect(seededDefinitionActivity(item, true)).toBe(false);
  expect(seededDefinitionActivity(seeded[0], false)).toBe(false);
});
