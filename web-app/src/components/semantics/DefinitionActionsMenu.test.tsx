import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";
import { DefinitionActionsMenu } from "./DefinitionActionsMenu";

describe("DefinitionActionsMenu", () => {
  it("uses the medium borderless icon action with its accessible name and tooltip", () => {
    const markup = renderToStaticMarkup(<DefinitionActionsMenu historyHref="/history" canDelete onDelete={() => undefined} />);
    expect(markup).toContain('aria-label="Definition actions"');
    expect(markup).toContain('title="Definition actions"');
    expect(markup).toContain("size-9");
    expect(markup).toContain("bg-transparent");
    expect(markup).not.toContain("border-border");
    expect(markup).toContain("lucide-ellipsis");
  });
});
