import { afterEach, describe, expect, it, vi } from "vitest";
import { CHART_FONT_FAMILY, registerChartFont } from "./chart-font";

const DEADLINE_MS = 50;

type FaceCall = Readonly<{ family: string; source: unknown; descriptors: FontFaceDescriptors }>;

function stubFontFace(load: () => Promise<unknown> = () => Promise.resolve()) {
  const calls: FaceCall[] = [];
  vi.stubGlobal("FontFace", class {
    constructor(family: string, source: unknown, descriptors: FontFaceDescriptors) {
      calls.push({ family, source, descriptors });
    }
    load() { return load(); }
  });
  return calls;
}

afterEach(() => { vi.unstubAllGlobals(); });

describe("Chart Worker font", () => {
  it("registers bundled Geist bytes for both Latin subsets with tabular numerals", async () => {
    const calls = stubFontFace();
    const added: unknown[] = [];
    await expect(registerChartFont({ fonts: { add: (face) => added.push(face) } }, DEADLINE_MS)).resolves.toBe(true);
    expect(added).toHaveLength(2);
    expect(calls.map((call) => call.family)).toEqual([CHART_FONT_FAMILY, CHART_FONT_FAMILY]);
    for (const call of calls) {
      expect(call.source).toBeInstanceOf(ArrayBuffer);
      expect(new Uint8Array(call.source as ArrayBuffer).slice(0, 4)).toEqual(new Uint8Array([0x77, 0x4f, 0x46, 0x32]));
      expect(call.descriptors).toMatchObject({ weight: "100 900", style: "normal", featureSettings: '"tnum" 1, "ss01" 1' });
    }
    expect(calls[0].descriptors.unicodeRange).toContain("U+0000-00FF");
    expect(calls[1].descriptors.unicodeRange).toContain("U+0100-02BA");
  });

  it("falls back when the Worker has no font set", async () => {
    const calls = stubFontFace();
    await expect(registerChartFont({}, DEADLINE_MS)).resolves.toBe(false);
    expect(calls).toEqual([]);
  });

  it("falls back when FontFace is unavailable", async () => {
    vi.stubGlobal("FontFace", undefined);
    await expect(registerChartFont({ fonts: { add: () => undefined } }, DEADLINE_MS)).resolves.toBe(false);
  });

  it("removes the faces and falls back when a face fails to load", async () => {
    stubFontFace(() => Promise.reject(new Error("invalid font")));
    const added = new Set<unknown>();
    const fonts = { add: (face: unknown) => added.add(face), delete: (face: unknown) => added.delete(face) };
    await expect(registerChartFont({ fonts }, DEADLINE_MS)).resolves.toBe(false);
    expect(added.size).toBe(0);
  });

  it("removes the faces and falls back when loading misses the deadline", async () => {
    stubFontFace(() => new Promise(() => undefined));
    const added = new Set<unknown>();
    const fonts = { add: (face: unknown) => added.add(face), delete: (face: unknown) => added.delete(face) };
    await expect(registerChartFont({ fonts }, DEADLINE_MS)).resolves.toBe(false);
    expect(added.size).toBe(0);
  });
});
