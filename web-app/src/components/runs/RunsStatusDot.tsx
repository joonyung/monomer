export function RunsStatusDot({ running }: { running: boolean }) {
  return (
    <span
      data-testid="runs-status-dot"
      data-running={running}
      aria-hidden="true"
      className={`absolute right-1.5 top-1.5 h-1.5 w-1.5 rounded-full ${running ? "animate-pulse-dot bg-foreground" : "bg-foreground-secondary/45"}`}
    />
  );
}
