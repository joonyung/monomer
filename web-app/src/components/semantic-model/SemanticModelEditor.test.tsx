import { Children, isValidElement, type ReactElement, type ReactNode } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { describe, expect, it, vi } from "vitest";
import type { SemanticModelResponse } from "@/api/semantic-model";
import { SemanticModelEditor, SourceTables, tableTitle, modelWriteRequest, modelSaveFieldErrors } from "@/components/semantic-model/SemanticModelEditor";
import { FieldDetailsForm, FieldsTable } from "@/components/semantic-model/SemanticModelFieldsTable";
import { changeFieldLabel, definitionFromFields, fieldPaths, fieldsFromDefinition, type FieldDraft } from "@/components/semantic-model/semantic-model-fields";
import { sourceTableFromCatalog } from "@/components/semantic-model/semantic-model-sources";

vi.mock("@/hooks/use-workspace", () => ({
  useWorkspaceId: () => "workspace-1",
  usePrincipalId: () => null,
}));

vi.mock("@/lib/navigation", () => ({
  Link: ({ href, children, className }: { href: string; children: ReactNode; className?: string }) => (
    <a href={href} className={className}>{children}</a>
  ),
  useRouter: () => ({ push: vi.fn() }),
}));

vi.mock("@/api/semantic-model", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/api/semantic-model")>()),
  semanticModelAPI: {
    create: vi.fn(),
    update: vi.fn(),
    getById: vi.fn(),
  },
}));

vi.mock("@/api/connectors", () => ({
  connectorsAPI: { list: vi.fn() },
}));

vi.mock("@/api/catalog", () => ({
  catalogAPI: { getRelation: vi.fn() },
  catalogWorkspaceAPI: { searchTables: vi.fn() },
}));

const model: SemanticModelResponse = {
  id: "model-1",
  name: "invoices",
  label: "Invoices",
  sourceRelations: [{ connectorId: "connector-1", schema: "billing", relation: "invoices" }],
  aggregationTimeDimension: "issued_at",
  entities: [{ name: "invoice_id", type: "primary", expression: "invoice_id", label: "Invoice" }],
  dimensions: [
    { isPartition: false, name: "issued_at", type: "time", expression: "issued_at", granularity: "hour" },
    { isPartition: false, name: "status", type: "categorical", expression: "status" },
  ],
  measures: [{ name: "revenue", aggregation: "sum", expression: "amount_cents / 100.0", label: "Revenue" }],
  createdAt: "2026-09-01T00:00:00Z",
  updatedAt: "2026-09-01T00:00:00Z",
};

function render(node: ReactNode): string {
  return renderToStaticMarkup(<QueryClientProvider client={new QueryClient()}>{node}</QueryClientProvider>);
}

function tableRows(markup: string): number {
  return markup.match(/<tr[\s>]/g)?.length ?? 0;
}

describe("tableTitle", () => {
  it("starts a model opened from a table with a readable label", () => {
    expect(tableTitle("orders")).toBe("Orders");
    expect(tableTitle("order_items")).toBe("Order Items");
    expect(tableTitle("DIM_DATE")).toBe("Dim Date");
  });
});

