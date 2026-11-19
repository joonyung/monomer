import { describe, expect, it } from "vitest";
import { validateStaticSvg, validateSvgPath } from "./static-svg";

describe("static SVG numeric path grammar", () => {
  it.each(["M0 0 L1 2 Z", "M0,0 C1 2 3 4 5 6 S3 4 5 6", "M1e2 -2.5 a2 3 0 1 0 -2 5", "m0 0h3v4q1 2 3 4t5 6z", "M1.2.3L100-200", "M.6.5L1e2-2e2"])("accepts complete bounded paths: %s", path => {
    expect(() => validateSvgPath(path)).not.toThrow();
  });
  it.each(["", "L1 2", "M0", "M0 0 L1", "M0 0 X1 2", "M0 0 LNaN 2", "M0 0 L1e309 2", "M0 0 L1000001 2", "M0 0 A1 2 0 2 0 2 3", "M0 0 A-1 2 0 0 0 2 3", "M0 0 Z1 2", "M0,,0", "M0 0,", "M0 0" + "L0 0".repeat(1025)])("rejects malformed or over-budget paths", path => {
    expect(() => validateSvgPath(path)).toThrow();
  });
  it.each(["1.0", "1e0", "+1", "-0", "01", ".0"])("rejects nonliteral arc flags: %s", flag => {
    expect(() => validateSvgPath(`M0 0 A1 2 0 ${flag} 0 2 3`)).toThrow();
    expect(() => validateSvgPath(`M0 0 A1 2 0 0 ${flag} 2 3`)).toThrow();
  });
  it.each(["M0\u00a00", "\u00a0M0 0", "M0 0\u00a0", "M0\v0"])("rejects non-SVG whitespace", path => {
    expect(() => validateSvgPath(path)).toThrow();
  });
  it.each(["<!DOCTYPE svg [<!ENTITY secret SYSTEM 'https://evil.test'>]><svg/>", "<?xml-stylesheet href='https://evil.test'?>", "x".repeat(32769)])("rejects declarations and byte excess before XML parsing", source => {
    expect(() => validateStaticSvg(source)).toThrow(/Invalid static/);
  });
});
