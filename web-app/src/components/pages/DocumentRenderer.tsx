import { useEffect, useMemo, useRef, useState } from "react";
import { useVirtualizer } from "@tanstack/react-virtual";
import ReactMarkdown, { type Components } from "react-markdown";
import { pageContentLimits, type PageContentFormat } from "@/api/page-content";
import { Button } from "@/components/ui/Button";
import { StateBlock } from "@/components/ui/StateBlock";
import type { NotebookCell, NotebookOutput, NotebookWorkerResponse } from "./notebookDocumentTypes";
import { staticHtmlChildDocument } from "./staticHtmlDocument";
import styles from "./DocumentPageSurface.module.css";

interface DocumentRendererProps {
  bytes: Uint8Array<ArrayBuffer>;
  format: PageContentFormat;
  title: string;
  downloading: boolean;
  onDownload: () => void;
}

const SAFE_LINK_PROTOCOLS = new Set(["http:", "https:", "mailto:"]);
function safeHref(raw: string | undefined): string | undefined {
  if (!raw) return undefined;
  try {
    const url = new URL(raw, window.location.origin);
    return SAFE_LINK_PROTOCOLS.has(url.protocol) ? url.toString() : undefined;
  } catch {
    return undefined;
  }
}

const markdownComponents: Components = {
  a: ({ href, children, ...props }) => {
    const safe = safeHref(href);
    return safe
      ? <a {...props} href={safe} target="_blank" rel="noopener noreferrer">{children}</a>
      : <span>{children}</span>;
  },
  img: () => null,
  table: ({ children, ...props }) => (
    <div className="document-table-scroll" role="region" aria-label="Scrollable table" tabIndex={0}>
      <table {...props}>{children}</table>
    </div>
  ),
};

function decodeUtf8(bytes: Uint8Array<ArrayBuffer>): string {
  return new TextDecoder("utf-8", { fatal: true }).decode(bytes);
}

function Markdown({ source }: { source: string }) {
  return (
    <article className={`document-content ${styles.markdownContent}`}>
      <ReactMarkdown components={markdownComponents} skipHtml>{source}</ReactMarkdown>
    </article>
  );
}

function Failure({ reason, downloading, onDownload }: { reason: string; downloading: boolean; onDownload: () => void }) {
  return (
    <div className="rounded-[6px] border border-error/20 bg-background shadow-ambient">
      <StateBlock
        kind="error"
        layout="page"
        title={<span role="heading" aria-level={2}>This document cannot be displayed safely</span>}
        description={<>
          <span className="block">{reason}</span>
          <span className="mt-2 block">The stored PageVersion is intact. You can still download the exact original.</span>
        </>}
        action={(
          <Button variant="secondary" size="sm" onClick={onDownload} disabled={downloading}>
            Download original
          </Button>
        )}
      />
    </div>
  );
}

function NotebookOutputView({ output }: { output: NotebookOutput }) {
  if (output.kind === "image") {
    return <img src={`data:${output.mime};base64,${output.base64}`} alt="Stored Notebook output" className="max-h-[680px] max-w-full object-contain" />;
  }
  if (output.kind === "markdown") return <Markdown source={output.markdown} />;
  if (output.kind === "text") {
    return (
      <div>
        <p className="mb-1 type-group text-foreground-secondary">{output.label}</p>
        <pre className="overflow-x-auto whitespace-pre-wrap rounded-[4px] bg-surface p-3 type-mono leading-relaxed text-foreground">{output.text}</pre>
      </div>
    );
  }
  return (
    <div className="rounded-[4px] border border-border bg-surface px-3 py-2 type-meta text-foreground-secondary">
      <p>{output.reason}</p>
      {output.mimeKinds.length > 0 && <p className="mt-1 font-mono type-status">{output.mimeKinds.join(", ")}</p>}
    </div>
  );
}

function NotebookCellView({ cell }: { cell: NotebookCell }) {
  return (
    <section className="overflow-hidden rounded-[6px] border border-border bg-background">
      <div className="flex items-center justify-between border-b border-border bg-surface px-3 py-2 type-group text-foreground-secondary">
        <span>{cell.kind}</span>
        {cell.executionCount !== null && <span className="tabular-nums">[{cell.executionCount}]</span>}
      </div>
      {cell.kind === "markdown"
        ? <Markdown source={cell.source} />
        : <pre className="overflow-x-auto p-4 type-mono leading-relaxed text-foreground"><code>{cell.source}</code></pre>}
      {cell.outputs.length > 0 && (
        <div className="space-y-3 border-t border-border px-4 py-3">
          {cell.outputs.map((output, index) => <NotebookOutputView key={`${cell.id}-output-${index}`} output={output} />)}
        </div>
      )}
    </section>
  );
}

