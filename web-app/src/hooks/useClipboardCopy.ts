import { useCallback, useEffect, useRef, useState } from "react";

export type ClipboardCopyStatus = "idle" | "copied" | "failed";

const COPIED_FEEDBACK_MS = 2_000;

interface ClipboardCopier {
  copy: (text: string) => Promise<void>;
  dispose: () => void;
}

/** A failure stays until the next attempt so its message isn't lost on a timer; a success clears after a moment. */
export function createClipboardCopier(
  writeText: (text: string) => Promise<void>,
  onStatus: (status: ClipboardCopyStatus) => void,
): ClipboardCopier {
  let latestAttempt = 0;
  let disposed = false;
  let resetTimer: ReturnType<typeof setTimeout> | null = null;
  const cancelReset = () => {
    if (resetTimer !== null) clearTimeout(resetTimer);
    resetTimer = null;
  };
  return {
    copy: async (text) => {
      const attempt = ++latestAttempt;
      cancelReset();
      onStatus("idle");
      let outcome: ClipboardCopyStatus;
      try {
        await writeText(text);
        outcome = "copied";
      } catch {
        outcome = "failed";
      }
      if (disposed || attempt !== latestAttempt) return;
      onStatus(outcome);
      if (outcome === "copied") {
        resetTimer = setTimeout(() => {
          resetTimer = null;
          onStatus("idle");
        }, COPIED_FEEDBACK_MS);
      }
    },
    dispose: () => {
      disposed = true;
      cancelReset();
    },
  };
}

export function useClipboardCopy(): { status: ClipboardCopyStatus; copy: (text: string) => Promise<void> } {
  const [status, setStatus] = useState<ClipboardCopyStatus>("idle");
  const copier = useRef<ClipboardCopier | null>(null);
  useEffect(() => {
    const mounted = createClipboardCopier((text) => navigator.clipboard.writeText(text), setStatus);
    copier.current = mounted;
    return () => mounted.dispose();
  }, []);
  const copy = useCallback(async (text: string) => {
    await copier.current?.copy(text);
  }, []);
  return { status, copy };
}
