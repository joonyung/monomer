import type { ReactNode } from "react";
import { Check, Copy } from "lucide-react";
import { Button } from "@/components/ui/Button";
import { useClipboardCopy } from "@/hooks/useClipboardCopy";
import { nameInitials } from "./organizationSettingsModel";

type IdentitySize = "sm" | "lg";

const IDENTITY_SIZE: Record<IdentitySize, string> = {
  sm: "h-7 w-7 type-status",
  lg: "h-10 w-10 type-control",
};

export function MemberAvatar({ name, size = "sm" }: { name: string; size?: IdentitySize }) {
  return (
    <span
      aria-hidden="true"
      className={`flex flex-shrink-0 items-center justify-center rounded-full bg-accent text-accent-foreground ${IDENTITY_SIZE[size]}`}
    >
      {nameInitials(name)}
    </span>
  );
}

export interface SegmentedOption<T extends string> {
  value: T;
  label: string;
  count?: number;
}

// A compact single-choice control: the chosen option keeps a neutral surface, and keyboard focus
// underlines the option in addition to the standard outline.
export function SegmentedControl<T extends string>({
  label,
  options,
  value,
  onChange,
  disabled = false,
}: {
  label: string;
  options: SegmentedOption<T>[];
  value: T;
  onChange: (value: T) => void;
  disabled?: boolean;
}) {
  return (
    <div role="group" aria-label={label} className="inline-flex max-w-full flex-wrap gap-0.5 rounded-[4px] border border-border p-0.5">
      {options.map((option) => {
        const selected = option.value === value;
        return (
          <button
            key={option.value}
            type="button"
            aria-pressed={selected}
            disabled={disabled}
            onClick={() => onChange(option.value)}
            className={`flex h-7 items-center gap-1.5 rounded-[4px] px-3 type-control underline-offset-4 transition-colors focus-visible:underline disabled:cursor-not-allowed ${
              selected
                ? "bg-surface text-foreground disabled:text-foreground-secondary"
                : "text-foreground-secondary enabled:hover:text-foreground"
            }`}
          >
            {option.label}
            {option.count !== undefined ? (
              <span className="type-status tabular-nums text-foreground-secondary">{option.count}</span>
            ) : null}
          </button>
        );
      })}
    </div>
  );
}

export function SheetSectionHeading({ id, title, action }: { id: string; title: string; action?: ReactNode }) {
  return (
    <div className="flex min-h-8 flex-wrap items-center justify-between gap-3">
      <h3 id={id} className="type-group text-label">{title}</h3>
      {action}
    </div>
  );
}

// One row of a settings definition list: label, value, and an optional action at the right edge.
export function SettingsField({
  label,
  children,
  action,
  valueClassName = "break-words type-body",
}: {
  label: string;
  children: ReactNode;
  action?: ReactNode;
  valueClassName?: string;
}) {
  if (!action) {
    return (
      <div className="grid items-baseline gap-x-4 gap-y-1 py-3.5 sm:grid-cols-[200px_minmax(0,1fr)_auto]">
        <dt className="type-meta text-foreground-secondary">{label}</dt>
        <dd className={`min-w-0 text-foreground ${valueClassName}`}>{children}</dd>
      </div>
    );
  }
  return (
    <div className="grid grid-cols-[minmax(0,1fr)_auto] items-center gap-x-4 gap-y-1 py-3.5 sm:grid-cols-[200px_minmax(0,1fr)_auto] sm:items-baseline">
      <dt className="type-meta text-foreground-secondary">{label}</dt>
      <dd className={`col-start-1 row-start-2 min-w-0 text-foreground sm:col-start-2 sm:row-start-1 ${valueClassName}`}>{children}</dd>
      <dd className="col-start-2 row-span-2 row-start-1 sm:col-start-3 sm:row-span-1">{action}</dd>
    </div>
  );
}

export function CopyButton({ value, label, copiedAnnouncement, failedAnnouncement }: { value: string; label: string; copiedAnnouncement: string; failedAnnouncement: string }) {
  const { status, copy } = useClipboardCopy();
  const copied = status === "copied";
  return (
    <>
      <Button variant="ghost" size="sm" aria-label={label} onClick={() => void copy(value)}>
        {copied ? <Check aria-hidden="true" /> : <Copy aria-hidden="true" />}
        {copied ? "Copied" : "Copy"}
      </Button>
      <span role="status" className="sr-only">{copied ? copiedAnnouncement : status === "failed" ? failedAnnouncement : ""}</span>
    </>
  );
}
