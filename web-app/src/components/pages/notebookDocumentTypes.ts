export type NotebookOutput =
  | { kind: "text"; label: string; text: string }
  | { kind: "markdown"; markdown: string }
  | { kind: "image"; mime: "image/png" | "image/jpeg" | "image/gif"; base64: string }
  | { kind: "omitted"; mimeKinds: string[]; reason: string };

export interface NotebookCell {
  id: string;
  kind: "markdown" | "code" | "raw";
  source: string;
  executionCount: number | null;
  outputs: NotebookOutput[];
}

export type NotebookWorkerResponse =
  | { ok: true; cells: NotebookCell[] }
  | { ok: false; reason: string };
