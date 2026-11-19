import { describe, expect, it } from "vitest";
import { buildReplaceNavigationOptions } from "@/lib/navigation";

describe("TanStack navigation adapter", () => {
  it("preserves scroll position when scroll is false", () => {
    expect(buildReplaceNavigationOptions("/explore?execution=run-2", { scroll: false })).toEqual({
      href: "/explore?execution=run-2",
      replace: true,
      resetScroll: false,
    });
  });
});
