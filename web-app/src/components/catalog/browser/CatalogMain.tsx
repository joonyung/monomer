import { useLayoutEffect, useRef, useState } from "react";
import { RelationColumn } from "./RelationColumn";
import { SchemaColumn } from "./SchemaColumn";
import { ColumnBack } from "./ColumnChrome";
import { ConnectionRecovery } from "@/components/catalog/ConnectionRecovery";
import { RelationDetailPanel } from "@/components/catalog/RelationDetailPanel";
import { refreshFailureSummary, type RefreshFailure } from "@/components/catalog/catalogCopy";
import type { CatalogIngestJob, CatalogIngestTask, RelationSummary, SchemaSummary } from "@/lib/types/catalog";
import type { Connector } from "@/lib/types/connector";
import type { CatalogMainData } from "@/hooks/useCatalogTree";

export interface CatalogMainProps {
  connector: Pick<Connector, "id" | "type">;
  data: CatalogMainData;
  job: CatalogIngestJob | null;
  // Failed tasks of the active refresh, or of the latest finished refresh when none is running.
  refreshErrors: CatalogIngestTask[];
  // Starts a refresh of the current entry; null when the viewer can't refresh or one is running.
  onRefresh: (() => void) | null;
  // The exact Catalog URL to come back to after fixing the connector.
  returnTo: string | null;
}

export type { CatalogMainData };

type BrowserLayout = "columns" | "split" | "single";
type BrowserPane = "schemas" | "tables" | "detail";

// Schemas 240 + Tables 280 + detail 420 fit side by side; below that the schema list collapses
// behind a back affordance, and a phone-width browser drills down one level at a time.
const COLUMNS_MIN_WIDTH = 940;
const SPLIT_MIN_WIDTH = 600;

function layoutForWidth(width: number): BrowserLayout {
  if (width >= COLUMNS_MIN_WIDTH) return "columns";
  return width >= SPLIT_MIN_WIDTH ? "split" : "single";
}

function useBrowserLayout() {
  const ref = useRef<HTMLDivElement>(null);
  const [layout, setLayout] = useState<BrowserLayout>("columns");
  useLayoutEffect(() => {
    const element = ref.current;
    if (!element || typeof ResizeObserver === "undefined") return;
    const update = () => setLayout(layoutForWidth(element.getBoundingClientRect().width));
    update();
    const observer = new ResizeObserver(update);
    observer.observe(element);
    return () => observer.disconnect();
  }, []);
  return { ref, layout };
}

function sameCatalog(left: string | null | undefined, right: string | null | undefined): boolean {
  return (left ?? null) === (right ?? null);
}

function taskForSchema(job: CatalogIngestJob | null, schema: SchemaSummary) {
  return job?.tasks.find((task) => task.targetKind === "SCHEMA" && task.schemaName === schema.name && sameCatalog(task.catalogName, schema.catalogName)) ?? null;
}

function taskForRelation(job: CatalogIngestJob | null, relation: Pick<RelationSummary, "catalogName" | "schemaName" | "name">) {
  return job?.tasks.find((task) => task.targetKind === "RELATION" && task.schemaName === relation.schemaName && task.relationName === relation.name && sameCatalog(task.catalogName, relation.catalogName)) ?? null;
}

function schemaFailure(errors: CatalogIngestTask[], catalogName: string | null | undefined, schemaName: string): RefreshFailure | null {
  return errors.find((task) => task.targetKind === "SCHEMA" && task.schemaName === schemaName && sameCatalog(task.catalogName, catalogName)) ?? null;
}

function relationFailure(errors: CatalogIngestTask[], relation: Pick<RelationSummary, "catalogName" | "schemaName" | "name">): RefreshFailure | null {
  return errors.find((task) => task.targetKind === "RELATION" && task.schemaName === relation.schemaName && task.relationName === relation.name && sameCatalog(task.catalogName, relation.catalogName)) ?? null;
}

function failedTablesInSchema(errors: CatalogIngestTask[], catalogName: string | null | undefined, schemaName: string): number {
  return errors.filter((task) => task.targetKind === "RELATION" && task.schemaName === schemaName && sameCatalog(task.catalogName, catalogName)).length;
}

