import { useEffect, useState } from "react";
import type { RunPresentation } from "./types";

export function useRunPresentationClock(
  runs: readonly RunPresentation[] | RunPresentation | null,
): number {
  const hasRunningRun = Array.isArray(runs)
    ? runs.some((run: RunPresentation) => run.state === "RUNNING")
    : runs !== null && (runs as RunPresentation).state === "RUNNING";
  const [now, setNow] = useState(() => Date.now());

  useEffect(() => {
    if (!hasRunningRun) return;
    const timer = window.setInterval(() => setNow(Date.now()), 1_000);
    return () => window.clearInterval(timer);
  }, [hasRunningRun]);

  return now;
}

export function runDurationSeconds(run: RunPresentation, now = Date.now()): number {
  if (isDisplayTimestamp(run.createdAt) || isDisplayTimestamp(run.updatedAt)) {
    return run.elapsedSeconds ?? 0;
  }
  const start = Date.parse(run.createdAt);
  const end = run.state === "RUNNING" ? now : Date.parse(run.updatedAt);
  if (Number.isFinite(start) && Number.isFinite(end)) {
    return Math.max(0, Math.floor((end - start) / 1_000));
  }
  return run.elapsedSeconds ?? 0;
}

export function runStartedLabel(run: RunPresentation, now = Date.now()): string {
  if (isDisplayTimestamp(run.createdAt)) return run.createdAt;
  const started = Date.parse(run.createdAt);
  if (!Number.isFinite(started)) return run.createdAt || "—";
  const ageMinutes = Math.max(0, Math.floor((now - started) / 60_000));
  if (ageMinutes < 1) return "Just now";
  if (ageMinutes < 60) return `${ageMinutes}m ago`;
  return formatExactTime(run.createdAt);
}

export function formatExactTime(value: string): string {
  if (isDisplayTimestamp(value)) return value;
  const date = new Date(value);
  if (!Number.isFinite(date.getTime())) return value || "—";
  return new Intl.DateTimeFormat("en-US", {
    month: "short",
    day: "numeric",
    hour: "2-digit",
    minute: "2-digit",
  }).format(date);
}

export function formatDuration(seconds: number): string {
  if (seconds < 60) return `${seconds}s`;
  return `${Math.floor(seconds / 60)}m ${seconds % 60}s`;
}

export function formatResultBytes(value: string): string {
  const bytes = Number(value);
  const units = ["B", "KB", "MB", "GB", "TB"];
  const unitIndex = bytes > 0 ? Math.min(Math.floor(Math.log10(bytes) / 3), units.length - 1) : 0;
  return `${new Intl.NumberFormat("en-US", { maximumFractionDigits: 1 }).format(bytes / 1000 ** unitIndex)} ${units[unitIndex]}`;
}

export function formatCell(value: string | boolean | null): string {
  if (value === null) return "—";
  if (typeof value === "boolean") return value ? "true" : "false";
  return value;
}

function isDisplayTimestamp(value: string): boolean {
  return /^[A-Z][a-z]{2} \d{1,2}, \d{2}:\d{2}$/.test(value);
}
