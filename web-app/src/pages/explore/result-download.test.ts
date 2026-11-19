import { describe, expect, it, vi } from "vitest";
import { readResultBlob, writeResultResponse } from "./result-download";

describe("result file streaming", () => {
  it("saves a header-only CSV without Content-Length and commits only at EOF", async () => {
    const write = vi.fn(); const close = vi.fn();
    await writeResultResponse(new Response("\ufeffcolumn\r\n"), new WritableStream({ write, close }), "csv", new AbortController().signal);
    expect(write).toHaveBeenCalled(); expect(close).toHaveBeenCalledOnce();
  });
  it("rejects incomplete Arrow and aborts the file", async () => {
    const abort = vi.fn(); const close = vi.fn();
    await expect(writeResultResponse(new Response("short", { headers: { "Content-Length": "100" } }), new WritableStream({ abort, close }), "arrow", new AbortController().signal)).rejects.toThrow("incomplete");
    expect(abort).toHaveBeenCalledOnce(); expect(close).not.toHaveBeenCalled();
  });
  it("bounds actual CSV bytes with a reused chunk, independent of Content-Length", async () => {
    const abort = vi.fn(); const close = vi.fn(); const chunk = new Uint8Array(1_000_000); let count = 0;
    const body = new ReadableStream({ pull(controller) { if (count++ < 1001) controller.enqueue(chunk); else controller.close(); } });
    await expect(writeResultResponse(new Response(body), new WritableStream({ abort, close }), "csv", new AbortController().signal)).rejects.toThrow("1 GB");
    expect(abort).toHaveBeenCalledOnce(); expect(close).not.toHaveBeenCalled();
  });
  it("cancels the source and aborts the file when cancelled", async () => {
    const controller = new AbortController(); const cancel = vi.fn(); const abort = vi.fn();
    const source = new ReadableStream({ start(sourceController) { sourceController.enqueue(new Uint8Array([1])); }, cancel });
    await expect(writeResultResponse(new Response(source), new WritableStream({ write() { controller.abort(); }, abort }), "csv", controller.signal)).rejects.toThrow();
    expect(cancel).toHaveBeenCalled(); expect(abort).toHaveBeenCalled();
  });
  it("aborts rather than committing a failed response body", async () => {
    const close = vi.fn(); const abort = vi.fn();
    const body = new ReadableStream({ start(controller) { controller.error(new Error("transport failure")); } });
    await expect(writeResultResponse(new Response(body), new WritableStream({ close, abort }), "csv", new AbortController().signal)).rejects.toThrow("transport failure");
    expect(close).not.toHaveBeenCalled(); expect(abort).toHaveBeenCalled();
  });
  it("reads the whole bounded result into a typed Blob for browsers without direct file saving", async () => {
    const blob = await readResultBlob(new Response("\ufeffcolumn\r\nvalue\r\n"), "csv", new AbortController().signal);
    expect(blob.type).toBe("text/csv");
    expect(new Uint8Array(await blob.arrayBuffer())).toEqual(new TextEncoder().encode("\ufeffcolumn\r\nvalue\r\n"));
  });
  it("rejects an incomplete fallback download instead of saving a partial file", async () => {
    await expect(readResultBlob(new Response("short", { headers: { "Content-Length": "100" } }), "arrow", new AbortController().signal)).rejects.toThrow("incomplete");
  });
});
