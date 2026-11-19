/// <reference lib="webworker" />

import type { NotebookCell, NotebookOutput, NotebookWorkerResponse } from "./notebookDocumentTypes";

const SAFE_IMAGE_MIME = ["image/png", "image/jpeg", "image/gif"] as const;

type JsonRecord = Record<string, unknown>;

function record(value: unknown): JsonRecord | null {
  return typeof value === "object" && value !== null && !Array.isArray(value)
    ? value as JsonRecord
    : null;
}

function sourceText(value: unknown): string | null {
  if (typeof value === "string") return value;
  if (Array.isArray(value) && value.every((line) => typeof line === "string")) return value.join("");
  return null;
}

function safeBundleOutput(dataValue: unknown): NotebookOutput {
  const data = record(dataValue);
  if (!data) return { kind: "omitted", mimeKinds: [], reason: "The output MIME bundle is malformed." };
  const mimeKinds = Object.keys(data).sort();
  for (const mime of SAFE_IMAGE_MIME) {
    const base64 = sourceText(data[mime]);
    if (base64) return { kind: "image", mime, base64 };
  }
  const markdown = sourceText(data["text/markdown"]);
  if (markdown !== null) return { kind: "markdown", markdown };
  const json = data["application/json"];
  if (json !== undefined) {
    const text = JSON.stringify(json, null, 2);
    return { kind: "text", label: "JSON output", text };
  }
  const plain = sourceText(data["text/plain"]);
  if (plain !== null) return { kind: "text", label: "Output", text: plain };
  return {
    kind: "omitted",
    mimeKinds,
    reason: mimeKinds.length === 0
      ? "The output has no displayable representation."
      : "Unsupported interactive output was omitted.",
  };
}

function parseOutput(value: unknown): NotebookOutput {
  const output = record(value);
  if (!output || typeof output.output_type !== "string") {
    return { kind: "omitted", mimeKinds: [], reason: "The output is malformed." };
  }
  if (output.output_type === "stream") {
    const text = sourceText(output.text);
    return text === null
      ? { kind: "omitted", mimeKinds: ["text/plain"], reason: "The stream output is malformed." }
      : { kind: "text", label: typeof output.name === "string" ? output.name : "Stream", text };
  }
  if (output.output_type === "error") {
    const text = sourceText(output.traceback);
    return text === null
      ? { kind: "omitted", mimeKinds: ["text/plain"], reason: "The error output is malformed." }
      : { kind: "text", label: "Error", text };
  }
  if (output.output_type === "display_data" || output.output_type === "execute_result") {
    return safeBundleOutput(output.data);
  }
  return { kind: "omitted", mimeKinds: [output.output_type], reason: "Unsupported output type." };
}

function parseNotebook(raw: string): NotebookWorkerResponse {
  let parsed: unknown;
  try {
    parsed = JSON.parse(raw);
  } catch {
    return { ok: false, reason: "The Notebook is not valid JSON." };
  }
  const notebook = record(parsed);
  if (!notebook || notebook.nbformat !== 4 || !Array.isArray(notebook.cells)) {
    return { ok: false, reason: "Only nbformat 4 Notebooks can be displayed." };
  }
  const cells: NotebookCell[] = notebook.cells.map((value, index) => {
    const cell = record(value);
    const source = sourceText(cell?.source);
    const kind = cell?.cell_type === "markdown" || cell?.cell_type === "code" || cell?.cell_type === "raw"
      ? cell.cell_type
      : "raw";
    const outputs = kind === "code" && Array.isArray(cell?.outputs)
      ? cell.outputs.map(parseOutput)
      : [];
    return {
      id: `${typeof cell?.id === "string" && cell.id ? cell.id : "cell"}-${index + 1}`,
      kind,
      source: source ?? "",
      executionCount: typeof cell?.execution_count === "number" ? cell.execution_count : null,
      outputs,
    };
  });
  return { ok: true, cells };
}

self.addEventListener("message", (event: MessageEvent<string>) => {
  self.postMessage(parseNotebook(event.data));
});