describe("SemanticModelEditor", () => {
  it("requests only aggregates without an existing metric, preserving existing names and metadata", () => {
    const definition = { ...model, measures: [...model.measures, { name: "invoice_count", label: "Invoice count", description: "Count invoices.", expression: "invoice_id", aggregation: "count" as const }] };
    const existing = { id: "metric-1", name: "recognized_revenue", label: "Recognized revenue", type: "simple" as const, typeParams: { semanticModelName: model.name, measure: "revenue" }, createdAt: model.createdAt, updatedAt: model.updatedAt };
    const request = modelWriteRequest(definition, [existing], null);
    expect(request.aggregateMetrics).toEqual([{ measure: "invoice_count", name: "invoice_count", label: "Invoice count", description: "Count invoices." }]);
    expect(request.definition.measures).toEqual(definition.measures);
    expect(modelWriteRequest(model, [existing], null).aggregateMetrics).toEqual([]);
  });

  it("retains the submitted aggregates when a committed response is lost and the metric cache refreshes", () => {
    const submitted = modelWriteRequest(model, [], null);
    const committed = { id: "metric-1", name: "revenue", type: "simple" as const, typeParams: { semanticModelName: model.name, measure: "revenue" }, createdAt: model.createdAt, updatedAt: model.updatedAt };
    const retry = modelWriteRequest({ ...model }, [committed], submitted);
    expect(retry).toBe(submitted);
    expect(retry.aggregateMetrics).toEqual([{ measure: "revenue", name: "revenue", label: "Revenue" }]);
  });

  it("builds a new atomic request from current metrics when the draft changes after a failed response", () => {
    const submitted = modelWriteRequest(model, [], null);
    const committed = { id: "metric-1", name: "revenue", type: "simple" as const, typeParams: { semanticModelName: model.name, measure: "revenue" }, createdAt: model.createdAt, updatedAt: model.updatedAt };
    const changed = { ...model, description: "Updated after the failed response.", measures: [...model.measures, { name: "invoice_count", expression: "invoice_id", aggregation: "count" as const }] };
    const next = modelWriteRequest(changed, [committed], submitted);
    expect(next).not.toBe(submitted);
    expect(next.definition).toEqual(changed);
    expect(next.aggregateMetrics).toEqual([{ measure: "invoice_count", name: "invoice_count" }]);
  });

  it("maps indexed metric errors to their source measure cells even when existing aggregates were skipped", () => {
    const request = { definition: { ...model, measures: [...model.measures, { name: "invoice_count", expression: "invoice_id", aggregation: "count" as const }] }, aggregateMetrics: [{ measure: "invoice_count", name: "invoice_count" }] };
    expect(modelSaveFieldErrors(request, { "aggregateMetrics[0].name": "Name already exists.", "aggregateMetrics[0].measure": "Measure is missing.", "sourceRelations[0]": "Source is missing." })).toEqual({ "measures[1].name": "Measure is missing.", "sourceRelations[0]": "Source is missing." });
    expect(modelSaveFieldErrors(request, { "aggregateMetrics[0].name": "Name already exists." })).toEqual({ "measures[1].name": "Name already exists." });
  });
  it("renders the create form while its disabled detail query is pending", () => {
    const markup = render(<SemanticModelEditor />);
    expect(markup).toContain("New model");
    expect(markup).toContain("Identity");
    expect(markup).toContain("Source table");
    expect(markup).toContain("Columns <span class=\"tabular-nums text-foreground-secondary\">· 0</span>");
    expect(markup).toContain("Add calculated field");
    expect(markup.indexOf("Source table")).toBeLessThan(markup.indexOf("Identity"));
    expect(markup).not.toContain("Metric time");
    expect(markup).toContain('id="semantic-model-name"');
    expect(markup).not.toMatch(/Binding|Relation|Aggregation time dimension|Grain/);
  });

  it("shows the model name read-only in edit mode", () => {
    const markup = render(<SemanticModelEditor modelId="model-1" initialData={model} onCancel={vi.fn()} />);
    expect(markup).toContain("Name is used in queries and can&#x27;t be changed after the model is created.");
    expect(markup).not.toContain('id="semantic-model-name"');
    expect(markup).toContain("max-w-[1440px]");
    expect(markup).toContain("lg:grid-cols-[minmax(0,1fr)_340px]");
    expect(markup).toContain("lg:sticky lg:top-24 lg:max-h-[calc(100vh-7rem)] lg:overflow-y-auto");
    expect(markup).toMatch(/<input type="radio" aria-label="Use issued_at as Metric time"[^>]*name="semantic-model-metric-time"/);
    expect(markup).not.toContain('id="semantic-model-time-axis"');
    expect(markup).toMatch(/<button[^>]*class="[^"]*hover:bg-surface[^"]*"[^>]*>Cancel<\/button>/);
    expect(markup).toMatch(/<button[^>]*class="[^"]*hover:bg-surface[^"]*"[^>]*>.*?Add calculated field<\/button>/);
  });
});

