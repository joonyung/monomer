import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";
import { RunsRail } from "./RunsRail";
import type { RunPresentation } from "./types";

const sam = { id: "u-sam", displayName: "Sam Lee" };
const codex = { clientId: "https://chatgpt.com/codex/client.json", name: "Codex", verifiedHost: "chatgpt.com" };
const claude = { clientId: "https://claude.ai/oauth/mcp-oauth-client-metadata", name: "Claude", verifiedHost: "claude.ai" };

const run = (id: string, credentialSurface: RunPresentation["credentialSurface"]): RunPresentation => ({
  id, title: `Run ${id}`, subtitle: "SQL · Warehouse", state: "SUCCEEDED",
  createdAt: "2026-09-01T10:00:00Z", updatedAt: "2026-09-01T10:00:02Z",
  resultAvailable: true, failureMessage: null, isCurrentTarget: false, credentialSurface,
});

const visibleText = (markup: string) => markup
  .replace(/<span class="sr-only">.*?<\/span>/g, "")
  .replace(/<span aria-hidden="true" class="pointer-events-none[^"]*"[^>]*>.*?<\/span>/g, "")
  .replace(/<[^>]+>/g, "");
const rows = (markup: string) => [...markup.matchAll(/<button[^>]*>[\s\S]*?<\/button>/g)].map((match) => match[0]).filter((row) => row.includes("Run "));
const render = (runs: readonly RunPresentation[], collapsed: boolean, principalId: string | null = null) => renderToStaticMarkup(
  <RunsRail className="flex" runs={runs} principalId={principalId} newlyAddedRunId={null} collapsed={collapsed} onInspect={() => undefined} />,
);

describe("RunsRail request path", () => {
  it("marks only agent runs, after the run label when no person was recorded", () => {
    const [web, agent, unrecorded] = rows(render([run("web", "WEB"), run("agent", "AGENT"), run("unrecorded", null)], false));
    expect(visibleText(agent)).toContain("Run agentvia agent·SQL · Warehouse");
    expect(agent).not.toContain("lucide-square-terminal");
    expect(visibleText(web)).not.toContain("via agent");
    expect(visibleText(unrecorded)).not.toContain("via agent");
    expect(visibleText(web)).toContain("Run webSQL · Warehouse");
  });

  it("shows a known agent app's logo by its verified host and keeps its host in the row's name", () => {
    const markup = render([{ ...run("claude", "AGENT"), agentApp: claude }], false);
    expect(markup).toContain('data-agent-app-logo="claude"');
    expect(markup).toContain('<span class="sr-only"> via Claude (claude.ai)</span>');
    expect(render([{ ...run("claude", "AGENT"), agentApp: claude }], true)).toContain('aria-label="Open Run claude, started via Claude (claude.ai)"');
  });

  it("names agent runs in the collapsed rail", () => {
    const markup = render([run("web", "WEB"), run("agent", "AGENT")], true);
    expect(markup).toContain('aria-label="Open Run agent, started via agent"');
    expect(markup).toContain('aria-label="Open Run web"');
  });

  it("groups the row count of a succeeded run", () => {
    const counted = { ...run("counted", "WEB"), result: { columns: [], rows: [], returnedRowCount: 0, totalRowCount: 12480 } };
    expect(visibleText(render([counted], false))).toContain("12,480 rows");
    expect(visibleText(render([{ ...counted, result: { ...counted.result, totalRowCount: 1 } }], false))).toContain("Succeeded · 2s · 1 row");
  });
});

describe("RunsRail person", () => {
  it("names the person, then the agent app, then the subtitle", () => {
    const [agent, web] = rows(render([
      { ...run("agent", "AGENT"), startedBy: sam, agentApp: codex, subtitle: "Dashboard query" },
      { ...run("web", "WEB"), startedBy: sam, subtitle: "Dashboard query" },
    ], false, "u-viewer"));
    expect(visibleText(agent)).toContain("Run agentSam Leevia Codex·Dashboard query");
    expect(agent).toContain('data-agent-app-logo="openai"');
    expect(agent.indexOf("Sam Lee")).toBeLessThan(agent.indexOf("via Codex"));
    expect(visibleText(web)).toContain("Run webSam Lee·Dashboard query");
    expect(visibleText(web)).not.toContain("via");
  });

  it("says You for the viewer's own runs and an unnamed account reads Unknown user", () => {
    const [own, unnamed] = rows(render([
      { ...run("own", "WEB"), startedBy: sam },
      { ...run("unnamed", "WEB"), startedBy: { id: "u-gone", displayName: null } },
    ], false, "u-sam"));
    expect(visibleText(own)).toContain("You·SQL · Warehouse");
    expect(visibleText(unnamed)).toContain("Unknown user·SQL · Warehouse");
  });

  it("puts the person and app in the collapsed rail's names", () => {
    const markup = render([
      { ...run("agent", "AGENT"), startedBy: sam, agentApp: claude },
      { ...run("web", "WEB"), startedBy: sam },
    ], true, "u-sam");
    expect(markup).toContain('aria-label="Open Run agent, started by you via Claude (claude.ai)"');
    expect(markup).toContain('aria-label="Open Run web, started by you"');
    expect(markup).toContain('title="Run web · started by you · Succeeded · ');
  });
});
