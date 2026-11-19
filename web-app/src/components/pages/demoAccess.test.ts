import { describe, expect, it } from "vitest";
import { isPagesDemoAvailable } from "@/components/pages/demoAccess";

describe("isPagesDemoAvailable", () => {
  it("keeps Pages fixtures out of production", () => {
    expect(isPagesDemoAvailable(true)).toBe(true);
    expect(isPagesDemoAvailable(false)).toBe(false);
  });
});
