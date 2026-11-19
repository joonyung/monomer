import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it, vi } from "vitest";
import {
  TimeSpineEditorRow,
  TimeSpinesExperience,
  validateTimeSpineDefinition,
} from "@/components/semantics/TimeSpinesExperience";
import type { Connector } from "@/lib/types/connector";
import type { RelationDetail } from "@/lib/types/catalog";
import { queryKeys } from "@/lib/query/keys";
import { TIME_GRANULARITY_LABELS } from "@/lib/types/semantic-model";
import { TIME_SPINE_GRANULARITIES, type TimeSpine, type TimeSpineConfiguration } from "@/lib/types/time-spine";

const session = vi.hoisted(() => ({ principalId: "user-1" as string | null }));

vi.mock("@/hooks/use-workspace", () => ({
  useWorkspaceId: () => "workspace-1",
  usePrincipalId: () => session.principalId,
}));

const CONNECTORS: Connector[] = [
  {
    id: "connector-1",
    workspaceId: "workspace-1",
    name: "Warehouse",
    type: "POSTGRESQL",
    properties: { host: "fixture.internal", port: 5432, database: "warehouse" },
    createdAtEpochMs: 0,
    updatedAtEpochMs: 0,
  },
  {
    id: "connector-2",
    workspaceId: "workspace-1",
    name: "Lakehouse",
    type: "TRINO",
    properties: { host: "fixture.internal", port: 443, catalog: "lake" },
    createdAtEpochMs: 0,
    updatedAtEpochMs: 0,
  },
];

const CONFIGURATION: TimeSpineConfiguration = {
  connectorId: "connector-1",
  definition: {
    schemaVersion: 1,
    timeSpines: [{
      relation: { catalog: "analytics", schema: "shared", relation: "dim_date" },
      primaryColumn: { name: "date_day", granularity: "day" },
    }],
  },
};

function renderExperience({
  configurations = [CONFIGURATION],
  selectedConnectorId = "connector-1",
  query,
  canEdit = true,
  connectors = CONNECTORS,
}: {
  canEdit?: boolean;
  connectors?: Connector[];
  configurations?: TimeSpineConfiguration[];
  selectedConnectorId?: string;
  query?: string;
} = {}): string {
  return renderToStaticMarkup(
    <TimeSpinesExperience
      canEdit={canEdit}
      connectors={connectors}
      configurations={{ items: configurations, loading: false, error: null }}
      selectedConnectorId={selectedConnectorId}
      query={query ?? ""}
      onConnectorChange={() => undefined}
      onSave={async (_connectorId, definition) => definition}
    />,
  );
}

