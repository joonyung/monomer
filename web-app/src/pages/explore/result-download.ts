export type ResultFormat = "csv" | "arrow";
export const CSV_MAX_BYTES = 1_000_000_000;
export async function writeResultResponse(response: Response, writable: WritableStream<Uint8Array>, format: ResultFormat, signal: AbortSignal): Promise<void> {
  if (!response.body) throw new Error("The download response has no body.");
  const expectedLength = response.headers.get("Content-Length");
  let byteCount = 0;
  const checked = new TransformStream<Uint8Array, Uint8Array>({
    transform(chunk, controller) {
      byteCount += chunk.byteLength;
      if (format === "csv" && byteCount > CSV_MAX_BYTES) throw new Error("CSV exceeds the 1 GB download limit.");
      controller.enqueue(chunk);
    },
    flush() {
      if (expectedLength !== null && byteCount !== Number(expectedLength)) throw new Error("The download is incomplete.");
    },
  });
  await response.body.pipeThrough(checked).pipeTo(writable, { signal, preventClose: true });
  signal.throwIfAborted();
  await writable.close();
}
/** Fallback for browsers without direct file saving: the bounded result is read into memory first. */
export async function readResultBlob(response: Response, format: ResultFormat, signal: AbortSignal): Promise<Blob> {
  const chunks: Uint8Array[] = [];
  const collector = new WritableStream<Uint8Array>({ write(chunk) { chunks.push(chunk); } });
  await writeResultResponse(response, collector, format, signal);
  return new Blob(chunks as BlobPart[], { type: format === "csv" ? "text/csv" : "application/vnd.apache.arrow.file" });
}
export interface SaveResultWindow extends Window {
  showSaveFilePicker?: (options: { suggestedName: string; types: { description: string; accept: Record<string, string[]> }[] }) => Promise<{ createWritable: () => Promise<WritableStream<Uint8Array>> }>;
}
