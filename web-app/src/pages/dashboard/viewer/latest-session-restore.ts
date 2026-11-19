export function restoreLatestSession(
  latestRestoreAttempt: { current: string | null },
  restoreKey: string,
  latestSessionId: string | undefined,
  onRestore: (sessionId: string) => void,
): void {
  if (latestSessionId === undefined || latestRestoreAttempt.current === restoreKey) return;
  latestRestoreAttempt.current = restoreKey;
  onRestore(latestSessionId);
}
