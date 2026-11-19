import { describe, expect, it } from "vitest";
import { createControlProblemValidator, ControlProblemSchemaConfigurationError } from "./problem-runtime";

function pathValidator(pathSchema: object) {
  return createControlProblemValidator({
    $ref: "#/$defs/Problem",
    $defs: {
      Problem: { type: "object", properties: { path: pathSchema }, required: ["path"], additionalProperties: false },
    },
  });
}

describe("Control Problem string pattern negation", () => {
  it("preserves positive patterns, length bounds and existing intersections", () => {
    const validator = pathValidator({
      type: "string", pattern: "^workspace/", maxLength: 30,
      allOf: [{ pattern: "/[^/]+$", type: "string" }],
      not: { pattern: "~(?![01])" },
    });
    for (const path of ["workspace/~0", "workspace/~1", "workspace/한글", "workspace/a\u2028b", "workspace/a\n~0"]) {
      expect(validator.safeParse({ path }).success, path).toBe(true);
    }
    for (const path of ["other/~0", "workspace/", "workspace/~", "workspace/~2", "workspace/a\n~x", "workspace/a\u2028~x", `workspace/${"a".repeat(22)}`]) {
      expect(validator.safeParse({ path }).success, path).toBe(false);
    }
  });

  it("preserves unanchored and anchored forbidden-pattern semantics", () => {
    const anywhere = pathValidator({ type: "string", not: { pattern: "bad" } });
    expect(anywhere.safeParse({ path: "prefixbadend" }).success).toBe(false);
    expect(anywhere.safeParse({ path: "good" }).success).toBe(true);
    const anchored = pathValidator({ type: "string", not: { pattern: "^bad$" } });
    expect(anchored.safeParse({ path: "bad" }).success).toBe(false);
    expect(anchored.safeParse({ path: "prefixbadend" }).success).toBe(true);
  });

  it("does not silently discard unsupported negation constraints", () => {
    expect(() => pathValidator({ type: "string", not: { pattern: "bad", minLength: 4 } }))
      .toThrow(ControlProblemSchemaConfigurationError);
  });
});
