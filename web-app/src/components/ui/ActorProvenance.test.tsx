import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";
import { ActorProvenance, actorLabel, actorName, actorProvenanceLabel, seededByMonomer } from "./ActorProvenance";
import { AgentAvatar, AgentAvatarCaption, AgentMarker } from "./AgentMarker";

const claude = { clientId: "https://claude.ai/oauth/mcp-oauth-client-metadata", name: "Claude", verifiedHost: "claude.ai" };
const spoofedClaude = { clientId: "https://claude-helper.app/client.json", name: "Claude", verifiedHost: "claude-helper.app" };
const acme = { clientId: "https://agents.acme.dev/client.json", name: "Acme Agent", verifiedHost: "agents.acme.dev" };
const native = { clientId: "monomer-native", name: "Monomer CLI", verifiedHost: null };

function visibleText(markup: string): string {
  return markup.replace(/<span[^>]*class="sr-only"[^>]*>.*?<\/span>/g, "").replace(/<span aria-hidden="true" class="pointer-events-none[^"]*"[^>]*>.*?<\/span>/g, "").replace(/<[^>]+>/g, "");
}

describe("ActorProvenance", () => {
  const sam = { id: "u-sam", displayName: "Sam Kim" };

  it("names the person and the agent app with its logo after the name", () => {
    const markup = renderToStaticMarkup(<ActorProvenance verb="Updated" actor={sam} surface="AGENT" agentApp={claude} principalId="u-other" time="2 hr ago" />);
    expect(markup).toContain("Updated by Sam Kim");
    expect(visibleText(markup)).toContain("via Claude");
    expect(markup).toContain('data-agent-app-logo="claude"');
    expect(markup).toContain('aria-label="via Claude (claude.ai)"');
    expect(markup).toContain('type="button"');
    expect(markup.indexOf("Sam Kim")).toBeLessThan(markup.indexOf("Claude"));
    expect(markup).toContain("2 hr ago");
  });

  it("falls back to via agent when no app was recorded", () => {
    const markup = renderToStaticMarkup(<ActorProvenance verb="Updated" actor={sam} surface="AGENT" agentApp={null} principalId="u-other" time="now" />);
    expect(markup).toContain("via agent");
    expect(markup).not.toContain("data-agent-app-logo");
    expect(markup).not.toContain("<svg");
    expect(markup).not.toContain("<button");
  });

  it("says you for the viewer and omits the marker for Web work", () => {
    const markup = renderToStaticMarkup(<ActorProvenance verb="Updated" actor={sam} surface="WEB" agentApp={null} principalId="u-sam" time="now" />);
    expect(markup).toContain("Updated by you");
    expect(markup).not.toContain("via");
  });

  it("reads as a bare time when no person was recorded and capitalizes a verbless fact", () => {
    expect(renderToStaticMarkup(<ActorProvenance verb="Updated" actor={null} surface={null} agentApp={null} principalId={null} time="Yesterday" />)).toContain("Updated Yesterday");
    expect(renderToStaticMarkup(<ActorProvenance verb={null} actor={sam} surface="AGENT" agentApp={null} principalId="u-sam" time="now" />)).toContain("You");
    expect(actorName({ id: "gone", displayName: null }, null)).toBe("Unknown user");
  });

  it("names Monomer only for a sample Workspace's definition record that recorded neither a person nor a surface", () => {
    const seeded = renderToStaticMarkup(<ActorProvenance verb="Updated" actor={null} surface={null} agentApp={null} principalId="u-sam" time="2h ago" sampleDefinition />);
    expect(visibleText(seeded)).toBe("Updated by Monomer·2h ago");
    expect(visibleText(renderToStaticMarkup(<ActorProvenance verb={null} actor={null} surface={null} agentApp={null} principalId="u-sam" time="2h ago" sampleDefinition />))).toBe("Monomer·2h ago");
    expect(visibleText(renderToStaticMarkup(<ActorProvenance verb="Created" actor={null} surface={null} agentApp={null} principalId={null} time={null} sampleDefinition />))).toBe("Created by Monomer");
    expect(visibleText(renderToStaticMarkup(<ActorProvenance verb="Updated" actor={null} surface={null} agentApp={null} principalId={null} time="2h ago" />))).toBe("Updated 2h ago");
    expect(visibleText(renderToStaticMarkup(<ActorProvenance verb="Updated" actor={null} surface="WEB" agentApp={null} principalId={null} time="2h ago" sampleDefinition />))).toBe("Updated 2h ago");
    expect(visibleText(renderToStaticMarkup(<ActorProvenance verb="Updated" actor={sam} surface="WEB" agentApp={null} principalId="u-sam" time="now" sampleDefinition />))).toBe("Updated by you·now");
    expect(seededByMonomer(true, null, null)).toBe(true);
    expect(seededByMonomer(false, null, null)).toBe(false);
    expect(seededByMonomer(true, null, "AGENT")).toBe(false);
    expect(seededByMonomer(true, sam, null)).toBe(false);
  });

  it("labels the person alone and states the whole rule in words for accessible names", () => {
    expect(actorLabel(sam, "u-sam")).toBe("You");
    expect(actorLabel(sam, "u-other")).toBe("Sam Kim");
    expect(actorProvenanceLabel("started", sam, "AGENT", claude, "u-other")).toBe("started by Sam Kim via Claude (claude.ai)");
    expect(actorProvenanceLabel("started", sam, "WEB", null, "u-sam")).toBe("started by you");
    expect(actorProvenanceLabel("started", null, "AGENT", null, null)).toBe("started via agent");
    expect(actorProvenanceLabel("started", null, null, null, null)).toBeNull();
  });
});

