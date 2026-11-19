import { ListChecks } from "lucide-react";
import { ConnectorTypeMark } from "@/components/connectors/ConnectorTypeMark";
import { CONNECTOR_TYPE_META } from "@/components/connectors/connectorTypeMeta";
import { Button } from "@/components/ui/Button";
import { Select } from "@/components/ui/Select";
import type { Connector } from "@/lib/types/connector";

export interface CatalogScopeSelection {
  connectorId: string;
  catalogName: string | null;
}

export type CatalogNamesState =
  | { status: "loading" }
  | { status: "error" }
  | { status: "loaded"; catalogNames: string[] };


interface CatalogScopeSwitcherProps {
  connectors: Connector[];
  selection: CatalogScopeSelection;
  // Saved catalog membership of the selected connector; null for a connector without catalogs.
  catalogs: CatalogNamesState | null;
  onSelect: (selection: CatalogScopeSelection) => void;
  onManageCatalogs: (() => void) | null;
}

function catalogPlaceholder(catalogs: CatalogNamesState): string {
  if (catalogs.status === "loading") return "Loading catalogs…";
  if (catalogs.status === "error") return "Catalogs unavailable";
  return "No catalogs selected";
}

// The connector and catalog are the outer browse levels; the schema/table columns carry the marker.
// The catalog picker uses a second scope row on a phone.
export function CatalogScopeSwitcher({ connectors, selection, catalogs, onSelect, onManageCatalogs }: CatalogScopeSwitcherProps) {
  const connector = connectors.find((candidate) => candidate.id === selection.connectorId) ?? null;
  const catalogNames = catalogs?.status === "loaded" ? catalogs.catalogNames : [];
  const catalogKnown = selection.catalogName !== null && catalogNames.includes(selection.catalogName);
  return (
    <div data-testid="catalog-scope-switcher" className="flex min-w-0 flex-1 flex-wrap items-center gap-2">
      <div className="flex min-w-0 flex-1 items-center gap-2 sm:w-[260px] sm:flex-initial">
        {connector ? <ConnectorTypeMark type={connector.type} size="sm" /> : null}
        <Select
          label="Connector"
          value={selection.connectorId}
          currentLabel={connector?.name}
          options={connectors.map((candidate) => ({ value: candidate.id, label: `${candidate.name} · ${CONNECTOR_TYPE_META[candidate.type].label}` }))}
          disabled={connectors.length === 0}
          invalid={false}
          onChange={(connectorId) => {
            if (connectorId !== selection.connectorId) onSelect({ connectorId, catalogName: null });
          }}
          className="min-w-0 flex-1"
        />
      </div>
      {catalogs ? (
        <div className="flex w-full min-w-0 items-center gap-2 max-sm:order-2 sm:contents">
          <Select
            label="Catalog"
            value={selection.catalogName ?? ""}
            currentLabel={catalogKnown ? undefined : selection.catalogName ?? catalogPlaceholder(catalogs)}
            options={catalogNames.map((name) => ({ value: name, label: name }))}
            disabled={catalogNames.length === 0}
            invalid={false}
            onChange={(catalogName) => onSelect({ connectorId: selection.connectorId, catalogName })}
            className="min-w-0 flex-1 sm:w-[200px] sm:flex-initial"
          />
          {onManageCatalogs ? (
            <Button type="button" variant="ghost" size="sm" onClick={onManageCatalogs}>
              <ListChecks aria-hidden="true" />
              Manage catalogs
            </Button>
          ) : null}
        </div>
      ) : null}
    </div>
  );
}