describe("TimeSpinesExperience", () => {
  it("renders every connector, including an unconfigured connector, in a connector-rooted outline", () => {
    const markup = renderExperience();

    expect(markup).toContain("Warehouse");
    expect(markup).toContain("Lakehouse");
    expect(markup).toContain("Not configured");
    expect(markup).toContain("analytics.shared.dim_date");
    expect(markup).toContain("Primary column · date_day");
    expect(markup).toContain('aria-label="Connectors and Calendar tables"');
    expect(markup).toContain('aria-label="Configure Calendar tables for Warehouse"');
    expect(markup).not.toContain("Save configuration");
  });

  it("scopes search to connector identity/type and Calendar table fields", () => {
    const relationMatch = renderExperience({ query: "date_day" });
    expect(relationMatch).toContain("Warehouse");
    expect(relationMatch).not.toContain("Lakehouse");
    expect(relationMatch).toContain("analytics.shared.dim_date");

    const connectorMatch = renderExperience({ query: "trino", selectedConnectorId: "connector-2" });
    expect(connectorMatch).toContain("Lakehouse");
    expect(connectorMatch).not.toContain("Warehouse");
  });

  it("explains which metrics need a time spine and what fails without one", () => {
    const markup = renderExperience({
      configurations: [{ connectorId: "connector-1", definition: { schemaVersion: 1, timeSpines: [] } }],
    });

    expect(markup).toContain("Configure Calendar tables");
    expect(markup).not.toContain("capability");
    expect(markup).toContain("Running totals, conversion metrics, and metric inputs with an offset need a Calendar table");
    expect(markup).toContain("This metric needs a time spine.");
  });

  it("names granularities in words", () => {
    const markup = renderExperience();

    expect(markup).toContain(">Day<");
    expect(markup).not.toContain(">day<");
  });

  it("keeps read-only details separate from mutation and uses a responsive inspector/drawer", () => {
    const markup = renderExperience();

    expect(markup).toContain('aria-label="Calendar table details"');
    expect(markup).toContain("w-[520px]");
    expect(markup).toContain("Configure Calendar tables for Warehouse");
    expect(markup).not.toContain("Delete");
    const markupWithoutClasses = markup.replace(/ class="[^"]*"/g, "");
    expect(markupWithoutClasses).not.toContain("status");
    expect(markup).not.toContain("revision");
  });

  it("selects the only connector without waiting for a URL selection", () => {
    const markup = renderExperience({ connectors: [CONNECTORS[1]], configurations: [], selectedConnectorId: "" });
    expect(markup).toContain('aria-current="true"');
    expect(markup).toContain("No Calendar table is configured for this connector.");
    expect(markup).not.toContain("Select a connector to inspect");
    expect(markup).toContain("flex min-h-0 flex-1 flex-col bg-background");
    expect(markup).not.toContain("min-h-[520px]");
    expect(markup).not.toContain("min-h-[460px]");
  });

  it("offers one secondary empty-state action and reserves the pencil for configured tables", () => {
    const empty = renderExperience({ configurations: [] });
    expect(empty).toContain("Configure Calendar tables</button>");
    expect(empty).not.toContain('aria-label="Configure Calendar tables for Warehouse"');
    expect(empty).toContain("border-border bg-transparent text-foreground hover:bg-surface");
    expect(renderExperience()).toContain('aria-label="Configure Calendar tables for Warehouse"');
  });

  it("pairs each connector configuration state with a neutral dot and a text label", () => {
    const markup = renderExperience();
    expect(markup.match(/h-1\.5 w-1\.5 rounded-full bg-foreground-secondary/g)).toHaveLength(2);
    expect(markup).toContain("1 configured");
    expect(markup).toContain("Not configured");
    expect(markup).not.toContain("bg-success");
  });

  it("requires a non-empty configuration and rejects duplicate grain", () => {
    expect(validateTimeSpineDefinition({ schemaVersion: 1, timeSpines: [] })).toBeNull();
    expect(validateTimeSpineDefinition({
      schemaVersion: 1,
      timeSpines: [
        {
          relation: { schema: "shared", relation: "dim_date" },
          primaryColumn: { name: "date_day", granularity: "day" },
        },
        {
          relation: { schema: "shared", relation: "dim_date" },
          primaryColumn: { name: "week_start", granularity: "day" },
        },
      ],
    })).toBe("Calendar table 2: each granularity can be configured only once.");
    expect(validateTimeSpineDefinition({
      schemaVersion: 1,
      timeSpines: [{ relation: { schema: "", relation: "" }, primaryColumn: { name: "", granularity: "day" } }],
    })).toBe("Calendar table 1: choose a table.");
  });

  it("preserves the whole-definition contract for every configured grain", () => {
    const allGrains = TIME_SPINE_GRANULARITIES.map((granularity) => ({
      relation: { schema: "shared", relation: "dim_date" },
      primaryColumn: { name: `date_${granularity}`, granularity },
    }));
    const definition = { schemaVersion: 1 as const, timeSpines: allGrains };

    expect(validateTimeSpineDefinition(definition)).toBeNull();
    expect(renderExperience({ configurations: [{ connectorId: "connector-1", definition }] })).toContain("11");
  });
});

it("keeps Calendar table reads available but hides mutation below Editor", () => { const markup = renderExperience({ canEdit: false }); expect(markup).toContain("analytics.shared.dim_date"); expect(markup).not.toContain("Configure Calendar tables"); });

const DIM_DATE: TimeSpine = {
  relation: { catalog: "analytics", schema: "shared", relation: "dim_date" },
  primaryColumn: { name: "date_day", granularity: "day" },
};

function relationDetail(columnNames: string[]): RelationDetail {
  return { requiresPartitionFilter: null,
    catalogName: "analytics",
    schemaName: "shared",
    name: "dim_date",
    kind: "TABLE",
    columns: columnNames.map((name, index) => ({ name, nativeType: "date", ordinalPosition: index + 1, nullable: false })),
    partitionKeys: [],
    clusteringKeys: [],
    foreignKeys: [],
  } as unknown as RelationDetail;
}

