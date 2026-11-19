import { DashboardCapture } from "@/components/dashboard/capture/DashboardCapture";
import { resultsFromLabel, type CaptureInput, type CaptureProvenance } from "@/components/dashboard/capture/provenance";
import { useState, type ReactNode, type RefObject } from "react";
import { History } from "lucide-react";
import { WorkspacePageHeader } from "@/components/layout/WorkspacePageHeader";
import { IconButton } from "@/components/ui/IconButton";
import { useTransientScrollbar } from "@/components/ui/useTransientScrollbar";
import { PageShareActions } from "./PageShareActions";

export function PageViewerActions({ sessionId, historyOpen, onHistory, captureTarget, provenance, children }: {
  captureTarget?: RefObject<HTMLDivElement | null>; provenance: CaptureProvenance; sessionId?: string; historyOpen: boolean; onHistory?: () => void; children?: ReactNode;
}) {
  return <><PageShareActions sessionId={sessionId} />{captureTarget && <DashboardCapture key={sessionId} target={captureTarget} provenance={provenance} />}{onHistory && <IconButton label="Open history" icon={History} size="md" title="History" aria-haspopup="dialog" aria-expanded={historyOpen} onClick={onHistory} />}{children}</>;
}

export function PageViewerFrame({ title, description, actions, toolbar, aside, children }: {
  title: string; description?: ReactNode; actions?: ReactNode; toolbar?: ReactNode; aside?: ReactNode; children: ReactNode;
}) {
  const scrollbar = useTransientScrollbar();
  return <div className="flex h-full min-h-0 flex-col bg-background" style={{ fontFeatureSettings: '"ss01"' }}>
    <WorkspacePageHeader title={title} description={description} actions={actions} wrapTitle />{toolbar}
    <div className="flex min-h-0 flex-1"><main data-testid="dashboard-viewer-scroll" onScroll={scrollbar.onScroll} className={`min-h-0 min-w-0 flex-1 overflow-y-auto px-4 py-5 sm:px-6 ${scrollbar.className}`}>{children}</main>{aside}</div>
  </div>;
}

export function PageViewerContext({ inputs, resultsAt, timeZone }: { inputs: readonly CaptureInput[]; resultsAt: string | null; timeZone: string | null }) {
  const [now] = useState(() => Date.now());
  if (inputs.length === 0 && !resultsAt) return null;
  return <div aria-label="Inputs and results" role="group" className="mb-4 flex flex-wrap items-baseline gap-x-6 gap-y-2 type-meta text-foreground-secondary">
    {inputs.length > 0 && <span className="type-group">Inputs</span>}
    {inputs.map((input, index) => <span key={`${index}:${input.label}`} className="min-w-0 wrap-anywhere">{input.label}: <span className="text-foreground">{input.value}</span></span>)}
    {resultsAt && <time dateTime={resultsAt} title={new Date(resultsAt).toLocaleString("en-US")} className="tabular-nums sm:ml-auto">{resultsFromLabel(resultsAt, now)}</time>}
    {resultsAt && timeZone && <span>{timeZone}</span>}
  </div>;
}
