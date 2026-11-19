import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";
import { DashboardInputTimeZoneProvider } from "./DashboardInputTimeZone";
import { DashboardValueEditor } from "./DashboardValueEditor";

describe("DashboardValueEditor server values", () => {
  it("keeps an outside scalar selected as its actual value", () => {
    const markup = renderToStaticMarkup(<DashboardValueEditor type="TEXT" value="west" nullable={false} label="Region" control={{ options: [{ value: "north", label: "North" }] }} onChange={() => undefined} />);
    expect(markup).toContain('role="combobox"');
    expect(markup).toContain('title="west"');
    expect(markup).toContain('>west</span>');
    expect(markup).not.toContain('>North</span>');
    expect(markup).not.toContain("Enter a valid value.");
  });

  it("keeps a disabled boolean control explicitly disabled outside fieldset inheritance", () => {
    const markup = renderToStaticMarkup(<DashboardValueEditor type="BOOLEAN" value={false} nullable={false} label="Enabled" disabled onChange={() => undefined} />);
    expect(markup).toMatch(/role="combobox"[^>]*disabled=""/);
    expect(markup).toContain('>false</span>');
    expect(markup).not.toContain('<select');
  });

  it("keeps every outside SET item visible and individually removable", () => {
    const markup = renderToStaticMarkup(<DashboardValueEditor type="TEXT_SET" value={["west", "north", "east"]} nullable={false} label="Regions" control={{ options: [{ value: "north", label: "North" }] }} onChange={() => undefined} />);
    expect(markup).toContain('aria-pressed="true"');
    expect(markup).toContain('aria-label="Remove west"');
    expect(markup).toContain('aria-label="Remove east"');
    expect(markup).not.toContain("allowed range");
  });

  it("shows exact out-of-bounds timestamp text without losing fractional precision", () => {
    const markup = renderToStaticMarkup(<DashboardValueEditor type="TIMESTAMP" value="2026-09-06T09:00:00.000000001Z" nullable={false} label="Observed at" control={{ max: "2026-09-01T00:00:00Z" }} onChange={() => undefined} />);
    expect(markup).toContain("2026-09-06T09:00:00.000000001Z");
    expect(markup).not.toContain("Enter a valid value.");
  });

  it("offers a nullable choice's empty value as its first option instead of a No value checkbox", () => {
    const empty = renderToStaticMarkup(<DashboardValueEditor type="TEXT" value={null} nullable label="Region" control={{ options: [{ value: "north", label: "North" }] }} onChange={() => undefined} />);
    expect(empty).toContain('role="combobox"');
    expect(empty).toContain(">Any</span>");
    expect(empty).not.toContain("No value");
    const labelled = renderToStaticMarkup(<DashboardValueEditor type="BOOLEAN" value={null} nullable label="Active" control={{ options: [{ value: true, label: "Active" }], emptyLabel: "All accounts" }} onChange={() => undefined} />);
    expect(labelled).toContain(">All accounts</span>");
    const freeText = renderToStaticMarkup(<DashboardValueEditor type="TEXT" value={null} nullable label="Note" onChange={() => undefined} />);
    expect(freeText).toContain("No value");
  });
});


describe("DashboardValueEditor time zone", () => {
  const observedAt = (timeZone: string | null) => renderToStaticMarkup(
    <DashboardInputTimeZoneProvider timeZone={timeZone}>
      <DashboardValueEditor type="TIMESTAMP" value="2026-08-25T05:00:00Z" nullable={false} label="Observed at" onChange={() => undefined} />
    </DashboardInputTimeZoneProvider>,
  );

  it("shows a timestamp as the Reporting time zone's wall clock and names the zone", () => {
    const markup = observedAt("Asia/Seoul");
    expect(markup).toContain('value="2026-08-25T14:00:00"');
    expect(markup).toMatch(/aria-describedby="([^"]+)"[\s\S]*id="\1"[^>]*>Asia\/Seoul time<\/p>/);
  });

  it("keeps UTC, labeled, when the zone is unknown or the browser lacks it", () => {
    for (const markup of [observedAt(null), observedAt("Mars/Olympus_Mons")]) {
      expect(markup).toContain('value="2026-08-25T05:00:00"');
      expect(markup).toContain(">UTC</p>");
    }
    expect(renderToStaticMarkup(<DashboardValueEditor type="TIMESTAMP_RANGE" value={{ start: "2026-08-25T05:00:00Z", end: "2026-08-26T05:00:00Z" }} nullable={false} label="Window" onChange={() => undefined} />)).toContain(">UTC</p>");
  });

  it("labels no zone for non-time values", () => {
    expect(renderToStaticMarkup(<DashboardValueEditor type="TEXT" value="north" nullable={false} label="Region" onChange={() => undefined} />)).not.toContain("UTC");
  });
});
