import { useState } from "react";
import { Link } from "@tanstack/react-router";
import type { DashboardDefinition, DashboardDraftResponse } from "@/api/dashboard";
import { DashboardBuilderSurface } from "@/pages/dashboard/builder/DashboardBuilderSurface";
import { dashboardViewerFixtureResult } from "@/pages/dev/dashboard-viewer-fixture/page";

function initialDefinition(definition: DashboardDefinition): DashboardDefinition {
  const chart = definition.widgets.chart;
  return {
    ...definition,
    parameters: {},
    widgets: {
      ...definition.widgets,
      chart: chart.type === "CHART" ? { ...chart, module: { ...chart.module, source: chart.module.source.replace("animation: false,", 'animation: false, backgroundColor: "#fdfdfe",') } } : chart,
      footer: { type: "TEXT", content: "### Additional context\n\nScroll retention fixture." },
    },
    layout: { sections: [
      { id: "data", height: { mode: "SCALED", scale: 1.2 }, root: { type: "STACK", id: "data-stack", direction: "ROW", separation: "GAP", children: [
        { weight: 2, node: { type: "WIDGET", id: "chart-item", widgetId: "chart" } },
        { weight: 1, node: { type: "WIDGET", id: "table-item", widgetId: "table" } },
      ] } },
      { id: "controls", height: { mode: "CONTENT" }, root: { type: "STACK", id: "controls-stack", direction: "ROW", separation: "DIVIDER", children: [
        { weight: 1, node: { type: "WIDGET", id: "input-item", widgetId: "input" } },
        { weight: 2, node: { type: "WIDGET", id: "text-item", widgetId: "text" } },
      ] } },
      { id: "footer", height: { mode: "CONTENT" }, root: { type: "WIDGET", id: "footer-item", widgetId: "footer" } },
    ] },
  };
}

export function ChangeHighlightFixture({ initialDraft }: { initialDraft: DashboardDraftResponse }) {
  const [draft, setDraft] = useState(() => ({ ...initialDraft, definition: initialDefinition(initialDraft.definition) }));
  const updateDefinition = (update: (definition: DashboardDefinition) => DashboardDefinition) => setDraft(current => ({
    ...current, authoringRevision: current.authoringRevision + 1, definition: update(current.definition),
  }));
  const resize = (id: string) => updateDefinition(definition => ({ ...definition, layout: { sections: definition.layout.sections.map(section => section.id === id ? { ...section, height: { mode: "SCALED", scale: (section.height.mode === "SCALED" ? section.height.scale : 1) + 0.1 } } : section) } }));
  const actions: Record<string, () => void> = {
    "Update text": () => updateDefinition(definition => ({ ...definition, widgets: { ...definition.widgets, text: { type: "TEXT", content: `### Updated orders\n\nSnapshot revision ${draft.authoringRevision + 1}.` } } })),
    "Update chart": () => updateDefinition(definition => {
      const chart = definition.widgets.chart;
      return { ...definition, widgets: { ...definition.widgets, chart: chart.type === "CHART" ? { ...chart, module: { ...chart.module, source: `${chart.module.source}\n` } } : chart } };
    }),
    "Add widget": () => updateDefinition(definition => ({ ...definition, widgets: { ...definition.widgets, added: { type: "TEXT", content: "### New widget" } }, layout: { sections: [...definition.layout.sections, { id: "added", height: { mode: "CONTENT" }, root: { type: "WIDGET", id: "added-item", widgetId: "added" } }] } })),
    "Resize GAP section": () => resize("data"),
    "Resize DIVIDER section": () => updateDefinition(definition => ({ ...definition, layout: { sections: definition.layout.sections.map(section => section.id === "controls" && section.root.type === "STACK" ? { ...section, root: { ...section.root, children: section.root.children.map((child, index) => ({ ...child, weight: index === 0 ? 2 : 1 })) } } : section) } })),
    "Unchanged snapshot": () => setDraft(current => structuredClone(current)),
    "Revision only": () => setDraft(current => ({ ...current, authoringRevision: current.authoringRevision + 1 })),
    "Run only": () => setDraft(current => ({ ...current, recentQueryRuns: current.recentQueryRuns.map(run => ({ ...run, updatedAt: "2026-08-24T09:00:00Z" })) })),
    "Switch dashboard": () => setDraft(current => ({ ...current, dashboardId: `${current.dashboardId}-next` })),
    "Switch workspace": () => setDraft(current => ({ ...current, workspaceId: `${current.workspaceId}-next` })),
  };
  return <div className="flex h-screen min-h-0 flex-col bg-background">
    <nav aria-label="Snapshot simulation" className="flex shrink-0 flex-wrap gap-2 border-b border-border p-3">
      {Object.entries(actions).map(([label, action]) => <button key={label} type="button" className="rounded-[4px] border border-border px-2 py-1 text-[11px]" onClick={action}>{label}</button>)}
      <Link to="/dev/dashboard-builder-fixture" search={{ scene: "controls" }} className="rounded-[4px] border border-border px-2 py-1 text-[11px]">Leave chart fixture</Link>
    </nav>
    {/* The applied definition follows each snapshot so the review summary stays still while highlights are measured. */}
    <DashboardBuilderSurface draft={draft} principalId={null} appliedDefinition={draft.definition} resultsByRunId={{ [dashboardViewerFixtureResult.queryRunId]: dashboardViewerFixtureResult }} onRefresh={() => undefined} onApply={() => undefined} onDiscard={() => undefined} onParameterSet={() => undefined} onCancel={() => undefined} />
  </div>;
}
