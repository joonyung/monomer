import { describe, expect, it } from "vitest";
import { activityQuery } from "./use-activity";

describe("activityQuery", () => {
  it("reads everyone's work, the viewer's own, or work done through an agent connection", () => {
    expect(activityQuery("all")).toEqual({ actor: "all" });
    expect(activityQuery("mine")).toEqual({ actor: "me" });
    expect(activityQuery("agents")).toEqual({ actor: "all", surface: "AGENT" });
  });
});
