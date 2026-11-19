import { describe, expect, it } from "vitest";
import { tableError } from "./output-boundary";

describe("tableError", () => {
  it("reads only a plain string message and bounds it", () => {
    const objectMessage = new Error("x");
    Object.defineProperty(objectMessage, "message", { value: { a: 1 } });
    expect(tableError(objectMessage)).toBe("Table presentation failed");
    expect(tableError(new Error("y".repeat(10_000)))).toHaveLength(4096);
    expect(tableError("not an error")).toBe("Table presentation failed");
  });
});
