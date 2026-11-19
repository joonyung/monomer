import { describe, expect, it } from "vitest";
import {
  ControlConstraintError,
  controlSafeJsonInteger,
  controlSemanticDecimal38_18,
  controlUuidV7,
} from "./constraints";

describe("Control constrained scalar constructors", () => {
  it("accepts only lowercase UUIDv7 text", () => {
    expect(controlUuidV7("019e501f-0000-7000-8000-000000000011"))
      .toBe("019e501f-0000-7000-8000-000000000011");
    expect(() => controlUuidV7("019e501f-0000-4000-8000-000000000011"))
      .toThrow(ControlConstraintError);
  });

  it("accepts only non-negative IEEE-754 safe integers", () => {
    expect(controlSafeJsonInteger(9_007_199_254_740_991)).toBe(9_007_199_254_740_991);
    expect(() => controlSafeJsonInteger(-1)).toThrow(ControlConstraintError);
    expect(() => controlSafeJsonInteger(9_007_199_254_740_992)).toThrow(ControlConstraintError);
  });

  it("accepts canonical DECIMAL(38,18) strings only", () => {
    expect(controlSemanticDecimal38_18("-12345678901234567890.123456789012345678"))
      .toBe("-12345678901234567890.123456789012345678");
    for (const invalidValue of ["-0", "1.0", "1e3", "01", "1.000000000000000000"]) {
      expect(() => controlSemanticDecimal38_18(invalidValue)).toThrow(ControlConstraintError);
    }
  });
});
