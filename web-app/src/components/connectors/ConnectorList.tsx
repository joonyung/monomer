import { useMemo, useState } from "react";
import { Search } from "lucide-react";
import { countLabel, failureCause, formatRefreshTime, formatShortDate } from "@/components/catalog/catalogCopy";
import { ConnectorTypeMark } from "@/components/connectors/ConnectorTypeMark";
import { CONNECTOR_TYPE_META } from "@/components/connectors/connectorTypeMeta";
import { Input } from "@/components/ui/Input";
import { StateBlock } from "@/components/ui/StateBlock";
import { WorkStatus } from "@/components/ui/WorkStatus";
import { catalogHref, connectorLocationLabel, THREE_DEPTH_CONNECTOR_TYPES } from "@/lib/catalog/catalog-links";
import { Link } from "@/lib/navigation";
import type { CatalogConnectorSummary } from "@/lib/types/catalog";
import type { Connector } from "@/lib/types/connector";

// Catalog state per connector, or why it isn't available yet.
export type ConnectorSummaries =
  | { status: "loading" }
  | { status: "error" }
  | { status: "loaded"; byConnectorId: ReadonlyMap<string, CatalogConnectorSummary> };

interface ConnectorListProps {
  workspaceId: string;
  connectors: Connector[];
  summaries: ConnectorSummaries;
  // The plan's connector limit, or null when the plan has none.
  connectorLimit: number | null;
  // Editor or higher can choose tables from a connector with nothing chosen.
  canChooseTables: boolean;
  onOpen: (connector: Connector) => void;
}

const GRID = "sm:grid sm:grid-cols-[minmax(0,1.6fr)_minmax(0,1fr)_minmax(0,1.2fr)] sm:items-center sm:gap-4";

export function connectorCountLabel(total: number, matching: number | null, limit: number | null): string {
  const scope = limit === null ? countLabel(total, "connector", "connectors") : `Plan usage: ${total.toLocaleString("en-US")} of ${limit.toLocaleString("en-US")} connectors`;
  return matching === null ? scope : `${matching.toLocaleString("en-US")} matching · ${scope}`;
}

export function ConnectorList({ workspaceId, connectors, summaries, connectorLimit, canChooseTables, onOpen }: ConnectorListProps) {
  const [searchTerm, setSearchTerm] = useState("");
  const normalizedSearchTerm = searchTerm.trim().toLocaleLowerCase();
  const filteredConnectors = useMemo(
    () => connectors.filter((connector) => {
      if (!normalizedSearchTerm) return true;
      const typeLabel = CONNECTOR_TYPE_META[connector.type].label;
      return `${connector.name} ${typeLabel}`.toLocaleLowerCase().includes(normalizedSearchTerm);
    }),
    [connectors, normalizedSearchTerm],
  );

  return (
    <>
      <div className="mb-5 flex flex-col items-stretch gap-2 sm:flex-row sm:items-center sm:justify-between sm:gap-4">
        <div className="relative w-full max-w-[360px]">
          <Search className="pointer-events-none absolute left-3 top-1/2 h-3.5 w-3.5 -translate-y-1/2 text-foreground-secondary" aria-hidden="true" />
          <Input
            value={searchTerm}
            onChange={(event) => setSearchTerm(event.target.value)}
            placeholder="Search connectors"
            aria-label="Search connectors"
            className="pl-8"
          />
        </div>
        <span className="type-status shrink-0 self-end tabular-nums text-foreground-secondary sm:self-auto">
          {connectorCountLabel(connectors.length, normalizedSearchTerm ? filteredConnectors.length : null, connectorLimit)}
        </span>
      </div>

      {filteredConnectors.length === 0 ? (
        <StateBlock kind="empty" layout="inline" icon={Search} title={`No connectors match “${searchTerm.trim()}”.`} />
      ) : (
        <div className="border-y border-border">
          <div aria-hidden="true" className={`hidden border-b border-border px-4 py-2 type-group text-foreground-secondary ${GRID}`}>
            <span>Connector</span><span>Tables</span><span>Last refresh</span>
          </div>
          <ul className="divide-y divide-border-light" aria-label="Connectors">
            {filteredConnectors.map((connector) => {
              const summary = summaries.status === "loaded" ? summaries.byConnectorId.get(connector.id) ?? null : null;
              return (
                <li key={connector.id} className={`group relative min-h-[76px] px-3 py-3 transition-colors hover:bg-surface sm:px-4 ${GRID}`}>
                  <button
                    type="button"
                    onClick={() => onOpen(connector)}
                    aria-label={`View ${connector.name} connector`}
                    className="flex min-w-0 items-center gap-3 rounded-[4px] text-left after:absolute after:inset-0 after:content-[''] focus-visible:outline focus-visible:outline-[2px] focus-visible:outline-offset-2 focus-visible:outline-[var(--focus-ring)] sm:gap-4"
                  >
                    <ConnectorTypeMark type={connector.type} size="sm" />
                    <span className="min-w-0 flex-1">
                      <span className="type-item block truncate text-foreground" title={connector.name}>
                        {connector.name}
                      </span>
                      <span className="type-meta mt-1 block truncate text-foreground-secondary">
                        {connector.sample || connector.type === "DUCKDB" ? "Sample data · read-only · managed by Monomer" : <>{CONNECTOR_TYPE_META[connector.type].label} · {connectorLocationLabel(connector)}</>}
                      </span>
                    </span>
                  </button>
                  <TablesCell workspaceId={workspaceId} connector={connector} summaries={summaries} summary={summary} canChooseTables={canChooseTables} />
                  <LastRefreshCell summaries={summaries} summary={summary} />
                </li>
              );
            })}
          </ul>
        </div>
      )}
    </>
  );
}

