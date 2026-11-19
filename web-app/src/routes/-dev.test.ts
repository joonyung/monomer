import { describe, expect, it } from "vitest";
import { requireDevelopmentRoute } from "./dev";

describe("developer route boundary", () => {
  it("allows fixtures only in development builds", () => {
    expect(() => requireDevelopmentRoute(true)).not.toThrow();
    expect(() => requireDevelopmentRoute(false)).toThrow();
  });
});
