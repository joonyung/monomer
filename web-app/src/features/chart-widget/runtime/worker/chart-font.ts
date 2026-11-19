import geistLatin from "@fontsource-variable/geist/files/geist-latin-wght-normal.woff2?inline";
import geistLatinExtended from "@fontsource-variable/geist/files/geist-latin-ext-wght-normal.woff2?inline";

export const CHART_FONT_FAMILY = "Geist Variable";
export const CHART_FONT_LOAD_DEADLINE_MS = 1_000;

// Unicode ranges match the @fontsource-variable/geist subsets the document loads.
const LATIN_RANGE = "U+0000-00FF,U+0131,U+0152-0153,U+02BB-02BC,U+02C6,U+02DA,U+02DC,U+0304,U+0308,U+0329,U+2000-206F,U+20AC,U+2122,U+2191,U+2193,U+2212,U+2215,U+FEFF,U+FFFD";
const LATIN_EXTENDED_RANGE = "U+0100-02BA,U+02BD-02C5,U+02C7-02CC,U+02CE-02D7,U+02DD-02FF,U+0304,U+0308,U+0329,U+1D00-1DBF,U+1E00-1E9F,U+1EF2-1EFF,U+2020,U+20A0-20AB,U+20AD-20C0,U+2113,U+2C60-2C7F,U+A720-A7FF";

type FontScope = Readonly<{
  fonts?: Readonly<{ add?: (face: FontFace) => unknown; delete?: (face: FontFace) => unknown }>;
}>;

function inlineBytes(dataUrl: string): ArrayBuffer {
  const binary = atob(dataUrl.slice(dataUrl.indexOf(",") + 1));
  const bytes = new Uint8Array(binary.length);
  for (let index = 0; index < binary.length; index += 1) bytes[index] = binary.charCodeAt(index);
  return bytes.buffer;
}

// The Worker CSP forbids font requests, so bundled bytes are registered without a fetch.
// Canvas has no font-variant-numeric; the face descriptor supplies the DESIGN.md tnum and ss01 features.
// A face that misses the deadline is removed so text measured with the fallback font is also painted with it.
export async function registerChartFont(scope: FontScope, deadlineMs: number): Promise<boolean> {
  const fonts = scope.fonts;
  if (typeof FontFace !== "function" || typeof fonts?.add !== "function") return false;
  const faces = [[geistLatin, LATIN_RANGE], [geistLatinExtended, LATIN_EXTENDED_RANGE]].map(([source, unicodeRange]) => new FontFace(
    CHART_FONT_FAMILY,
    inlineBytes(source),
    { weight: "100 900", style: "normal", unicodeRange, featureSettings: '"tnum" 1, "ss01" 1' },
  ));
  for (const face of faces) fonts.add(face);
  let timer: ReturnType<typeof setTimeout> | undefined;
  const loaded = await Promise.race([
    Promise.all(faces.map((face) => face.load())).then(() => true, () => false),
    new Promise<false>((resolve) => { timer = setTimeout(() => resolve(false), deadlineMs); }),
  ]);
  clearTimeout(timer);
  if (!loaded) for (const face of faces) fonts.delete?.(face);
  return loaded;
}
