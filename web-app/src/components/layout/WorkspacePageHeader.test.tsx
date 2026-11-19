import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";
import { WorkspacePageHeader } from "./WorkspacePageHeader";

describe("WorkspacePageHeader", () => {
  it("renders one shared hierarchy for sidebar destinations", () => {
    const markup = renderToStaticMarkup(
      <WorkspacePageHeader
        section="Sources"
        title="Catalog"
        description="Browse metadata from workspace connectors."
        actions={<button type="button">Run refresh</button>}
      />,
    );

    expect(markup).toContain('data-testid="workspace-page-header"');
    expect(markup).toContain("Sources");
    expect(markup).toContain("<h1");
    expect(markup).toContain("Catalog");
    expect(markup).toContain("Browse metadata from workspace connectors.");
    expect(markup).toContain("Run refresh");
    expect(markup).toContain("w-full flex-none");
    expect(markup).toContain("sm:w-auto");
  });

  it("does not reserve an empty action region", () => {
    const markup = renderToStaticMarkup(
      <WorkspacePageHeader
        section="Workspace"
        title="Overview"
        description="Workspace activity."
      />,
    );

    expect(markup).not.toContain("<button");
  });

  it("supports a compact title-only hierarchy", () => {
    const markup = renderToStaticMarkup(<WorkspacePageHeader title="Connectors" />);

    expect(markup).toContain("Connectors");
    expect(markup).toContain("min-h-[88px]");
    expect(markup).not.toContain(">Sources<");
    expect(markup).not.toContain("foreground-secondary");
  });
});
