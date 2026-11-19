import { useId, useMemo, useState } from "react";
import { Check } from "lucide-react";
import { Button } from "@/components/ui/Button";
import { Input } from "@/components/ui/Input";
import { setWorkspaceReportingTimeZone, UnsupportedReportingTimeZoneError } from "@/lib/auth/graphql";
import { SettingsDialog } from "./SettingsDialog";
import {
  filterReportingTimeZoneOptions,
  REPORTING_TIME_ZONE_CHANGE_NOTICE,
  reportingTimeZoneLabel,
  reportingTimeZoneOptions,
} from "./workspaceSettingsModel";

// Choosing a zone and confirming it are two steps of one dialog, so the confirmation can't be skipped
// and Back keeps the search and choice.
export function ReportingTimeZoneDialog({
  workspace,
  onClose,
  onChanged,
}: {
  workspace: { id: string; reportingTimeZone: string };
  onClose: () => void;
  onChanged: () => Promise<unknown>;
}) {
  const listId = useId();
  const [now] = useState(() => new Date());
  const options = useMemo(() => reportingTimeZoneOptions(now), [now]);
  const [search, setSearch] = useState("");
  const [selected, setSelected] = useState(workspace.reportingTimeZone);
  const [activeIndex, setActiveIndex] = useState(-1);
  const [confirming, setConfirming] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const matches = filterReportingTimeZoneOptions(options, search);
  const active = matches[activeIndex] ?? null;
  const changed = selected !== workspace.reportingTimeZone;

  const choose = (value: string) => {
    setSelected(value);
    setError(null);
  };
  const save = async () => {
    if (!changed || busy) return;
    setBusy(true);
    setError(null);
    try {
      await setWorkspaceReportingTimeZone(workspace.id, selected);
      await onChanged();
      onClose();
    } catch (cause) {
      if (cause instanceof UnsupportedReportingTimeZoneError) setConfirming(false);
      setError(cause instanceof Error ? cause.message : "Couldn't change the Reporting time zone. Try again.");
    } finally {
      setBusy(false);
    }
  };

  if (confirming) {
    return (
      <SettingsDialog
        title="Change the Reporting time zone?"
        size="sm"
        role="alertdialog"
        busy={busy}
        onClose={onClose}
        footer={
          <>
            <Button variant="ghost" disabled={busy} onClick={() => { setError(null); setConfirming(false); }}>Back</Button>
            <Button autoFocus pending={busy} onClick={() => void save()}>Change time zone</Button>
          </>
        }
      >
        <p className="type-body text-foreground">
          {reportingTimeZoneLabel(workspace.reportingTimeZone, now)} <span aria-hidden="true">→</span><span className="sr-only">to</span> {reportingTimeZoneLabel(selected, now)}
        </p>
        <p className="mt-3 type-body text-foreground-secondary">{REPORTING_TIME_ZONE_CHANGE_NOTICE}</p>
        {error && <p role="alert" className="mt-4 type-body text-error">{error}</p>}
      </SettingsDialog>
    );
  }

  return (
    <SettingsDialog
      title="Reporting time zone"
      description="Days and times in new Semantic results use this zone."
      size="sm"
      onClose={onClose}
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>Cancel</Button>
          <Button disabled={!changed} onClick={() => { setError(null); setConfirming(true); }}>Continue</Button>
        </>
      }
    >
      <Input
        id="reporting-time-zone-search"
        label="Search time zones"
        autoFocus
        role="combobox"
        aria-expanded={matches.length > 0}
        aria-controls={matches.length > 0 ? listId : undefined}
        aria-autocomplete="list"
        aria-activedescendant={active ? `${listId}-${activeIndex}` : undefined}
        value={search}
        placeholder="City, region, or GMT offset"
        onChange={(event) => { setSearch(event.target.value); setActiveIndex(-1); }}
        onKeyDown={(event) => {
          if (event.key === "ArrowDown" || event.key === "ArrowUp") {
            event.preventDefault();
            if (matches.length === 0) return;
            const step = event.key === "ArrowDown" ? 1 : -1;
            setActiveIndex((current) => current < 0 ? (step > 0 ? 0 : matches.length - 1) : Math.max(0, Math.min(matches.length - 1, current + step)));
          } else if (event.key === "Enter") {
            event.preventDefault();
            if (active) choose(active.value);
          }
        }}
      />
      {matches.length === 0 ? (
        <p role="status" className="mt-3 rounded-[4px] border border-border px-3 py-2.5 type-meta text-foreground-secondary">No time zone matches “{search.trim()}”.</p>
      ) : <div
        id={listId}
        role="listbox"
        aria-label="Time zones"
        className="mt-3 max-h-[min(320px,45vh)] overflow-y-auto rounded-[4px] border border-border"
      >
        {matches.map((option, index) => {
          const isSelected = option.value === selected;
          return (
            <div
              key={option.value}
              id={`${listId}-${index}`}
              role="option"
              aria-selected={isSelected}
              ref={index === activeIndex ? (element) => element?.scrollIntoView({ block: "nearest" }) : undefined}
              onMouseDown={(event) => event.preventDefault()}
              onClick={() => { setActiveIndex(index); choose(option.value); }}
              className={`flex cursor-pointer items-center gap-2 px-3 py-2 type-control tabular-nums ${index === activeIndex ? "bg-surface" : "hover:bg-surface"} ${isSelected ? "text-foreground" : "text-foreground-secondary"}`}
            >
              <Check className={`h-3.5 w-3.5 shrink-0 ${isSelected ? "" : "invisible"}`} aria-hidden="true" />
              <span className="min-w-0 break-words">{option.label}</span>
            </div>
          );
        })}
      </div>}
      {error && <p role="alert" className="mt-4 type-body text-error">{error}</p>}
    </SettingsDialog>
  );
}