describe("Fields table", () => {
  const fields = fieldsFromDefinition(model.entities, model.dimensions, model.measures);

  it("puts the native type in its own column and gives a calculated field's SQL the column and type cells", () => {
    const nativeFields = [{ ...fields[0], nativeType: "BIGINT" }, { ...fields[1], expression: "DATE_TRUNC('day', issued_at)" }];
    const markup = render(<FieldsTable fields={nativeFields} paths={fieldPaths(nativeFields)} errors={{}} onChange={vi.fn()} onRemove={vi.fn()} onOpenDetails={vi.fn()} />);
    expect(markup).toContain('title="BIGINT">BIGINT</span></span></td>');
    expect(markup).toContain('title="invoice_id"');
    expect(markup).toMatch(/<td colSpan="2"[^>]*><textarea[^>]*aria-label="Field 2 expression"/);
    expect(markup).toContain("border-transparent hover:border-border");
  });

  it("lists an unused source column without inputs until a use is chosen", () => {
    const unused = [{ ...fields[0], kind: "unused" as const, nativeType: "BIGINT" }, { ...fields[1], nativeType: "TIMESTAMP" }];
    const markup = render(<FieldsTable fields={unused} paths={fieldPaths(unused)} errors={{}} onChange={vi.fn()} onRemove={vi.fn()} onOpenDetails={vi.fn()} />);
    expect(markup).not.toContain('aria-label="Field 1 expression"');
    expect(markup).not.toContain('aria-label="Field 1 name"');
    expect(markup).toMatch(/<code data-field-identifier="true" title="invoice_id"[^>]*>invoice_<wbr\/>id<\/code>/);
    expect(markup).toContain('aria-label="Field 1 use as"');
    expect(markup).toContain('aria-label="Field 2 name"');
  });

  it("selects the model's Metric time on its Time row and hides an In use view's unused rows", () => {
    const onModelTimeDimensionChange = vi.fn();
    const markup = render(<FieldsTable fields={fields} paths={fieldPaths(fields)} errors={{}} modelTimeDimension="issued_at" onModelTimeDimensionChange={onModelTimeDimensionChange} onModelTimeDimensionClear={vi.fn()} hiddenIds={new Set([fields[2].id])} onChange={vi.fn()} onRemove={vi.fn()} onOpenDetails={vi.fn()} />);
    expect(markup).toMatch(/aria-label="Clear Metric time"[^>]*>Clear<\/button>/);
    expect(markup).toMatch(/<input type="radio" aria-label="Use issued_at as Metric time"[^>]*name="semantic-model-metric-time" checked=""/);
    expect(markup.match(/type="radio"/g)).toHaveLength(1);
    expect(markup).not.toContain('aria-label="Field 3 name"');
    expect(markup).toContain('aria-label="Field 4 name"');
  });

  it("hides the rows a view names without renumbering the rest and words an empty result", () => {
    const markup = render(<FieldsTable fields={fields} filterText="issued" hiddenIds={new Set([fields[0].id, fields[2].id, fields[3].id])} paths={fieldPaths(fields)} errors={{}} onChange={vi.fn()} onRemove={vi.fn()} onOpenDetails={vi.fn()} />);
    expect(markup).toContain('aria-label="Field 2 name"');
    expect(markup).not.toContain('aria-label="Field 1 name"');
    expect(tableRows(markup)).toBe(2);
    const everything = new Set(fields.map((field) => field.id));
    expect(render(<FieldsTable fields={fields} filterText="nothing" hiddenIds={everything} paths={fieldPaths(fields)} errors={{}} onChange={vi.fn()} onRemove={vi.fn()} onOpenDetails={vi.fn()} />)).toContain("No column matches “nothing”.");
    expect(render(<FieldsTable fields={fields} hiddenIds={everything} paths={fieldPaths(fields)} errors={{}} onChange={vi.fn()} onRemove={vi.fn()} onOpenDetails={vi.fn()} />)).toContain("No columns are in use yet.");
  });

  it("renders one line per field with its kind, expression, and detail", () => {
    const markup = render(<FieldsTable fields={fields} paths={fieldPaths(fields)} errors={{}} onChange={vi.fn()} onRemove={vi.fn()} onOpenDetails={vi.fn()} />);
    expect(tableRows(markup)).toBe(fields.length + 1);
    for (const heading of ["Column", "Type", "Name", "Use as", "Details"]) expect(markup).toContain(`>${heading}</th>`);
    expect(markup).not.toContain('aria-label="Field 1 label"');
    expect(markup).toContain('aria-label="Field 2 name"');
    expect(markup).toContain('value="issued_at"');
    expect(markup).not.toContain("Label (optional)");
    expect(markup).toContain('<option value="primary" selected="">Row key</option>');
    expect(markup).toContain('<option value="time" selected="">Time</option>');
    expect(markup).toContain('<option value="categorical" selected="">Category</option>');
    expect(markup).toContain('<option value="hour" selected="">Hour</option>');
    expect(markup.match(/<option value="(nanosecond|microsecond|millisecond|second|minute|hour|day|week|month|quarter|year)"/g)).toHaveLength(11);
    expect(markup).toContain('<option value="aggregate:sum" selected="">Sum → metric</option>');
    expect(markup).toContain('aria-label="Field 4 options"');
  });

  it("changes a seeded field's technical name through its Name cell while keeping the label independent", () => {
    type ControlProps = { children?: ReactNode; "aria-label"?: string; onChange?: (event: { target: { value: string } }) => void };
    const findControl = (node: ReactNode, label: string): ReactElement<ControlProps> | undefined => {
      if (!isValidElement<ControlProps>(node)) return undefined;
      if (node.props["aria-label"] === label) return node;
      return Children.toArray(node.props.children).map((child) => findControl(child, label)).find(Boolean);
    };
    let changed = fields[3];
    expect(changed.nameFollowsLabel).toBe(false);
    const onChange = vi.fn((_id: string, update: (field: FieldDraft) => FieldDraft) => { changed = update(changed); });
    const table = FieldsTable({ fields, paths: fieldPaths(fields), errors: {}, onChange, onRemove: vi.fn(), onOpenDetails: vi.fn() });
    const nameControl = findControl(table, "Field 4 name");
    expect(nameControl?.props.onChange).toBeDefined();
    nameControl?.props.onChange?.({ target: { value: "gross_revenue" } });
    expect(onChange).toHaveBeenCalledWith(fields[3].id, expect.any(Function));
    expect(changed).toMatchObject({ name: "gross_revenue", nameFollowsLabel: false, label: "Revenue" });
    expect(changeFieldLabel(changed, "Gross revenue").name).toBe("gross_revenue");
    expect(definitionFromFields([changed]).measures).toEqual([{ ...model.measures[0], name: "gross_revenue" }]);
  });

  it("shows errors beside their cells and points detail errors to the field details", () => {
    const errors = {
      "measures[0].name": "Change this field's name.",
      "measures[0].expression": "Enter a column or SQL expression.",
      "measures[0].nonAdditiveDimension": "Select a Time field for its non-additive rule.",
      "measures[0].label": "Use a shorter label.",
      "dimensions[0].granularity": "Select a granularity.",
    };
    const markup = render(<FieldsTable fields={fields} paths={fieldPaths(fields)} errors={errors} onChange={vi.fn()} onRemove={vi.fn()} onOpenDetails={vi.fn()} />);
    expect(markup).toContain("Enter a column or SQL expression.");
    expect(markup).toContain("Change this field&#x27;s name.");
    expect(markup).toContain("Select a granularity.");
    expect(markup).toContain("One value per period: select a Time field for its non-additive rule.");
    expect(markup).toContain("Label: use a shorter label.");
    expect(markup).toContain("Fix in field details");
    expect(tableRows(markup)).toBe(fields.length + 1);
  });

  it("reaches name, description, and measure options from the field details", () => {
    const measure = { ...fields[3], nonAdditiveDimension: { name: "issued_at", windowChoice: "max" as const, windowGroupings: ["invoice_id"] } };
    const markup = render(<FieldDetailsForm field={measure} path="measures[0]" errors={{}} timeFieldNames={["issued_at"]} entityNames={["invoice_id"]} onChange={vi.fn()} />);
    for (const label of ["Name", "Label (optional)", "Description (optional)", "Advanced options", "Metric time", "One value per period", "Value to take", "Group by keys (optional)"]) {
      expect(markup).toContain(label);
    }
    expect(markup).toContain('<option value="issued_at" selected="">issued_at</option>');
    expect(markup).toContain('<option value="min">Earliest (min)</option>');
    expect(markup).toContain('<option value="max" selected="">Latest (max)</option>');
    expect(markup).toMatch(/<input[^>]*type="checkbox"[^>]*checked=""|<input[^>]*checked=""[^>]*type="checkbox"/);

    const earliest = render(<FieldDetailsForm field={{ ...measure, nonAdditiveDimension: { ...measure.nonAdditiveDimension, windowChoice: "min" } }} path="measures[0]" errors={{}} timeFieldNames={["issued_at"]} entityNames={["invoice_id"]} onChange={vi.fn()} />);
    expect(earliest).toContain('<option value="min" selected="">Earliest (min)</option>');

    const category = render(<FieldDetailsForm field={fields[2]} path="dimensions[1]" errors={{}} timeFieldNames={["issued_at"]} entityNames={["invoice_id"]} onChange={vi.fn()} />);
    expect(category).toContain("Description (optional)");
    expect(category).not.toContain("Advanced options");
  });
});

