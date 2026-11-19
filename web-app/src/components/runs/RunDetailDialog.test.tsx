import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";
import { RunStatusLine } from "./RunDetailDialog";
import type { RunPresentation } from "./types";

const sam = { id: "u-sam", displayName: "Sam Lee" };
const codex = { clientId: "https://chatgpt.com/codex/client.json", name: "Codex", verifiedHost: "chatgpt.com" };

const run: RunPresentation = {
  id: "run-1", title: "orders", subtitle: "Dashboard query", state: "SUCCEEDED",
  createdAt: "2026-09-01T10:00:00Z", updatedAt: "2026-09-01T10:00:02Z",
  resultAvailable: true, failureMessage: null, isCurrentTarget: true,
  startedBy: sam, credentialSurface: "AGENT", agentApp: codex,
};

const visibleText = (markup: string) => markup
  .replace(/<span class="sr-only">.*?<\/span>/g, "")
  .replace(/<span aria-hidden="true" class="pointer-events-none[^"]*"[^>]*>.*?<\/span>/g, "")
  .replace(/<[^>]+>/g, "");
const line = (overrides: Partial<RunPresentation>, principalId: string | null = "u-viewer") =>
  renderToStaticMarkup(<RunStatusLine run={{ ...run, ...overrides }} principalId={principalId} />);

describe("RunStatusLine", () => {
  it("says who started the run and through which agent app", () => {
    const markup = line({});
    expect(visibleText(markup)).toContain("Started by Sam Lee");
    expect(visibleText(markup)).toContain("via Codex");
    expect(markup).toContain('data-agent-app-logo="openai"');
    expect(markup).toContain('aria-label="via Codex (chatgpt.com)"');
    expect(markup.indexOf("Started by Sam Lee")).toBeLessThan(markup.indexOf("Current target"));
  });

  it("names a Web run's person without an agent marker and says you for the viewer", () => {
    const markup = line({ credentialSurface: "WEB", agentApp: null }, "u-sam");
    expect(visibleText(markup)).toContain("Started by you");
    expect(visibleText(markup)).not.toContain("via");
  });

  it("keeps the agent marker when only the agent surface was recorded and says nothing when nothing was", () => {
    expect(visibleText(line({ startedBy: null, agentApp: null }))).toContain("Startedvia agent");
    const unrecorded = visibleText(line({ startedBy: null, credentialSurface: null, agentApp: null }));
    expect(unrecorded).not.toContain("Started");
    expect(unrecorded).not.toContain("via");
  });
});
