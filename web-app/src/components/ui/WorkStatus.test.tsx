import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";
import type { DashboardQueryRun, DashboardScheduleOccurrence } from "@/api/dashboard";
import type { ActivityItem } from "@/api/overview";
import { RUN_PRESENTATION_STATES } from "@/components/runs/types";
import type { CatalogIngestJobState, CatalogIngestTaskState, CatalogListRefreshState } from "@/lib/types/catalog";
import { EXPLORE_RUN_STATES } from "@/lib/types/explore";
import { WorkStatus, workStatus, type WorkStatusState, type WorkStatusTone } from "./WorkStatus";

function ownerStates<State extends WorkStatusState>(states: Record<State, true>): State[] {
  return Object.keys(states) as State[];
}

const displayedOwnerStates: Record<string, readonly WorkStatusState[]> = {
  "Run presentation": RUN_PRESENTATION_STATES,
  "Explore run": EXPLORE_RUN_STATES,
  "Overview activity": ownerStates<ActivityItem["state"]>({ RUNNING: true, CANCEL_REQUESTED: true, SUCCEEDED: true, FAILED: true, CANCELLED: true, COMPLETED: true, COMPLETED_WITH_ERRORS: true, CREATED: true, APPLIED: true, UPDATED: true, DELETED: true }),
  "Dashboard query run": ownerStates<DashboardQueryRun["state"]>({ RUNNING: true, SUCCEEDED: true, FAILED: true, CANCELLED: true }),
  "Dashboard schedule outcome": ownerStates<DashboardScheduleOccurrence["outcome"]>({ ACCEPTED: true, SKIPPED: true, ADMISSION_FAILED: true }),
  "Dashboard schedule result": ownerStates<NonNullable<DashboardScheduleOccurrence["resultState"]>>({ RUNNING: true, READY: true, PARTIAL_FAILURE: true, FAILED: true, UNAVAILABLE: true }),
  "Catalog ingestion job": ownerStates<CatalogIngestJobState>({ RUNNING: true, CANCEL_REQUESTED: true, COMPLETED: true, COMPLETED_WITH_ERRORS: true, CANCELLED: true }),
  "Catalog ingestion task": ownerStates<CatalogIngestTaskState>({ RUNNING: true, SUCCEEDED: true, FAILED: true, CANCELLED: true }),
  "Catalog list refresh": ownerStates<CatalogListRefreshState>({ RUNNING: true, CANCEL_REQUESTED: true, COMPLETED: true, COMPLETED_WITH_ERRORS: true, CANCELLED: true }),
  "Overview observation": ["LAST_SEEN_RUNNING"],
};

const approvedVocabulary: Record<WorkStatusState, { label: string; tone: WorkStatusTone }> = {
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
};

const toneIconClass: Record<WorkStatusTone, string> = {
  active: "text-foreground-secondary",
  success: "text-success-text",
  "partial-failure": "text-warning",
  failure: "text-error",
  neutral: "text-foreground-secondary",
};

const allStates = Object.keys(approvedVocabulary) as WorkStatusState[];
const iconOf = (state: WorkStatusState) => renderToStaticMarkup(<WorkStatus state={state} labelDisplay="external" size="sm" />);

describe("work status vocabulary", () => {
  it.each(Object.entries(displayedOwnerStates))("covers every displayed %s state", (_owner, states) => {
    for (const state of states) expect(workStatus(state).label, state).not.toBe("Unknown status");
  });

  it("uses exactly one approved label and tone per state", () => {
    for (const state of allStates) expect(workStatus(state), state).toEqual(approvedVocabulary[state]);
    const labels = allStates.map((state) => workStatus(state).label);
    expect(new Set(labels).size).toBe(labels.length);
  });

  it("keeps running states neutral, spinning, and still under reduced motion", () => {
    const activeStates = allStates.filter((state) => workStatus(state).tone === "active");
    expect(activeStates).toEqual(["RUNNING", "CANCEL_REQUESTED"]);
    for (const state of activeStates) {
      const markup = iconOf(state);
      expect(markup).toContain("lucide-loader-circle");
      expect(markup).toContain("text-foreground-secondary");
      expect(markup).toContain("animate-spin");
      expect(markup).toContain("motion-reduce:animate-none");
      expect(markup).not.toMatch(/success|accent|warning|error/);
    }
  });

  it("reserves green for success, warning for partial failure, and red for full failure", () => {
    for (const state of allStates) {
      const { tone } = workStatus(state);
      const markup = iconOf(state);
      expect(markup, state).toContain(toneIconClass[tone]);
      expect(markup.includes("success"), state).toBe(tone === "success");
      expect(markup.includes("text-warning"), state).toBe(tone === "partial-failure");
      expect(markup.includes("text-error"), state).toBe(tone === "failure");
      expect(markup, state).not.toContain("accent");
      expect(markup.includes("animate-spin"), state).toBe(tone === "active");
    }
  });

  it("uses one icon per tone with Ban for cancelled work", () => {
    const icons: Record<WorkStatusTone, string> = { active: "lucide-loader-circle", success: "lucide-check", "partial-failure": "lucide-triangle-alert", failure: "lucide-circle-alert", neutral: "lucide-circle-dashed" };
    for (const state of allStates) expect(iconOf(state), state).toContain(state === "CANCELLED" ? "lucide-ban" : icons[workStatus(state).tone]);
  });

  it("never lets color carry the state alone", () => {
    const inline = renderToStaticMarkup(<WorkStatus state="FAILED" labelDisplay="inline" size="sm" />);
    expect(inline).toMatch(/<svg[^>]*aria-hidden="true"/);
    expect(inline).toContain(">Failed</span>");
    const named = renderToStaticMarkup(<WorkStatus state="COMPLETED_WITH_ERRORS" labelDisplay="accessible-name" size="md" />);
    expect(named).toContain('role="img"');
    expect(named).toContain('aria-label="Completed with errors"');
    const external = renderToStaticMarkup(<WorkStatus state="SUCCEEDED" labelDisplay="external" size="md" />);
    expect(external).toContain('aria-hidden="true"');
    expect(external).not.toContain("Succeeded");
  });

  it("renders an unknown server state as a static neutral fallback instead of throwing", () => {
    for (const unknown of ["QUEUED_BY_NEW_OWNER", "toString", "__proto__"]) {
      const state = unknown as WorkStatusState;
      expect(workStatus(state)).toEqual({ label: "Unknown status", tone: "neutral" });
      const markup = renderToStaticMarkup(<WorkStatus state={state} labelDisplay="inline" size="sm" />);
      expect(markup).toContain(">Unknown status</span>");
      expect(markup).toContain('data-work-status-tone="neutral"');
      expect(markup).toContain("lucide-circle-dashed");
      expect(markup).not.toContain("animate-spin");
    }
  });
});
