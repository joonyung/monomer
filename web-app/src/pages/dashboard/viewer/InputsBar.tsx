import { useEffect, useId, useLayoutEffect, useRef, useState } from "react";
import { Check } from "lucide-react";
import { resultsFromLabel } from "@/components/dashboard/capture/provenance";
import { dashboardInputsSummary, type DashboardInputChip } from "@/components/dashboard/DashboardParameterForm";
import { Button } from "@/components/ui/Button";
import { WorkStatus } from "@/components/ui/WorkStatus";
import { fittingInputChipCount, type DashboardResultsStatus } from "./inputs";

const CHIP_GAP = 8;
const OVERFLOW_WIDTH = 48;

function InputChip({ chip }: { chip: DashboardInputChip }) {
  return <span className="inline-flex h-8 max-w-[280px] shrink-0 items-center gap-1.5 rounded-[4px] border border-border px-2.5 type-control text-foreground" title={`${chip.label}: ${chip.value}`}>
    <span className="shrink-0 text-foreground-secondary">{chip.label}</span><span className="min-w-0 truncate tabular-nums">{chip.value}</span>
  </span>;
}

function ResultsStatus({ status }: { status: DashboardResultsStatus }) {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const timer = window.setInterval(() => setNow(Date.now()), 60_000);
    return () => window.clearInterval(timer);
  }, []);
  if (status.kind === "running") return <span className="type-meta text-foreground-secondary"><WorkStatus state="RUNNING" labelDisplay="inline" size="sm" /></span>;
  if (status.kind === "none") return null;
  return <span className="type-meta inline-flex min-w-0 items-center gap-1.5 text-foreground-secondary">
    <Check className="h-3.5 w-3.5 shrink-0" aria-hidden="true" />
    <time dateTime={status.at} title={new Date(status.at).toLocaleString("en-US")} className="truncate tabular-nums">{resultsFromLabel(status.at, now)}</time>
  </span>;
}

function OverflowChips({ chips, hiddenCount }: { chips: readonly DashboardInputChip[]; hiddenCount: number }) {
  const popoverId = useId();
  const popover = useRef<HTMLDivElement>(null);
  const trigger = useRef<HTMLButtonElement>(null);
  const [open, setOpen] = useState(false);
  const [position, setPosition] = useState({ top: 0, left: 0 });
  return <>
    <button ref={trigger} type="button" aria-label={`Show all ${chips.length} inputs`} aria-expanded={open} aria-controls={popoverId}
      className="inline-flex h-8 shrink-0 items-center rounded-[4px] border border-border px-2.5 type-control tabular-nums text-foreground hover:bg-surface focus-visible:outline focus-visible:outline-2 focus-visible:outline-focus-ring"
      onClick={() => {
        if (popover.current?.matches(":popover-open")) { popover.current.hidePopover(); return; }
        const bounds = trigger.current?.getBoundingClientRect();
        if (bounds) setPosition({ top: bounds.bottom + 6, left: Math.max(8, Math.min(bounds.left, window.innerWidth - 328)) });
        popover.current?.showPopover();
      }}
    >+{hiddenCount}</button>
    <div ref={popover} id={popoverId} popover="auto" role="dialog" aria-label="Inputs" onToggle={(event) => setOpen(event.newState === "open")} style={position}
      className="fixed m-0 w-80 max-w-[calc(100vw-16px)] rounded-[6px] border border-border bg-background px-4 py-2 text-foreground shadow-overlay">
      <dl>
        {chips.map((chip) => <div key={chip.name} className="grid grid-cols-[minmax(0,2fr)_minmax(0,3fr)] items-baseline gap-3 border-b border-border-light py-2.5 last:border-0">
          <dt className="type-meta wrap-anywhere text-foreground-secondary">{chip.label}</dt>
          <dd className="type-control wrap-anywhere tabular-nums text-foreground">{chip.value}</dd>
        </div>)}
      </dl>
    </div>
  </>;
}

export function DashboardInputsBar({ chips, status, timeZone, onChangeInputs }: {
  chips: readonly DashboardInputChip[];
  status: DashboardResultsStatus;
  /** "Times in Asia/Seoul" for the displayed results, or null when none recorded a zone. */
  timeZone: string | null;
  onChangeInputs?: () => void;
}) {
  const region = useRef<HTMLDivElement>(null);
  const measure = useRef<HTMLDivElement>(null);
  const change = useRef<HTMLSpanElement>(null);
  const [visibleCount, setVisibleCount] = useState(chips.length);
  const chipSignature = chips.map((chip) => `${chip.label}\u0000${chip.value}`).join("\u0001");
  useLayoutEffect(() => {
    const element = region.current;
    if (!element) return;
    const fit = () => {
      const widths = [...(measure.current?.children ?? [])].map((child) => (child as HTMLElement).offsetWidth);
      const reserved = change.current ? change.current.offsetWidth + CHIP_GAP : 0;
      setVisibleCount(fittingInputChipCount(widths, element.clientWidth - reserved, CHIP_GAP, OVERFLOW_WIDTH));
    };
    fit();
    const observer = new ResizeObserver(fit);
    observer.observe(element);
    return () => observer.disconnect();
  }, [chipSignature]);
  if (chips.length === 0 && status.kind === "none") return null;
  const hidden = chips.slice(visibleCount);
  return <section aria-label="Inputs and results" className="shrink-0 border-b border-border px-4 py-2 sm:px-6">
    <div className="hidden min-h-10 items-center gap-3 sm:flex">
      {chips.length > 0 ? <span className="type-group shrink-0 text-foreground-secondary">Inputs</span> : null}
      <div ref={region} className="relative flex min-w-0 flex-1 items-center gap-2 overflow-hidden">
        <div ref={measure} aria-hidden="true" className="invisible absolute left-0 top-0 flex gap-2">{chips.map((chip) => <InputChip key={chip.name} chip={chip} />)}</div>
        {chips.length > 0 ? <ul aria-label="Inputs" className="flex min-w-0 items-center gap-2">
          {chips.slice(0, visibleCount).map((chip) => <li key={chip.name} className="flex min-w-0"><InputChip chip={chip} /></li>)}
          {hidden.length > 0 ? <li className="flex"><OverflowChips chips={chips} hiddenCount={hidden.length} /></li> : null}
        </ul> : null}
        {onChangeInputs ? <span ref={change} className="flex shrink-0"><Button type="button" variant="ghost" size="sm" onClick={onChangeInputs}>Change inputs</Button></span> : null}
      </div>
      <div className="ml-auto flex shrink-0 items-center gap-3"><ResultsStatus status={status} />{timeZone ? <span className="type-meta text-foreground-secondary">{timeZone}</span> : null}</div>
    </div>
    <div className="sm:hidden">
      <div className="flex min-h-5 flex-wrap items-center gap-x-3"><ResultsStatus status={status} />{timeZone ? <span className="type-meta text-foreground-secondary">{timeZone}</span> : null}</div>
      {chips.length > 0 || onChangeInputs ? <div className="mt-1.5 flex items-center justify-between gap-3">
        <span className="min-w-0 truncate type-control text-foreground" title={dashboardInputsSummary(chips, "values")}>{dashboardInputsSummary(chips, "values")}</span>
        {onChangeInputs ? <Button type="button" variant="secondary" size="sm" onClick={onChangeInputs} aria-label="Change inputs">Change</Button> : null}
      </div> : null}
    </div>
  </section>;
}