function NotebookDocument({ source, downloading, onDownload }: { source: string; downloading: boolean; onDownload: () => void }) {
  const [result, setResult] = useState<NotebookWorkerResponse | null>(null);
  const scrollRef = useRef<HTMLDivElement>(null);
  useEffect(() => {
    setResult(null);
    const worker = new Worker(new URL("./notebookDocument.worker.ts", import.meta.url), { type: "module" });
    worker.addEventListener("message", (event: MessageEvent<NotebookWorkerResponse>) => setResult(event.data));
    worker.addEventListener("error", () => setResult({ ok: false, reason: "The Notebook renderer could not read this file." }));
    worker.postMessage(source);
    return () => worker.terminate();
  }, [source]);
  const cells = result?.ok ? result.cells : [];
  const virtualizer = useVirtualizer({
    count: cells.length,
    getScrollElement: () => scrollRef.current,
    estimateSize: () => 260,
    measureElement: (element) => element.getBoundingClientRect().height,
    overscan: 4,
  });

  if (result === null) {
    return <div className="rounded-[6px] border border-border bg-background"><StateBlock kind="loading" layout="inline" title="Reading stored Notebook cells…" /></div>;
  }
  if (!result.ok) return <Failure reason={result.reason} downloading={downloading} onDownload={onDownload} />;
  return (
    <div className="overflow-hidden rounded-[6px] border border-border bg-background shadow-ambient">
      <div className="border-b border-border bg-surface px-4 py-2.5 type-meta text-foreground-secondary">
        Stored cells and outputs · no cells are executed
      </div>
      <div ref={scrollRef} className="h-[min(760px,72vh)] overflow-y-auto p-4">
        <div className="relative w-full" style={{ height: virtualizer.getTotalSize() }}>
          {virtualizer.getVirtualItems().map((item) => (
            <div
              key={cells[item.index]?.id ?? item.key}
              ref={virtualizer.measureElement}
              data-index={item.index}
              className="absolute left-0 top-0 w-full pb-4"
              style={{ transform: `translateY(${item.start}px)` }}
            >
              <NotebookCellView cell={cells[item.index] as NotebookCell} />
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}

function StaticHtmlDocument({ source, title }: { source: string; title: string }) {
  const srcDoc = useMemo(() => staticHtmlChildDocument(source), [source]);
  return (
    <div className="overflow-hidden rounded-[6px] border border-border bg-background shadow-ambient">
      <div className="flex items-center justify-between gap-3 border-b border-border bg-surface px-4 py-2.5">
        <p className="type-meta text-foreground-secondary">Static document · scripts and network access disabled</p>
        <span className="rounded-[4px] border border-border bg-background px-2 py-0.5 type-status text-label">Isolated content</span>
      </div>
      <iframe
        srcDoc={srcDoc}
        title={`${title} static document`}
        sandbox=""
        referrerPolicy="no-referrer"
        className="h-[min(760px,72vh)] min-h-[440px] w-full border-0 bg-white"
      />
    </div>
  );
}

export function DocumentRenderer({ bytes, format, title, downloading, onDownload }: DocumentRendererProps) {
  if (bytes.byteLength > pageContentLimits.maximumSourceBytes) {
    return <Failure reason="This document exceeds the 20 MiB Pages limit." downloading={downloading} onDownload={onDownload} />;
  }
  let source: string;
  try {
    source = decodeUtf8(bytes);
  } catch {
    return <Failure reason={`${format === "NOTEBOOK" ? "Notebook" : format === "HTML" ? "HTML" : "Markdown"} source is not valid UTF-8.`} downloading={downloading} onDownload={onDownload} />;
  }
  if (format === "MARKDOWN") {
    return <div className="overflow-hidden rounded-[6px] border border-border bg-background shadow-ambient"><Markdown source={source} /></div>;
  }
  if (format === "NOTEBOOK") return <NotebookDocument source={source} downloading={downloading} onDownload={onDownload} />;
  return <StaticHtmlDocument source={source} title={title} />;
}
