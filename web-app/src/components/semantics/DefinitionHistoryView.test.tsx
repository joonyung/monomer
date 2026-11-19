import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it, vi } from "vitest";
import { DefinitionHistoryView } from "@/components/semantics/DefinitionHistoryView";
import type { DefinitionChange } from "@/api/semantic-definition-history";

const change: DefinitionChange = {
  changeId: "deleted-change", action: "DELETED", restorable: true, definition: { schemaVersion: 3, name: "revenue", type: "simple", semanticModelName: "orders", measure: "net_revenue", filters: [{ not: { dimension: { semanticModelName: "customers", name: "country" }, operator: "eq", values: ["US"] } }] },
  occurredAt: "2026-10-06T10:00:00Z", actor: { id: "sam", displayName: "Sam Kim" }, credentialSurface: "AGENT", agentApp: { clientId: "https://claude.ai", name: "Claude", verifiedHost: "claude.ai" },
};
function render(canRestore: boolean, error: string | null = null) {
  return renderToStaticMarkup(<DefinitionHistoryView changes={[change]} deleted canRestore={canRestore} principalId="reader" sampleWorkspace={false} restoringId={null} error={error} onRestore={() => undefined} />);
}

describe("semantic definition history", () => {
  it("shows the deleted state, recorded person and app, exact timestamp and full DSL", () => {
    const markup = render(true);
    expect(markup).toContain("Deleted · This definition no longer appears in Semantics");
    expect(markup).toContain("Sam Kim");
    expect(markup).toContain("Claude");
    expect(markup).toContain('dateTime="2026-10-06T10:00:00Z"');
    expect(markup).toContain("net_revenue");
    expect(markup).toContain("customers");
    expect(markup).toContain("View definition");
    expect(markup).toContain(">Restore</button>");
  });
  it("keeps history readable without Editor restore permission and shows restore failures", () => {
    expect(render(false)).not.toContain("Restore");
    expect(render(true, "The name is already used.")).toContain('role="alert"');
    expect(render(true, "The name is already used.")).toContain("The name is already used.");
  });
});


it("preserves the recorded surface and app when a historical person is unavailable", () => {
  const agentMarkup = renderToStaticMarkup(<DefinitionHistoryView changes={[{ ...change, actor: null }]} deleted canRestore={false} principalId="reader" sampleWorkspace={false} restoringId={null} error={null} onRestore={() => undefined} />);
  expect(agentMarkup).toContain("Person not recorded");
  expect(agentMarkup).toContain("via Claude");
  const webMarkup = renderToStaticMarkup(<DefinitionHistoryView changes={[{ ...change, credentialSurface: "WEB", agentApp: null }]} deleted canRestore={false} principalId="reader" sampleWorkspace={false} restoringId={null} error={null} onRestore={() => undefined} />);
  expect(webMarkup).toContain("via Web");
});

it("names Monomer for a sample Workspace's change without a recorded person or surface, and only there", () => {
  const seededChange: DefinitionChange = { ...change, changeId: "seeded", action: "CREATED", actor: null, credentialSurface: null, agentApp: null };
  const renderHistory = (changes: DefinitionChange[], sampleWorkspace: boolean) => renderToStaticMarkup(<DefinitionHistoryView changes={changes} deleted={false} canRestore={false} principalId="reader" sampleWorkspace={sampleWorkspace} restoringId={null} error={null} onRestore={() => undefined} />);
  const seeded = renderHistory([seededChange], true);
  expect(seeded).toContain("<span>Monomer</span>");
  expect(seeded).not.toContain("Person not recorded");
  expect(seeded).not.toContain("via");
  expect(renderHistory([seededChange], false)).toContain("Person not recorded");
  const agentWithoutPerson = renderHistory([{ ...seededChange, credentialSurface: "AGENT", agentApp: change.agentApp }], true);
  expect(agentWithoutPerson).toContain("Person not recorded");
  expect(agentWithoutPerson).not.toContain("Monomer");
  const editedLater = renderHistory([{ ...seededChange, changeId: "edited", action: "UPDATED", actor: { id: "reader", displayName: "Reader" }, credentialSurface: "WEB" }, seededChange], true);
  expect(editedLater).toMatch(/You.*via Web.*Monomer/s);
});

it("hides restore on the live current snapshot and offers it on a differing older version", () => {
  const latest: DefinitionChange = { ...change, changeId: "current", action: "UPDATED", restorable: false };
  const older: DefinitionChange = { ...change, changeId: "older", action: "CREATED", definition: { ...change.definition, label: "Earlier Revenue" } };
  const markup = renderToStaticMarkup(<DefinitionHistoryView changes={[latest, older]} deleted={false} canRestore principalId="reader" sampleWorkspace={false} restoringId={null} error={null} onRestore={() => undefined} />);
  const rows = markup.match(/<li[\s\S]*?<\/li>/g)!;
  expect(rows[0]).toContain(">Edited</h2>");
  expect(rows[0]).not.toContain("Restore");
  expect(rows[1]).toContain(">Restore this version</button>");
});

