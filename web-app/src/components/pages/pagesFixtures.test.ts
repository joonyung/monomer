import { describe, expect, it } from "vitest";
import { createPagesFixture } from "@/components/pages/pagesFixtures";

function placementDepth(placements: Array<{ children: Array<{ children: unknown[] }> }>, depth = 0): number {
  return placements.reduce((maximum, placement) => {
    const children = placement.children as Array<{ children: Array<{ children: unknown[] }> }>;
    return Math.max(maximum, children.length === 0 ? depth + 1 : placementDepth(children, depth + 1));
  }, depth);
}

describe("Pages fixtures", () => {
  it("keeps the dense performance fixture at the Stage 1 Page cap", () => {
    const fixture = createPagesFixture("dense");
    expect(fixture.snapshot.pages).toHaveLength(500);
    expect(fixture.snapshot.placements).toHaveLength(20);
  });

  it("exercises the root-inclusive depth-six boundary", () => {
    const fixture = createPagesFixture("depth");
    expect(fixture.snapshot.pages).toHaveLength(6);
    expect(placementDepth(fixture.snapshot.placements)).toBe(6);
  });

  it("keeps mutation capability fail-closed for the member fixture", () => {
    const fixture = createPagesFixture("empty-member");
    expect(fixture.workspaceAccess.role).toEqual("VIEWER");
  });
});
