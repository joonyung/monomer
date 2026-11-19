import { describe, expect, it } from "vitest";
import { requireLineageFixture } from "@/components/lineage/LineageAccessFixture";

describe("Lineage fixture boundary", () => {
  it("is unreachable outside development builds", () => {
    expect(() => requireLineageFixture(true)).not.toThrow();
    expect(() => requireLineageFixture(false)).toThrow();
  });
});
