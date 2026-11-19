import { Ban, Check, CircleAlert, CircleDashed, LoaderCircle, TriangleAlert, type LucideIcon } from "lucide-react";

export type WorkStatusTone = "active" | "success" | "partial-failure" | "failure" | "neutral";

export interface WorkStatusPresentation {
  readonly label: string;
  readonly tone: WorkStatusTone;
}

const WORK_STATUS = {
  RUNNING: { label: "Running", tone: "active" },
  CANCEL_REQUESTED: { label: "Cancelling", tone: "active" },
  SUCCEEDED: { label: "Succeeded", tone: "success" },
  COMPLETED: { label: "Completed", tone: "success" },
  READY: { label: "Ready", tone: "success" },
  COMPLETED_WITH_ERRORS: { label: "Completed with errors", tone: "partial-failure" },
  PARTIAL_FAILURE: { label: "Partial failure", tone: "partial-failure" },
  FAILED: { label: "Failed", tone: "failure" },
  ADMISSION_FAILED: { label: "Admission failed", tone: "failure" },
  CANCELLED: { label: "Cancelled", tone: "neutral" },
  SKIPPED: { label: "Skipped", tone: "neutral" },
  UNAVAILABLE: { label: "Result unavailable", tone: "neutral" },
  ACCEPTED: { label: "Accepted", tone: "neutral" },
  APPLIED: { label: "Applied", tone: "neutral" },
  CREATED: { label: "Created", tone: "neutral" },
  DELETED: { label: "Deleted", tone: "neutral" },
  UPDATED: { label: "Updated", tone: "neutral" },
  LAST_SEEN_RUNNING: { label: "Last seen running", tone: "neutral" },
} as const satisfies Record<string, WorkStatusPresentation>;

export type WorkStatusState = keyof typeof WORK_STATUS;

const UNKNOWN_WORK_STATUS: WorkStatusPresentation = { label: "Unknown status", tone: "neutral" };

export function workStatus(state: WorkStatusState): WorkStatusPresentation {
  return Object.hasOwn(WORK_STATUS, state) ? WORK_STATUS[state] : UNKNOWN_WORK_STATUS;
}

const TONE_ICON: Record<WorkStatusTone, LucideIcon> = {
  active: LoaderCircle,
  success: Check,
  "partial-failure": TriangleAlert,
  failure: CircleAlert,
  neutral: CircleDashed,
};

const TONE_ICON_CLASS: Record<WorkStatusTone, string> = {
  active: "animate-spin text-foreground-secondary motion-reduce:animate-none",
  success: "text-success-text",
  "partial-failure": "text-warning",
  failure: "text-error",
  neutral: "text-foreground-secondary",
};


export interface WorkStatusProps {
  state: WorkStatusState;
  labelDisplay: "inline" | "accessible-name" | "external";
  size: "sm" | "md";
  className?: string;
}

export function WorkStatus({ state, labelDisplay, size, className = "" }: WorkStatusProps) {
  const { label, tone } = workStatus(state);
  const Icon = state === "CANCELLED" ? Ban : TONE_ICON[tone];
  const iconClassName = `${size === "sm" ? "h-3.5 w-3.5" : "h-4 w-4"} shrink-0 ${TONE_ICON_CLASS[tone]}`;
  if (labelDisplay === "inline") {
    return (
      <span className={`inline-flex min-w-0 items-baseline ${size === "sm" ? "gap-1.5" : "gap-2"} ${className}`.trim()}>
        <span className="flex h-[1lh] shrink-0 items-center self-start">
          <Icon data-work-status={state} data-work-status-tone={tone} aria-hidden="true" className={iconClassName} />
        </span>
        <span className="min-w-0">{label}</span>
      </span>
    );
  }
  return labelDisplay === "accessible-name"
    ? <Icon data-work-status={state} data-work-status-tone={tone} role="img" aria-label={label} className={`${iconClassName} ${className}`.trim()} />
    : <Icon data-work-status={state} data-work-status-tone={tone} aria-hidden="true" className={`${iconClassName} ${className}`.trim()} />;
}
