import { ActorProvenance, seededByMonomer } from "@/components/ui/ActorProvenance";
import { AgentMarker } from "@/components/ui/AgentMarker";
import { Button } from "@/components/ui/Button";
import { formatRelativeTime } from "@/lib/utils/relative-time";
import type { DefinitionChange } from "@/api/semantic-definition-history";

const CHANGE_LABELS = { CREATED: "Created", UPDATED: "Edited", APPLIED: "Applied", DELETED: "Deleted" } as const;

type HistoryChange = Omit<DefinitionChange, "definition" | "action"> & { definition: unknown; action: "CREATED" | "UPDATED" | "APPLIED" | "DELETED" };

export function DefinitionHistoryView({ changes, deleted, deletedMessage = "Deleted · This definition no longer appears in Semantics. Its recorded versions stay available.", canRestore, principalId, sampleWorkspace, restoringId, error, onRestore }: {
  changes: HistoryChange[]; deleted: boolean; deletedMessage?: string; canRestore: boolean; principalId: string | null;
  sampleWorkspace: boolean; restoringId: string | null; error: string | null; onRestore: (changeId: string) => void;
}) {
  return <>
    {deleted && <p role="status" className="type-body mb-6 text-foreground-secondary">{deletedMessage}</p>}
    {error && <p role="alert" className="type-body mb-4 text-error">{error}</p>}
    <ol className="divide-y divide-border">{changes.map((change, index) => {
      const restoreDeleted = deleted && index === 0 && change.action === "DELETED";
      return <li key={change.changeId} className="py-5 first:pt-0">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div><h2 className="type-item text-foreground">{CHANGE_LABELS[change.action]}</h2>
          <div className="type-meta mt-1 flex flex-wrap items-center gap-x-1.5 text-foreground-secondary">
            {change.actor || seededByMonomer(sampleWorkspace, change.actor, change.credentialSurface) ? <ActorProvenance verb={null} actor={change.actor} surface={change.credentialSurface} agentApp={change.agentApp} principalId={principalId} sampleDefinition={sampleWorkspace} time={null} /> : <><span>Person not recorded</span>{change.credentialSurface === "AGENT" && <AgentMarker app={change.agentApp} focusable />}</>}
            {change.credentialSurface === "WEB" && <span>via Web</span>}
            <span aria-hidden="true">·</span><time dateTime={change.occurredAt} title={new Date(change.occurredAt).toLocaleString("en-US")} className="tabular-nums">{formatRelativeTime(change.occurredAt)}</time>
          </div>
        </div>
        {canRestore && change.restorable && <Button variant={restoreDeleted ? "primary" : "secondary"} size="sm" pending={restoringId === change.changeId} disabled={restoringId !== null} onClick={() => onRestore(change.changeId)}>{restoreDeleted ? "Restore" : "Restore this version"}</Button>}
      </div>
      <details className="mt-3"><summary className="type-control cursor-pointer text-foreground">View definition</summary><pre className="type-mono mt-3 max-h-[480px] overflow-auto rounded-md border border-border bg-surface p-4 text-foreground">{JSON.stringify(change.definition, null, 2)}</pre></details>
    </li>;
    })}</ol>
  </>;
}
