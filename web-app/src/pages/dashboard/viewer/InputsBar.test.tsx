import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";
import { DashboardInputsBar } from "./InputsBar";

const chips = [
  { name: "region", label: "Region", value: "APAC" },
  { name: "period", label: "Period", value: "Q3 2026" },
  { name: "currency", label: "Currency", value: "USD" },
];

describe("Dashboard Inputs bar", () => {
  it("lists inputs as label and value chips with Change inputs and the results time", () => {
    const markup = renderToStaticMarkup(<DashboardInputsBar chips={chips} status={{ kind: "results", at: "2026-10-01T09:02:00Z" }} timeZone="Times in Asia/Seoul" onChangeInputs={() => undefined} />);
    expect(markup).toContain('aria-label="Inputs and results"');
    expect(markup).toContain('>Inputs</span>');
    expect(markup).toContain('title="Region: APAC"');
    expect(markup).toContain('>Change inputs</button>');
    expect(markup).toContain('dateTime="2026-10-01T09:02:00Z"');
    expect(markup).toMatch(/>Results from [^<]+<\/time>/);
    expect(markup.match(/>Times in Asia\/Seoul<\/span>/g)).toHaveLength(2);
    expect(markup).toContain('>APAC · Q3 2026 · USD</span>');
    expect(markup).toContain('aria-label="Change inputs"');
    expect(markup).not.toMatch(/session/i);
  });

  it("shows the running signal instead of a time and omits Change inputs for readers", () => {
    const markup = renderToStaticMarkup(<DashboardInputsBar chips={chips} status={{ kind: "running" }} timeZone={null} />);
    expect(markup).toContain('data-work-status="RUNNING"');
    expect(markup).not.toContain("Results from");
    expect(markup).not.toContain("Change");
    expect(markup).not.toContain("Times in");
  });

  it("is omitted when there are neither inputs nor results", () => {
    expect(renderToStaticMarkup(<DashboardInputsBar chips={[]} status={{ kind: "none" }} timeZone={null} />)).toBe("");
  });
});
