import { useEffect, useRef, useState } from "react";
import { Check, ChevronDown } from "lucide-react";
import { ConnectorTypeMark } from "@/components/connectors/ConnectorTypeMark";
import { Button } from "@/components/ui/Button";
import type { Connector } from "@/lib/types/connector";

const CONNECTOR_CONTROL_CLASS = "type-control -ml-1.5 inline-flex h-9 min-w-9 max-w-[220px] items-center gap-1.5 rounded-[4px] bg-background pl-1.5 pr-2 text-foreground sm:min-w-[196px] sm:max-w-[240px]";

function ConnectorValue({ connector }: { connector: Connector | null }) {
  return <>
    {connector ? <ConnectorTypeMark type={connector.type} size="sm" /> : null}
    <span className={`min-w-0 flex-1 truncate text-left ${connector ? "" : "text-foreground-secondary"}`}>{connector?.name ?? "Choose a connector"}</span>
  </>;
}

export function ReadOnlyConnector({ connector }: { connector: Connector }) {
  return <span className={CONNECTOR_CONTROL_CLASS} title="Connector with the source tables of these metrics"><ConnectorValue connector={connector} /></span>;
}

function connectorTypeLabel(type: Connector["type"]): string {
  if (type === "DUCKDB") return "Sample data";
  if (type === "POSTGRESQL") return "PostgreSQL";
  if (type === "BIGQUERY") return "GoogleSQL";
  return type.charAt(0) + type.slice(1).toLowerCase();
}

export function CompactConnectorSelect({ connectors, value, onChange, loading, error, onRetry }: { connectors: Connector[]; value: string | null; onChange: (value: string) => void; loading: boolean; error: string | null; onRetry: () => void }) {
  const selected = connectors.find((connector) => connector.id === value) ?? null;
  const selectedIndex = Math.max(0, connectors.findIndex((connector) => connector.id === selected?.id));
  const [open, setOpen] = useState(false);
  const [activeIndex, setActiveIndex] = useState(selectedIndex);
  const rootRef = useRef<HTMLDivElement>(null);
  const triggerRef = useRef<HTMLButtonElement>(null);
  const listboxRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!open || connectors.length === 0) return;
    setActiveIndex(selectedIndex);
    const frame = window.requestAnimationFrame(() => listboxRef.current?.focus());
    const onPointerDown = (event: PointerEvent) => {
      if (!rootRef.current?.contains(event.target as Node)) setOpen(false);
    };
    document.addEventListener("pointerdown", onPointerDown);
    return () => {
      window.cancelAnimationFrame(frame);
      document.removeEventListener("pointerdown", onPointerDown);
    };
  }, [connectors.length, open, selectedIndex]);

  if (loading) return <p className="type-control text-foreground-secondary">Loading connectors…</p>;
  if (error) return <Button type="button" variant="secondary" onClick={onRetry}>Retry connectors</Button>;
  if (connectors.length === 0) return <span className="type-meta text-foreground-secondary">No connector</span>;

  const choose = (index: number) => {
    onChange((connectors[index] as Connector).id);
    setOpen(false);
    window.requestAnimationFrame(() => triggerRef.current?.focus());
  };

  const onListboxKeyDown = (event: React.KeyboardEvent<HTMLDivElement>) => {
    if (event.key === "ArrowDown") {
      event.preventDefault();
      setActiveIndex((current) => (current + 1) % connectors.length);
    } else if (event.key === "ArrowUp") {
      event.preventDefault();
      setActiveIndex((current) => (current - 1 + connectors.length) % connectors.length);
    } else if (event.key === "Home") {
      event.preventDefault();
      setActiveIndex(0);
    } else if (event.key === "End") {
      event.preventDefault();
      setActiveIndex(connectors.length - 1);
    } else if (event.key === "Enter" || event.key === " ") {
      event.preventDefault();
      choose(activeIndex);
    } else if (event.key === "Escape") {
      event.preventDefault();
      setOpen(false);
      triggerRef.current?.focus();
    } else if (event.key === "Tab") {
      setOpen(false);
    }
  };

  return (
    <div ref={rootRef} className="relative min-w-0 max-w-full">
      <button
        ref={triggerRef}
        type="button"
        aria-label="Select connector"
        aria-haspopup="listbox"
        aria-expanded={open}
        onClick={() => setOpen((current) => !current)}
        className={`${CONNECTOR_CONTROL_CLASS} hover:bg-surface`}
      >
        <ConnectorValue connector={selected} />
        <ChevronDown className={`h-3.5 w-3.5 shrink-0 text-foreground-secondary transition-transform duration-150 motion-reduce:transition-none ${open ? "rotate-180" : ""}`} aria-hidden="true" />
      </button>
      {open ? (
        <div
          ref={listboxRef}
          role="listbox"
          aria-label="Connectors"
          aria-activedescendant={`connector-option-${(connectors[activeIndex] as Connector).id}`}
          tabIndex={0}
          onKeyDown={onListboxKeyDown}
          className="absolute -left-1.5 top-full z-30 mt-2 w-[272px] overflow-hidden rounded-[4px] border border-border bg-background py-1 shadow-overlay outline-none"
        >
          {connectors.map((connector, index) => {
            const isSelected = connector.id === selected?.id;
            const isActive = index === activeIndex;
            return (
              <button
                key={connector.id}
                id={`connector-option-${connector.id}`}
                type="button"
                role="option"
                aria-selected={isSelected}
                onPointerMove={() => setActiveIndex(index)}
                onClick={() => choose(index)}
                className={`flex min-h-[52px] w-full items-center gap-2 border-l-2 py-2 pl-2 pr-3 text-left ${isActive ? "border-[var(--focus-ring)] bg-surface" : "border-transparent"}`}
              >
                <ConnectorTypeMark type={connector.type} size="sm" />
                <span className="min-w-0 flex-1">
                  <span className="type-control block truncate text-foreground">{connector.name}</span>
                  <span className="type-status mt-0.5 block text-foreground-secondary">{connectorTypeLabel(connector.type)}</span>
                </span>
                {isSelected ? <Check className="h-4 w-4 shrink-0 text-focus-ring" aria-hidden="true" /> : null}
              </button>
            );
          })}
        </div>
      ) : null}
    </div>
  );
}
