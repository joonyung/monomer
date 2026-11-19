import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";
import type { DashboardParameterDefinition } from "@/api/dashboard";
import { DashboardParameterToolbar } from "./DashboardParameterToolbar";

const visibleText = (markup: string) => markup.replace(/<[^>]+>/g, " ").replace(/\s+/g, " ");

describe("DashboardParameterToolbar", () => {
  it("summarizes preview inputs as label and formatted value in ASCII name order, closed until Change", () => {
    const definitions: Record<string, DashboardParameterDefinition> = {
      region: { type: "TEXT", nullable: false, label: "Region", description: "Warehouse region" },
      reportPeriod: { type: "DATE_RANGE", nullable: false, label: "Report period", description: null },
      status: {
        type: "TEXT",
        nullable: false,
        label: "Status",
        description: null,
        control: { options: [{ value: "open", label: "Open" }, { value: "closed", label: "Closed" }] },
      },
    };

    const markup = renderToStaticMarkup(
      <DashboardParameterToolbar
        definitions={definitions}
        values={{ region: "North", reportPeriod: { start: "2026-07-01", end: "2026-08-24" }, status: "open" }}
        onSet={() => undefined}
      />,
    );

    const text = visibleText(markup);
    expect(markup).toContain('aria-label="Preview inputs"');
    expect(text).toContain("Region North");
    expect(text).toContain("Report period Jul 1 – Aug 24, 2026");
    expect(text).toContain("Status Open");
    expect(text.indexOf("Region")).toBeLessThan(text.indexOf("Report period"));
    expect(text).toContain("Change preview inputs");
    expect(markup).toContain('aria-expanded="false"');
    expect(markup).not.toContain('placeholder="YYYY-MM-DD"');
    expect(markup).not.toContain(">Apply<");
  });

  it("does not render a toolbar when the definition declares no Parameters", () => {
    expect(renderToStaticMarkup(
      <DashboardParameterToolbar definitions={{}} values={{}} onSet={() => undefined} />,
    )).toBe("");
  });

  it("shows an explicit nullable null as None", () => {
    const markup = renderToStaticMarkup(
      <DashboardParameterToolbar
        definitions={{ note: { type: "TEXT", nullable: true, label: "Note", description: null } }}
        values={{ note: null }}
        onSet={() => undefined}
      />,
    );

    expect(visibleText(markup)).toContain("Note None");
  });
});
