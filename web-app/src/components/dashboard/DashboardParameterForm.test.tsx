import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";
import type { DashboardParameterDefinition } from "@/api/dashboard";
import { DashboardParameterForm, dashboardInputChips, dashboardInputsSummary, dashboardInputValueText } from "./DashboardParameterForm";

const parameters: Record<string, DashboardParameterDefinition> = {
  region: { type: "TEXT", nullable: false, label: "Region", control: { options: [{ value: "APAC" }, { value: "EMEA" }] } },
  period: { type: "DATE_RANGE", nullable: false, label: "Period" },
  currency: { type: "TEXT", nullable: true, label: "Currency", control: { options: [{ value: "USD" }, { value: "EUR" }], emptyLabel: "All currencies" } },
};

describe("DashboardParameterForm", () => {
  it("allows required review of an applied definition with no remaining Parameters", () => {
    const markup = renderToStaticMarkup(<DashboardParameterForm variant="dialog" parameters={{}} initialValues={{}} allowUnchanged onOpen={() => undefined} />);
    expect(markup).toContain("No inputs required.");
    expect(markup).not.toMatch(/<button[^>]*disabled=""[^>]*>Show results<\/button>/);
  });
  it("renders typed set and range controls without treating untouched required values as errors", () => {
    const markup = renderToStaticMarkup(
      <DashboardParameterForm
        variant="dialog"
        parameters={{
          orderStatuses: {
            type: "TEXT_SET",
            nullable: false,
            label: "Order statuses",
            description: "Statuses included in these results",
            control: { options: [{ value: "SHIPPED", label: "Shipped" }, { value: "REFUNDED", label: "Refunded" }] },
          },
          reportPeriod: {
            type: "DATE_RANGE",
            nullable: false,
            label: "Report period",
            description: "Date range used for these results",
          },
        }}
        onOpen={() => undefined}
      />,
    );

    expect(markup).toContain('role="group"');
    expect(markup).toContain('aria-pressed="false"');
    expect(markup).toContain('placeholder="YYYY-MM-DD"');
    expect(markup).not.toContain('aria-invalid="true"');
    expect(markup).not.toContain("<select multiple");
  });

  it("asks a first visitor to choose inputs and explains why Show results is disabled", () => {
    const markup = renderToStaticMarkup(<DashboardParameterForm parameters={parameters} onOpen={() => undefined} />);
    expect(markup).toContain(">Choose inputs</h2>");
    expect(markup).toContain("Every chart uses these values.");
    expect(markup).toContain("Fill in all required inputs (*) to continue.");
    expect(markup).toMatch(/<button[^>]*disabled=""[^>]*>Show results<\/button>/);
    expect(markup).not.toContain("session");
    expect(markup.indexOf("Region")).toBeLessThan(markup.indexOf("Period"));
    expect(markup.indexOf("Period")).toBeLessThan(markup.indexOf("Currency"));
    expect(markup).toContain(">All currencies</span>");
    expect(markup).not.toContain("No value");
  });

  it("opens prefilled with current inputs, keeps Show results off until a value changes, and marks changes", () => {
    const current = { region: "APAC", period: { start: "2026-07-01", end: "2026-09-30" }, currency: "USD" };
    const unchanged = renderToStaticMarkup(<DashboardParameterForm variant="dialog" parameters={parameters} initialValues={current} onCancel={() => undefined} onOpen={() => undefined} />);
    expect(unchanged).toContain(">APAC</span>");
    expect(unchanged).toContain('value="2026-07-01"');
    expect(unchanged).toContain(">Cancel</button>");
    expect(unchanged).toMatch(/<button[^>]*disabled=""[^>]*>Show results<\/button>/);
    expect(unchanged).not.toContain("Changed from");
    const definitionsChanged = { ...parameters, segment: { type: "TEXT" as const, nullable: false, label: "Segment" } };
    const withNewInput = renderToStaticMarkup(<DashboardParameterForm variant="dialog" parameters={definitionsChanged} initialValues={current} onOpen={() => undefined} />);
    expect(withNewInput).toContain("Fill in all required inputs (*) to continue.");
  });
});

describe("Dashboard input presentation", () => {
  it("formats empty values with the control's empty label or Any", () => {
    expect(dashboardInputValueText(parameters.currency, null)).toBe("All currencies");
    expect(dashboardInputValueText({ type: "TEXT", nullable: true }, null)).toBe("Any");
    expect(dashboardInputValueText(undefined, null)).toBe("Any");
    expect(dashboardInputValueText(parameters.period, { start: "2026-07-01", end: "2026-09-30" })).toBe("Jul 1 – Sep 30, 2026");
  });

  it("keeps definition order and appends bound names the definition no longer declares", () => {
    const chips = dashboardInputChips({ legacy_flag: true, currency: null, region: "EMEA" }, parameters);
    expect(chips).toEqual([
      { name: "region", label: "Region", value: "EMEA" },
      { name: "currency", label: "Currency", value: "All currencies" },
      { name: "legacy_flag", label: "Legacy flag", value: "Yes" },
    ]);
    expect(dashboardInputsSummary(chips, "first-labelled")).toBe("Region EMEA · All currencies · Yes");
    expect(dashboardInputsSummary(chips, "values")).toBe("EMEA · All currencies · Yes");
    expect(dashboardInputsSummary([], "values")).toBe("No inputs");
  });
});