describe("Source tables", () => {
  const connectors = [{ id: "connector-1", name: "Production Postgres" }, { id: "connector-2", name: "Lakehouse" }];
  const first = sourceTableFromCatalog({ connectorId: "connector-1", catalog: null, schema: "billing", relation: "invoices" });
  const second = sourceTableFromCatalog({ connectorId: "connector-2", catalog: "lake", schema: "billing", relation: "invoices" });
  const props = {
    connectors,
    errors: {},
    connectorsState: "ready" as const,
    workspaceConnectorsHref: "/workspaces/workspace-1/connectors",
    onAdd: vi.fn(),
    onChange: vi.fn(),
    onRemove: vi.fn(),
    onShowColumns: vi.fn(),
  };

  it("shows one source with Change and no remove action", () => {
    const markup = render(<SourceTables {...props} sources={[first]} activeSourceId={first.id} canAddSource />);
    expect(markup).toContain('<span class="type-body text-foreground">Production Postgres</span><span class="type-mono min-w-0 text-foreground [overflow-wrap:anywhere]">billing.invoices</span>');
    expect(markup).toContain('aria-label="Change source table"');
    expect(markup).toContain("Use another connector");
    expect(markup).toContain("Same table through another connection, such as a separate compute warehouse or a migrated copy. Each query runs on one connector.");
    expect(markup).not.toContain("Numbers match only when these tables hold the same data.");
    expect(markup).not.toContain("Remove source table");
  });

  it("keeps the other-connector explanation out when no other connector can be used", () => {
    const markup = render(<SourceTables {...props} sources={[first]} activeSourceId={first.id} canAddSource={false} />);
    expect(markup).not.toContain("Use another connector");
    expect(markup).not.toContain("Same table through another connection");
  });

  it("lets each of several sources be changed, removed, or shown as the column source", () => {
    const markup = render(<SourceTables {...props} sources={[first, second]} activeSourceId={first.id} canAddSource={false} />);
    expect(markup).toContain(">Lakehouse</span><span class=\"type-mono min-w-0 text-foreground [overflow-wrap:anywhere]\">lake.billing.invoices</span>");
    expect(markup).toContain('aria-label="Change source table 2"');
    expect(markup).toContain('aria-label="Remove source table 1"');
    expect(markup.match(/Show columns/g)).toHaveLength(1);
    expect(markup).not.toContain("Use another connector");
    expect(markup).toContain("Each query runs on one connector.");
    expect(markup).toContain("Numbers match only when these tables hold the same data.");
  });

  it("explains the connector prerequisite and offers a picker once connectors exist", () => {
    expect(render(<SourceTables {...props} connectors={[]} sources={[]} activeSourceId={null} canAddSource={false} />)).toContain("Add a connector before choosing a source table.");
    const empty = render(<SourceTables {...props} sources={[]} activeSourceId={null} canAddSource errors={{ sourceRelations: "Add a source table." }} />);
    expect(empty).toContain("Choose source table");
    expect(empty).toContain("Add a source table.");
  });
});
