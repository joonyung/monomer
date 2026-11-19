import { useEffect, useRef, useState } from "react";
import { Download } from "lucide-react";
import { exploreAPI } from "@/api/explore";
import { formatExactTime } from "@/components/runs";
import { formatResultBytes } from "@/components/runs/clock";
import { Button } from "@/components/ui/Button";
import { IconButton } from "@/components/ui/IconButton";
import { Menu, MenuContent, MenuItem, MenuTrigger } from "@/components/ui/Menu";
import { toExploreErrorPresentation } from "@/api/explore-errors";
import { ApiError } from "@/lib/api/client";
import { readResultBlob, writeResultResponse, type ResultFormat, type SaveResultWindow } from "./result-download";

function saveBlob(blob: Blob, fileName: string): void {
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement("a");
  anchor.href = url;
  anchor.download = fileName;
  anchor.rel = "noopener";
  document.body.append(anchor);
  anchor.click();
  anchor.remove();
  window.setTimeout(() => URL.revokeObjectURL(url), 0);
}

export function ExploreResultDownload({ workspaceId, runId, expiresAt, byteSize }: {
  workspaceId: string;
  runId: string;
  expiresAt: string | null;
  /** Size of the stored Arrow original, shown beside that format. */
  byteSize: string | null;
}) {
  const [pending, setPending] = useState<ResultFormat | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const active = useRef<AbortController | null>(null);
  const directSave = typeof window !== "undefined" && typeof (window as SaveResultWindow).showSaveFilePicker === "function";
  const expired = expiresAt !== null && Date.parse(expiresAt) <= Date.now();
  useEffect(() => () => { active.current?.abort(); active.current = null; }, []);
  async function download(format: ResultFormat) {
    if (active.current || expired) return;
    const controller = new AbortController();
    active.current = controller;
    const fileName = `explore-${runId}.${format}`;
    let writable: WritableStream<Uint8Array> | undefined;
    setNotice(null); setPending(format);
    try {
      if (directSave) {
        // The picker must run in the click gesture before authentication or network awaits.
        const handle = await (window as SaveResultWindow).showSaveFilePicker!({
          suggestedName: fileName,
          types: [{ description: format === "csv" ? "CSV" : "Arrow original", accept: { [format === "csv" ? "text/csv" : "application/vnd.apache.arrow.file"]: [`.${format}`] } }],
        });
        controller.signal.throwIfAborted();
        writable = await handle.createWritable();
        controller.signal.throwIfAborted();
        const response = await exploreAPI.downloadResult({ workspaceId, runId, format, signal: controller.signal });
        await writeResultResponse(response, writable, format, controller.signal);
      } else {
        const response = await exploreAPI.downloadResult({ workspaceId, runId, format, signal: controller.signal });
        saveBlob(await readResultBlob(response, format, controller.signal), fileName);
      }
    } catch (error) {
      controller.abort();
      if (writable && !writable.locked) await writable.abort().catch(() => undefined);
      if (active.current === controller && !(error instanceof DOMException && error.name === "AbortError")) {
        const problem = toExploreErrorPresentation(error);
        setNotice(error instanceof ApiError ? [problem?.detail, problem?.code, problem?.retryable ? "You can try this download again." : null, problem?.traceId ? `Reference ${problem.traceId}` : null].filter(Boolean).join(" · ") : "Download failed. The incomplete file was discarded. Try downloading again.");
      }
    } finally {
      if (active.current === controller) { active.current = null; setPending(null); }
    }
  }
  // The availability window only matters when saving the result, so it sits with the formats.
  return <div className="type-status flex flex-col items-end text-right text-foreground-secondary">
    <div className="flex items-center justify-end gap-1">
      {pending ? <><span role="status">Saving {pending.toUpperCase()}…</span><Button type="button" variant="ghost" size="sm" onClick={() => active.current?.abort()}>Cancel download</Button></> : null}
      {expired ? <span>Expired</span> : null}
      <Menu>
        <MenuTrigger asChild>
          <IconButton label={expired ? "Download unavailable: this result has expired" : "Download result"} icon={Download} pending={pending !== null} disabled={expired} />
        </MenuTrigger>
        <MenuContent align="end" className="w-64">
          <MenuItem onSelect={() => { void download("csv"); }}>
            <span className="min-w-0 flex-1">Download CSV</span><span className="type-status text-foreground-secondary">Up to 1 GB</span>
          </MenuItem>
          <MenuItem onSelect={() => { void download("arrow"); }}>
            <span className="min-w-0 flex-1">Download Arrow original</span>{byteSize ? <span className="type-status tabular-nums text-foreground-secondary">{formatResultBytes(byteSize)}</span> : null}
          </MenuItem>
          {expiresAt ? <p className="type-status mt-1 border-t border-border-light px-3 pb-1 pt-2 tabular-nums text-foreground-secondary">Available until {formatExactTime(expiresAt)}</p> : null}
        </MenuContent>
      </Menu>
    </div>
    {notice ? <p role="alert" className="mt-1 max-w-[320px] text-error">{notice}</p> : null}
  </div>;
}