export function CatalogMain({ connector, data, job, refreshErrors, onRefresh, returnTo }: CatalogMainProps) {
  const { ref, layout } = useBrowserLayout();
  const [pane, setPane] = useState<BrowserPane>(() => data.selectedRelation ? "detail" : data.selectedSchema ? "tables" : "schemas");
  const selectedSchema = data.selectedSchema;

  const loadingSchema = (schema: SchemaSummary) => taskForSchema(job, schema)?.loading === true;
  const failedSchema = (schema: SchemaSummary) => {
    const running = taskForSchema(job, schema)?.safeMessage;
    if (running) return running;
    const failure = schemaFailure(refreshErrors, schema.catalogName, schema.name);
    if (failure) return refreshFailureSummary(failure);
    const failedTables = failedTablesInSchema(refreshErrors, schema.catalogName, schema.name);
    return failedTables > 0 ? `Last refresh failed for ${failedTables} ${failedTables === 1 ? "table" : "tables"}` : null;
  };
  const loadingRelation = (relation: RelationSummary) => taskForRelation(job, relation)?.loading === true;
  const failedRelation = (relation: RelationSummary) => {
    const running = taskForRelation(job, relation)?.safeMessage;
    if (running) return running;
    const failure = relationFailure(refreshErrors, relation);
    return failure ? refreshFailureSummary(failure) : null;
  };

  const selectedSchemaFailure = selectedSchema ? schemaFailure(refreshErrors, selectedSchema.catalogName, selectedSchema.schemaName) : null;
  const selectedRelationFailure = data.selectedRelation
    ? relationFailure(refreshErrors, { catalogName: data.selectedRelation.catalogName, schemaName: data.selectedRelation.schemaName, name: data.selectedRelation.relationName })
    : null;
  const failureNotice = (failure: RefreshFailure) => (
    <div role="note" className="flex-shrink-0 border-b border-border bg-surface px-3 py-2.5">
      <ConnectionRecovery failureCode={failure.failureCode} safeMessage={failure.safeMessage} connector={connector} consequence={null} returnTo={returnTo} onRetry={onRefresh} retrying={false} links={null} density="row" />
    </div>
  );
  const schemaNotice = selectedSchemaFailure ? failureNotice(selectedSchemaFailure) : null;
  const selectedSchemaSummary = selectedSchema ? data.schemas.find((schema) => schema.name === selectedSchema.schemaName) : undefined;
  const selectedSchemaLoaded = selectedSchemaSummary?.lastRefreshedAt !== null;

  const showSchemas = layout === "columns" || pane === "schemas";
  const showTables = layout === "columns" || (layout === "split" ? pane !== "schemas" : pane === "tables");
  const showDetail = layout !== "single" || pane === "detail";
  const listWidth = layout === "single" ? null : layout === "split" ? "300px" : null;

  return (
    <div ref={ref} className="flex h-full min-w-0 flex-1" data-layout={layout}>
      {showSchemas ? (
        <SchemaColumn
          width={layout === "columns" ? "240px" : listWidth}
          schemas={data.schemas}
          totalCount={data.schemasTotalCount}
          page={data.schemasPage}
          pageSize={data.schemasPageSize}
          loading={data.loadingSchemas}
          query={data.schemaQuery}
          onQueryChange={data.setSchemaQuery}
          onPageChange={data.setSchemasPage}
          selectedSchemaName={selectedSchema?.schemaName ?? null}
          markSelection={!data.relations.some((relation) => relation.name === data.selectedRelation?.relationName)}
          onSelectSchema={(schema) => {
            data.selectSchema({ catalogName: schema.catalogName, schemaName: schema.name });
            setPane("tables");
          }}
          loadingSchema={loadingSchema}
          failedSchema={failedSchema}
          onRefresh={onRefresh}
        />
      ) : null}
      {showTables ? (
        <RelationColumn
          width={layout === "columns" ? "280px" : listWidth}
          back={layout === "columns" ? null : <ColumnBack label="Schemas" onBack={() => setPane("schemas")} />}
          notice={schemaNotice}
          relations={data.relations}
          totalCount={data.relationsTotalCount}
          page={data.relationsPage}
          pageSize={data.relationsPageSize}
          loading={data.loadingRelations}
          query={data.relationQuery}
          onQueryChange={data.setRelationQuery}
          onPageChange={data.setRelationsPage}
          selectedRelationName={data.selectedRelation?.relationName ?? null}
          onSelectRelation={(relation) => {
            data.selectRelation(relation);
            setPane("detail");
          }}
          loadingRelation={loadingRelation}
          failedRelation={failedRelation}
          schemaSelected={selectedSchema !== null}
          schemaLoaded={selectedSchemaLoaded}
        />
      ) : null}
      {showDetail ? (
        <div data-testid="catalog-relation-detail" className={`flex min-w-0 flex-1 flex-col ${layout === "columns" ? "min-w-[420px]" : ""}`}>
          {layout === "single" && selectedSchema ? <ColumnBack label={selectedSchema.schemaName} onBack={() => setPane("tables")} /> : null}
          {selectedRelationFailure ? failureNotice(selectedRelationFailure) : null}
          <div className="min-h-0 flex-1">
            <RelationDetailPanel
              connectorId={connector.id}
              relation={data.relationDetail}
              observedAt={data.relations.find((relation) => relation.name === data.selectedRelation?.relationName)?.lastRefreshedAt ?? null}
              loading={data.loadingRelationDetail}
              loadingMetadata={data.selectedRelation ? loadingRelation({
                catalogName: data.selectedRelation.catalogName,
                schemaName: data.selectedRelation.schemaName,
                name: data.selectedRelation.relationName,
                kind: "UNKNOWN",
                columnCount: 0,
              }) : false}
            />
          </div>
        </div>
      ) : null}
    </div>
  );
}
