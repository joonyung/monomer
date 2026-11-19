import { useState } from "react";
import { useSearch } from "@tanstack/react-router";
import { notFound } from "@/lib/navigation";
import { WorkspaceRouteFixtureProvider } from "@/components/auth/TenantRouteContext";
import { WorkspaceSettingsDemo } from "@/pages/workspace/settings/demo/WorkspaceSettingsDemo";
import type { WorkspaceAccess } from "@/lib/auth/types";
import { MainLayout } from "@/components/layout/MainLayout";
import { SemanticsExperience, type SemanticsView } from "@/components/semantics/SemanticsExperience";
import { METRIC_FIXTURES, SEMANTIC_MODEL_FIXTURES } from "@/components/semantics/semanticsFixtures";
import { MetricDetailActions } from "@/components/metric/MetricDetailActions";
import { Card } from "@/components/ui/Card";
import { Badge } from "@/components/ui/Badge";
import { MetricIcon } from "@/lib/utils/metric-icon";

const ORGANIZATION_ID = "71000000-0000-0000-0000-000000000001";
const WORKSPACE_ID = "71000000-0000-0000-0000-000000000002";
const accessByViewer: Record<"explorer" | "owner" | "editor", WorkspaceAccess> = {
  explorer: { granted: true, organizationId: ORGANIZATION_ID, role: "EXPLORER" as const, },
  owner: { granted: true, organizationId: ORGANIZATION_ID, role: "OWNER" as const, },
  editor: { granted: true, organizationId: ORGANIZATION_ID, role: "EDITOR" as const, },
};

export default function RoleSettingsFixturePage() {
  if (!import.meta.env.DEV) notFound();
  const { viewer, surface } = useSearch({ from: "/dev/role-settings-fixture" });
  const [selectedMemberId, setSelectedMemberId] = useState<string | null>(null);
  if (surface === "settings") {
    return <WorkspaceSettingsDemo viewer={viewer} surface="members" selectedMemberId={selectedMemberId} onSelectMember={setSelectedMemberId} />;
  }
  return <WorkspaceRouteFixtureProvider workspace={{ id: WORKSPACE_ID, organizationId: ORGANIZATION_ID, publicPages: false, reportingTimeZone: "UTC", name: "Northstar Analytics" }} access={accessByViewer[viewer]}>
    {surface === "semantics" ? <SemanticsScopeFixture /> : <MetricDetailScopeFixture />}
  </WorkspaceRouteFixtureProvider>;
}

function SemanticsScopeFixture() {
  const [view, setView] = useState<SemanticsView>("metrics");
  return <MainLayout><SemanticsExperience workspaceId={WORKSPACE_ID} view={view} searchQuery="" semanticModels={{ items: SEMANTIC_MODEL_FIXTURES, loading: false, error: null }} metrics={{ items: METRIC_FIXTURES, loading: false, error: null }} modelPage={0} modelPageSize={5} onViewChange={setView} onSearchQueryChange={() => undefined} onModelPageChange={() => undefined} /></MainLayout>;
}

function MetricDetailScopeFixture() {
  const metric = METRIC_FIXTURES[1];
  return <MainLayout><div className="h-full overflow-y-auto bg-surface/30"><div className="mx-auto max-w-[1200px] p-8"><div className="mb-6 flex flex-wrap items-start justify-between gap-4"><div className="flex min-w-0 items-start gap-4"><span className="flex h-14 w-14 flex-none items-center justify-center rounded-[6px] border border-border bg-background text-foreground"><MetricIcon type={metric.type} className="h-7 w-7" /></span><div><div className="flex flex-wrap items-center gap-2"><h1 className="text-[32px] font-light tracking-[-0.64px] text-heading">{metric.label}</h1><Badge variant="success">Semantic editor</Badge></div><p className="mt-2 max-w-2xl text-sm text-foreground-secondary">{metric.description}</p></div></div><MetricDetailActions exploreHref="/workspaces/workspace-1/explore?metric=revenue" onEdit={() => undefined} /></div><Card padding="lg"><h2 className="text-[22px] font-light text-heading">Workspace semantic edit check</h2><p className="mt-2 text-sm leading-6 text-foreground-secondary">The Editor role can edit every Semantic Model, Metric, and Time Spine in this Workspace.</p></Card></div></div></MainLayout>;
}