describe("AgentMarker", () => {
  it("shows a registered client by name without an icon, host, or tooltip", () => {
    const markup = renderToStaticMarkup(<AgentMarker app={native} focusable />);
    expect(visibleText(markup)).toBe("via Monomer CLI");
    expect(markup).not.toContain("<svg");
    expect(markup).not.toContain("<button");
  });

  it("shows another verified app by its host, never by its claimed name or a known logo", () => {
    const markup = renderToStaticMarkup(<AgentMarker app={spoofedClaude} focusable />);
    expect(visibleText(markup)).toBe("via claude-helper.app");
    expect(markup).toContain('aria-label="via claude-helper.app (Claude)"');
    expect(markup).not.toContain("data-agent-app-logo");
    expect(markup).not.toContain("<svg");
  });

  it("keeps the full label in the name of a marker inside a link without becoming focusable", () => {
    const markup = renderToStaticMarkup(<AgentMarker app={claude} focusable={false} />);
    expect(markup).not.toContain("<button");
    expect(markup).toContain('<span class="sr-only"> via Claude (claude.ai)</span>');
    expect(markup).toContain('data-agent-app-logo="claude"');
  });
});

describe("AgentAvatar", () => {
  const avatar = <span className="avatar">MP</span>;

  it("badges a known app with its logo and adds no visible line under the name", () => {
    const badge = renderToStaticMarkup(<AgentAvatar app={claude}>{avatar}</AgentAvatar>);
    expect(badge).toContain('data-agent-badge="claude"');
    expect(badge).toContain("via Claude (claude.ai)");
    const caption = renderToStaticMarkup(<AgentAvatarCaption app={claude} className="meta" />);
    expect(visibleText(caption)).toBe("");
    expect(caption).toContain('<span class="sr-only"> via Claude (claude.ai)</span>');
  });

  it("badges every other app neutrally and names it under the name", () => {
    for (const [app, line, label] of [
      [acme, "agents.acme.dev", "via agents.acme.dev (Acme Agent)"],
      [spoofedClaude, "claude-helper.app", "via claude-helper.app (Claude)"],
      [native, "Monomer CLI", "via Monomer CLI"],
      [null, "agent", "via agent"],
    ] as const) {
      const badge = renderToStaticMarkup(<AgentAvatar app={app}>{avatar}</AgentAvatar>);
      expect(badge).toContain('data-agent-badge="neutral"');
      expect(badge).not.toContain("data-agent-app-logo");
      const caption = renderToStaticMarkup(<AgentAvatarCaption app={app} className="meta" />);
      expect(visibleText(caption)).toBe(line);
      expect(caption).toContain(`<span class="sr-only"> ${label}</span>`);
    }
  });
});
