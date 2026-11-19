import { ChevronLeft, ChevronRight, Loader2, Search } from "lucide-react";
import { Button } from "@/components/ui/Button";
import { IconButton } from "@/components/ui/IconButton";
import { Input } from "@/components/ui/Input";
import { WorkStatus } from "@/components/ui/WorkStatus";

// Shared chrome for the miller-column browser.

// A null width lets the column fill the pane (single-column drill-down).
export function ColumnShell({
  width,
  children,
}: {
  width: string | null;
  children: React.ReactNode;
}) {
  return (
    <div
      className={`flex h-full min-w-0 flex-col bg-background ${width === null ? "w-full flex-1" : "flex-shrink-0 border-r border-border"}`}
      style={width === null ? undefined : { width }}
    >
      {children}
    </div>
  );
}

export function ColumnBack({ label, onBack }: { label: string; onBack: () => void }) {
  return (
    <div className="flex-shrink-0 border-b border-border px-2 py-1.5">
      <Button type="button" variant="ghost" size="sm" onClick={onBack} className="max-w-full">
        <ChevronLeft aria-hidden="true" />
        <span className="min-w-0 truncate">{label}</span>
      </Button>
    </div>
  );
}

export function ColumnHeader({
  label,
  count,
  updating,
  refreshControl,
}: {
  label: string;
  count: number | null;
  updating: boolean;
  refreshControl?: React.ReactNode;
}) {
  return (
    <div className="flex-shrink-0 border-b border-border px-3 py-2.5">
      <div className="flex items-center justify-between gap-2">
        <div className="flex min-w-0 items-center gap-2">
          <span className="type-group text-foreground-secondary">
            {label}
          </span>
          {count !== null && (
            <span className="type-group tabular-nums text-foreground-secondary">
              {count}
            </span>
          )}
        </div>
        <div className="flex items-center gap-2">
          {updating && <Loader2 className="h-3.5 w-3.5 animate-spin text-foreground-secondary motion-reduce:animate-none" aria-label={`${label} loading`} />}
          {refreshControl}
        </div>
      </div>
    </div>
  );
}

export function ColumnSearch({
  value,
  onChange,
  placeholder,
}: {
  value: string;
  onChange: (value: string) => void;
  placeholder: string;
}) {
  return (
    <div className="flex-shrink-0 border-b border-border px-3 py-2">
      <div className="relative">
        <Search className="pointer-events-none absolute left-3 top-1/2 h-3.5 w-3.5 -translate-y-1/2 text-foreground-secondary" />
        <Input
          type="text"
          value={value}
          onChange={(event) => onChange(event.target.value)}
          placeholder={placeholder}
          aria-label={placeholder}
          className="pl-8"
        />
      </div>
    </div>
  );
}

export function ColumnPagination({
  page,
  pageSize,
  totalCount,
  onPageChange,
}: {
  page: number;
  pageSize: number;
  totalCount: number;
  onPageChange: (page: number) => void;
}) {
  if (totalCount <= pageSize) return null;
  const totalPages = Math.max(1, Math.ceil(totalCount / pageSize));
  return (
    <div className="flex flex-shrink-0 items-center justify-between border-t border-border px-3 py-1">
      <span className="type-status tabular-nums text-foreground-secondary">
        Page {page} of {totalPages}
      </span>
      <div className="flex items-center gap-1">
        <IconButton label="Previous page" icon={ChevronLeft} className="border border-border" onClick={() => onPageChange(Math.max(1, page - 1))} disabled={page <= 1} />
        <IconButton label="Next page" icon={ChevronRight} className="border border-border" onClick={() => onPageChange(Math.min(totalPages, page + 1))} disabled={page >= totalPages} />
      </div>
    </div>
  );
}

export function NodeLoadingIndicator({ loading }: { loading: boolean }) {
  return loading ? (
    <WorkStatus state="RUNNING" labelDisplay="external" size="sm" />
  ) : null;
}
