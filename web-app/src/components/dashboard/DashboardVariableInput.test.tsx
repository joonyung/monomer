import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";
import { DashboardVariableInput } from "./DashboardVariableInput";

describe("DashboardVariableInput", () => {
  it("applies a choice as soon as it is picked, without an Apply control", () => {
    const markup = renderToStaticMarkup(<DashboardVariableInput name="status" definition={{ type: "TEXT", nullable: true, initial: null, label: "Status" }} control={{ options: [{ value: "OPEN", label: "Open" }, { value: "CLOSED", label: "Closed" }] }} value={null} onSet={() => undefined} />);
    expect(markup).toContain('role="combobox"');
    expect(markup).toContain(">Any</span>");
    expect(markup).not.toContain("Apply");
    expect(markup).not.toContain("No value");
  });

  it("keeps an explicit Apply for free text", () => {
    const markup = renderToStaticMarkup(<DashboardVariableInput name="note" definition={{ type: "TEXT", nullable: false, initial: "a", label: "Note" }} value="a" onSet={() => undefined} />);
    expect(markup).toContain('aria-label="Apply Note"');
  });
});