it("offers one primary Restore for deletion and omits older snapshots marked unavailable", () => {
  const identical: DefinitionChange = { ...change, changeId: "previous", action: "UPDATED", restorable: false };
  const different: DefinitionChange = { ...change, changeId: "original", action: "CREATED", definition: { ...change.definition, label: "Earlier Revenue" } };
  const markup = renderToStaticMarkup(<DefinitionHistoryView changes={[change, identical, different]} deleted canRestore principalId="reader" sampleWorkspace={false} restoringId={null} error={null} onRestore={() => undefined} />);
  const rows = markup.match(/<li[\s\S]*?<\/li>/g)!;
  expect(rows[0]).toMatch(/<button[^>]*bg-accent[^>]*>Restore<\/button>/);
  expect(rows[1]).not.toContain("Restore");
  expect(rows[2]).toContain(">Restore this version</button>");
  expect(markup.match(/>Restore<\/button>/g)).toHaveLength(1);
});

it("uses server availability even when visible definitions suggest a different comparison", () => {
  const latest: DefinitionChange = { ...change, changeId: "current", action: "UPDATED", restorable: false };
  const unavailable: DefinitionChange = { ...change, changeId: "unavailable", action: "UPDATED", restorable: false, definition: { ...change.definition, label: "Different visible definition" } };
  const available: DefinitionChange = { ...change, changeId: "available", action: "CREATED", restorable: true };
  const markup = renderToStaticMarkup(<DefinitionHistoryView changes={[latest, unavailable, available]} deleted={false} canRestore principalId="reader" sampleWorkspace={false} restoringId={null} error={null} onRestore={() => undefined} />);
  const rows = markup.match(/<li[\s\S]*?<\/li>/g)!;
  expect(rows[1]).not.toContain("Restore");
  expect(rows[2]).toContain(">Restore this version</button>");
});

it("offers an available older version without needing the latest record on its page", () => {
  const older: DefinitionChange = { ...change, changeId: "older-page", action: "UPDATED", restorable: true };
  const markup = renderToStaticMarkup(<DefinitionHistoryView changes={[older]} deleted canRestore principalId="reader" sampleWorkspace={false} restoringId={null} error={null} onRestore={() => undefined} />);
  expect(markup).toContain(">Restore this version</button>");
});

it("does not infer restore availability from a Deleted entry", () => {
  const unavailable: DefinitionChange = { ...change, restorable: false };
  const markup = renderToStaticMarkup(<DefinitionHistoryView changes={[unavailable]} deleted canRestore principalId="reader" sampleWorkspace={false} restoringId={null} error={null} onRestore={() => undefined} />);
  expect(markup).not.toContain("Restore");
});

it("shows relative time with the exact recorded time in its title", () => {
  vi.useFakeTimers();
  try {
    vi.setSystemTime(new Date("2026-10-06T11:30:00Z"));
    const markup = render(true);
    expect(markup).toContain(`title="${new Date(change.occurredAt).toLocaleString("en-US")}"`);
    expect(markup).toMatch(/<time[^>]*>1h ago<\/time>/);
  } finally { vi.useRealTimers(); }
});

it("renders Dashboard actions and uses only server restorable values", () => {
  const dashboard = { schemaVersion: 1, meta: { title: "Revenue" }, parameters: {}, variables: {}, queries: {}, widgets: {}, layout: { sections: [] } };
  const changes = [
    { ...change, definition: dashboard },
    { ...change, changeId: "applied", action: "APPLIED" as const, definition: dashboard, restorable: false },
    { ...change, changeId: "created", action: "CREATED" as const, definition: { ...dashboard, meta: { title: "Original revenue" } }, actor: null },
  ];
  const markup = renderToStaticMarkup(<DefinitionHistoryView changes={changes} deleted deletedMessage="This dashboard was deleted. Pages and past results don’t come back." canRestore principalId="reader" sampleWorkspace={false} restoringId={null} error={null} onRestore={() => undefined} />);
  expect(markup).toContain(">Applied</h2>");
  expect(markup).toContain(">Created</h2>");
  expect(markup).toContain("Person not recorded");
  expect(markup).toContain("Pages and past results don’t come back.");
  expect(markup.match(/>Restore<\/button>/g)).toHaveLength(1);
  expect(markup.match(/>Restore this version<\/button>/g)).toHaveLength(1);
});
