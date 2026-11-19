import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";
import { WorkspaceSettingsLocked } from "./WorkspaceSettingsShell";

describe("locked Workspace settings", () => {
  it("shows members without settings access the Reporting time zone, read-only", () => {
    const markup = renderToStaticMarkup(
      <QueryClientProvider client={new QueryClient()}>
        <WorkspaceSettingsLocked workspaceId="workspace-1" reportingTimeZone="Asia/Seoul" />
      </QueryClientProvider>,
    );
    expect(markup).toContain("Only workspace owners can change these settings.");
    expect(markup).toMatch(/<dt[^>]*>Reporting time zone<\/dt>/);
    expect(markup).toContain(">Asia/Seoul · GMT+9</span>");
    expect(markup).not.toContain("<button");
    expect(markup).not.toContain("Workspace name");
    expect(markup).not.toContain("Public");
  });
});