function renderRow(spine: TimeSpine, { principalId = "user-1", columns = null, fieldErrors = {} }: { principalId?: string | null; columns?: string[] | null; fieldErrors?: Record<string, string> } = {}): string {
  session.principalId = principalId;
  const queryClient = new QueryClient();
  if (columns && principalId) {
    queryClient.setQueryData(
      queryKeys.catalog.relation("workspace-1", principalId, "connector-1", "analytics", "shared", "dim_date"),
      relationDetail(columns),
    );
  }
  return renderToStaticMarkup(
    <QueryClientProvider client={queryClient}>
      <TimeSpineEditorRow index={0} connectorId="connector-1" spine={spine} fieldErrors={fieldErrors} onChange={() => undefined} onRemove={() => undefined} />
    </QueryClientProvider>,
  );
}

describe("Calendar table editor row", () => {
  it("starts a new row at a Catalog table search with the column waiting for a table", () => {
    const markup = renderRow({ relation: { schema: "", relation: "" }, primaryColumn: { name: "", granularity: "day" } });

    expect(markup).toContain("No table chosen");
    expect(markup).toContain('aria-label="Choose table for Calendar table 1"');
    expect(markup).toContain("Type the table name");
    expect(markup).toContain("Choose a table first");
    expect(markup).not.toMatch(/id="[^"]*time-spine-0-schema"/);
    expect(markup).toContain(">Grain<");
    for (const granularity of TIME_SPINE_GRANULARITIES) {
      expect(markup).toMatch(new RegExp(`value="${granularity}"( selected="")?>${TIME_GRANULARITY_LABELS[granularity]}</option>`));
    }
  });

  it("chooses the primary column from the Catalog table's columns", () => {
    const markup = renderRow(DIM_DATE, { columns: ["date_day", "week_start"] });

    expect(markup).toContain("analytics.shared.dim_date");
    expect(markup).toContain('aria-label="Change table for Calendar table 1"');
    expect(markup).toMatch(/<option value="date_day" selected="">date_day · date<\/option>/);
    expect(markup).toContain("week_start · date");
    expect(markup).not.toContain("not in Catalog");
    expect(markup).not.toMatch(/id="[^"]*time-spine-0-schema"/);
  });

  it("keeps a saved column that the Catalog table no longer lists", () => {
    const markup = renderRow(DIM_DATE, { columns: ["calendar_date"] });

    expect(markup).toContain("date_day (not in Catalog)");
  });

  it("associates server field messages with the existing input and select controls", () => {
    const fieldErrors = {
      "timeSpines[0].relation.catalog": "Check the catalog name.",
      "timeSpines[0].relation.schema": "Check the schema name.",
      "timeSpines[0].relation.relation": "Check the table name.",
      "timeSpines[0].primaryColumn.name": "Check the column name.",
      "timeSpines[0].primaryColumn.granularity": "Choose a supported granularity.",
      "timeSpines[1].relation.schema": "A different row's schema error.",
    };
    const markup = renderRow(DIM_DATE, { principalId: null, fieldErrors });
    for (const field of ["catalog", "schema", "relation", "column", "granularity"]) {
      expect(markup).toMatch(new RegExp(`id="([^"]*time-spine-0-${field})" aria-invalid="true" aria-describedby="\\1-message"`));
    }
    for (const message of Object.values(fieldErrors).slice(0, 5)) expect(markup).toContain(message);
    expect(markup).not.toContain("A different row&#x27;s schema error.");
    const picker = renderRow(DIM_DATE, { columns: ["date_day"], fieldErrors });
    expect(picker).toMatch(/<select[^>]*aria-invalid="true"[^>]*aria-describedby="[^"]*time-spine-0-column-message"/);
  });

  it("falls back to typed names when Catalog cannot describe the table", () => {
    const markup = renderRow(DIM_DATE, { principalId: null });

    expect(markup).toContain("Catalog has no columns for this table.");
    expect(markup).toMatch(/id="[^"]*time-spine-0-relation"[^>]*value="dim_date"/);
    expect(markup).toMatch(/id="[^"]*time-spine-0-column"[^>]*value="date_day"/);
    expect(markup).not.toContain("Type the table name");
  });
});
