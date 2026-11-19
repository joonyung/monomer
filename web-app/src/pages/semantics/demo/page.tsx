import { useNavigate, useSearch } from "@tanstack/react-router";
import { notFound } from "@/lib/navigation";
import { MainLayout } from "@/components/layout/MainLayout";
import {
  SemanticsExperience,
  type SemanticsView,
} from "@/components/semantics/SemanticsExperience";
import {
  METRIC_FIXTURES,
  CONNECTOR_FIXTURES,
  SEMANTIC_MODEL_FIXTURES,
  SEMANTICS_FIXTURE_WORKSPACE_ID,
  TIME_SPINE_FIXTURES,
} from "@/components/semantics/semanticsFixtures";
import { Link } from "@/lib/navigation";

const SCENES = ["ready", "empty", "loading", "error"] as const;

export default function SemanticsDemoPage() {
  if (!import.meta.env.DEV) notFound();
  const search = useSearch({ from: "/dev/semantics-demo" });
  const navigate = useNavigate({ from: "/dev/semantics-demo" });
  const ready = search.scene === "ready";
  const loading = search.scene === "loading";
  const error = search.scene === "error" ? "Fixture service unavailable" : null;
  const updateSearch = (patch: {
    modelFilter?: string;
    kindFilter?: ""|"simple"|"ratio"|"derived"|"cumulative"|"conversion";
    statusFilter?: ""|"broken"|"connector";
    sort?: "name"|"updated";
    view?: SemanticsView;
    connectorId?: string;
    q?: string;
    modelPage?: number;
  }) => {
    void navigate({ to: ".", replace: true, search: (current) => ({ ...current, ...patch }) });
  };

  return (
    <MainLayout
      workspaceFixture={{
        workspace: {
          id: SEMANTICS_FIXTURE_WORKSPACE_ID,
          organizationId: "71000000-0000-0000-0000-000000000002",
          publicPages: false, reportingTimeZone: "UTC", name: "Northstar Analytics",
        },
        access: {
          granted: true,
          organizationId: "71000000-0000-0000-0000-000000000002",
          role: "EDITOR" as const, },
      }}
    >
      <div className="flex h-full min-h-0 flex-col">
        <nav className="flex flex-none items-center gap-1 border-b border-border bg-background px-3 py-2" aria-label="Semantics fixture scenes">
          <span className="type-group mr-2 text-foreground-secondary">Fixture</span>
          {SCENES.map((scene) => (
            <Link
              key={scene}
              href={`/dev/semantics-demo?scene=${scene}&view=${search.view}`}
              className={`type-status rounded-[4px] px-2 py-1 focus:outline-none ${
                search.scene === scene ? "bg-accent text-accent-foreground" : "text-foreground-secondary hover:bg-surface"
              }`}
            >
              {scene}
            </Link>
          ))}
        </nav>
        <div className="min-h-0 flex-1">
          <SemanticsExperience
            workspaceId={SEMANTICS_FIXTURE_WORKSPACE_ID}
            view={search.view}
        filters={search}
        onFilterChange={(patch)=>updateSearch(patch as Parameters<typeof updateSearch>[0])}
            searchQuery={search.q}
            semanticModels={{
              items: ready ? SEMANTIC_MODEL_FIXTURES : [],
              loading,
              error,
            }}
            metrics={{
              items: ready ? METRIC_FIXTURES : [],
              loading,
              error,
            }}
            modelPage={search.modelPage}
            modelPageSize={2}
            connectors={CONNECTOR_FIXTURES}
            connectorsLoaded
            timeSpines={{
              items: ready ? TIME_SPINE_FIXTURES : [],
              loading,
              error,
            }}
            selectedConnectorId={search.connectorId}
            onViewChange={(view) => updateSearch({
              view,
              q: "",
              connectorId: view === "setup" ? search.connectorId : undefined,
              modelPage: 0,
            })}
            onSearchQueryChange={(q) => updateSearch({ q, modelPage: 0 })}
            onModelPageChange={(modelPage) => updateSearch({ modelPage })}
            onConnectorChange={(connectorId) => updateSearch({ view: "setup", connectorId })}
            onTimeSpineSave={async (_connectorId, definition) => definition}
          />
        </div>
      </div>
    </MainLayout>
  );
}
