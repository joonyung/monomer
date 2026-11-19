import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { createClipboardCopier, type ClipboardCopyStatus } from "@/hooks/useClipboardCopy";

describe("clipboard copy", () => {
  beforeEach(() => { vi.useFakeTimers(); });
  afterEach(() => { vi.useRealTimers(); });

  it("reports a copy and returns to idle after the feedback window", async () => {
    const statuses: ClipboardCopyStatus[] = [];
    const writeText = vi.fn(() => Promise.resolve());
    const copier = createClipboardCopier(writeText, (status) => statuses.push(status));

    await copier.copy("orders");

    expect(writeText).toHaveBeenCalledWith("orders");
    expect(statuses).toEqual(["idle", "copied"]);
    vi.advanceTimersByTime(1_999);
    expect(statuses.at(-1)).toBe("copied");
    vi.advanceTimersByTime(1);
    expect(statuses.at(-1)).toBe("idle");
  });

  it("keeps a failure until the next attempt, including an unavailable clipboard", async () => {
    const statuses: ClipboardCopyStatus[] = [];
    const rejected = createClipboardCopier(() => Promise.reject(new DOMException("denied", "NotAllowedError")), (status) => statuses.push(status));
    await rejected.copy("orders");
    vi.advanceTimersByTime(10_000);
    expect(statuses).toEqual(["idle", "failed"]);

    const unavailable: ClipboardCopyStatus[] = [];
    await createClipboardCopier(() => { throw new TypeError("clipboard is undefined"); }, (status) => unavailable.push(status)).copy("orders");
    expect(unavailable).toEqual(["idle", "failed"]);
  });

  it("restarts the feedback for a repeated copy so it is announced again", async () => {
    const statuses: ClipboardCopyStatus[] = [];
    const copier = createClipboardCopier(() => Promise.resolve(), (status) => statuses.push(status));
    await copier.copy("first");
    vi.advanceTimersByTime(1_500);
    await copier.copy("second");
    vi.advanceTimersByTime(1_500);

    expect(statuses).toEqual(["idle", "copied", "idle", "copied"]);
    vi.advanceTimersByTime(500);
    expect(statuses.at(-1)).toBe("idle");
  });

  it("reports only the latest attempt when an earlier write settles later", async () => {
    const statuses: ClipboardCopyStatus[] = [];
    let rejectFirst!: (error: Error) => void;
    const writeText = vi.fn()
      .mockReturnValueOnce(new Promise<void>((_resolve, reject) => { rejectFirst = reject; }))
      .mockResolvedValueOnce(undefined);
    const copier = createClipboardCopier(writeText, (status) => statuses.push(status));

    const first = copier.copy("first");
    await copier.copy("second");
    rejectFirst(new Error("late failure"));
    await first;

    expect(statuses).toEqual(["idle", "idle", "copied"]);
  });

  it("stops reporting and clears its timer once disposed", async () => {
    const statuses: ClipboardCopyStatus[] = [];
    let resolveWrite!: () => void;
    const copier = createClipboardCopier(() => new Promise<void>((resolve) => { resolveWrite = resolve; }), (status) => statuses.push(status));
    const pending = copier.copy("orders");
    copier.dispose();
    resolveWrite();
    await pending;
    expect(statuses).toEqual(["idle"]);

    const settled: ClipboardCopyStatus[] = [];
    const disposedAfterCopy = createClipboardCopier(() => Promise.resolve(), (status) => settled.push(status));
    await disposedAfterCopy.copy("orders");
    disposedAfterCopy.dispose();
    expect(vi.getTimerCount()).toBe(0);
    expect(settled).toEqual(["idle", "copied"]);
  });
});
