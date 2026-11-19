import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";
import { ConnectorTypeMark } from "./ConnectorTypeMark";

describe("ConnectorTypeMark", () => {
  it("renders the vendor glyph in a transparent alignment slot", () => {
    const markup = renderToStaticMarkup(<ConnectorTypeMark type="POSTGRESQL" size="sm" />);

    expect(markup).toContain("h-7 w-7");
    expect(markup).toContain("h-5 w-5");
    expect(markup).toContain("color:oklch(0.42 0.13 240)");
    expect(markup).not.toContain("background");
    expect(markup).not.toContain("border-radius");
  });
});