function CellLines({ label, primary, secondary, tone }: { label: string; primary: React.ReactNode; secondary: React.ReactNode; tone: "default" | "error" | "muted" }) {
  return (
    <div className="mt-2 min-w-0 pl-10 sm:mt-0 sm:pl-0">
      <span className="sr-only">{label}: </span>
      <span className={`type-meta block truncate tabular-nums ${tone === "error" ? "text-error" : tone === "muted" ? "text-foreground-secondary" : "text-foreground"}`}>{primary}</span>
      {secondary ? <span className="type-status mt-0.5 block truncate tabular-nums text-foreground-secondary">{secondary}</span> : null}
    </div>
  );
}

function TablesCell({ workspaceId, connector, summaries, summary, canChooseTables }: { workspaceId: string; connector: Connector; summaries: ConnectorSummaries; summary: CatalogConnectorSummary | null; canChooseTables: boolean }) {
  if (summaries.status !== "loaded") return <CellLines label="Tables" primary={summaries.status === "loading" ? "…" : "Unavailable"} secondary={null} tone="muted" />;
  const threeDepth = THREE_DEPTH_CONNECTOR_TYPES.has(connector.type);
  const schemas = summary?.selectedSchemaCount ?? 0;
  const catalogs = summary?.selectedCatalogCount ?? 0;
  const tables = summary?.tableCount ?? 0;
  if (schemas === 0 && tables === 0) {
    return (
      <div className="mt-2 min-w-0 pl-10 sm:mt-0 sm:pl-0">
        <span className="sr-only">Tables: </span>
        <span className="type-meta block truncate text-foreground-secondary">No tables chosen</span>
        {canChooseTables ? <Link href={catalogHref(workspaceId, { connector: connector.id, choose: true })} className="type-status relative z-10 mt-0.5 inline-block text-foreground underline underline-offset-4 focus-visible:outline focus-visible:outline-[2px] focus-visible:outline-offset-2 focus-visible:outline-[var(--focus-ring)]">Choose tables</Link> : null}
      </div>
    );
  }
  const scope = [threeDepth ? countLabel(catalogs, "catalog", "catalogs") : null, countLabel(schemas, "schema", "schemas")].filter(Boolean).join(" · ");
  return <CellLines label="Tables" primary={tables > 0 ? countLabel(tables, "table", "tables") : "No tables loaded"} secondary={scope} tone={tables > 0 ? "default" : "muted"} />;
}

function LastRefreshCell({ summaries, summary }: { summaries: ConnectorSummaries; summary: CatalogConnectorSummary | null }) {
  if (summaries.status !== "loaded") return <CellLines label="Last refresh" primary={summaries.status === "loading" ? "…" : "Unavailable"} secondary={null} tone="muted" />;
  const refresh = summary?.lastRefresh ?? null;
  if (!refresh) return <CellLines label="Last refresh" primary={summary && summary.selectedSchemaCount > 0 ? "Never refreshed" : "—"} secondary={null} tone="muted" />;
  const lastGood = summary?.lastSuccessfulRefreshAt ? `last good ${formatShortDate(summary.lastSuccessfulRefreshAt)}` : null;
  if (refresh.state === "RUNNING" || refresh.state === "CANCEL_REQUESTED") {
    return <CellLines label="Last refresh" primary={<span className="inline-flex items-center gap-1.5"><WorkStatus state={refresh.state} labelDisplay="external" size="sm" />Refreshing</span>} secondary={`Started ${formatRefreshTime(refresh.requestedAt)}`} tone="default" />;
  }
  const finished = refresh.finishedAt ? formatRefreshTime(refresh.finishedAt) : formatRefreshTime(refresh.requestedAt);
  if (refresh.entryFailureCode) {
    return <CellLines label="Last refresh" primary={`Failed · ${failureCause(refresh.entryFailureCode)}`} secondary={[finished, lastGood].filter(Boolean).join(" · ")} tone="error" />;
  }
  if (refresh.state === "CANCELLED") return <CellLines label="Last refresh" primary="Cancelled" secondary={[finished, lastGood].filter(Boolean).join(" · ")} tone="muted" />;
  if (refresh.errorCount > 0) return <CellLines label="Last refresh" primary={finished} secondary={countLabel(refresh.errorCount, "error", "errors")} tone="default" />;
  return <CellLines label="Last refresh" primary={finished} secondary={null} tone="default" />;
}
