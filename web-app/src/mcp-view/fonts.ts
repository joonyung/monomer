import geistLatin from "@fontsource-variable/geist/files/geist-latin-wght-normal.woff2?inline";
import geistLatinExtended from "@fontsource-variable/geist/files/geist-latin-ext-wght-normal.woff2?inline";
import geistMonoLatin from "@fontsource-variable/geist-mono/files/geist-mono-latin-wght-normal.woff2?inline";
import geistMonoLatinExtended from "@fontsource-variable/geist-mono/files/geist-mono-latin-ext-wght-normal.woff2?inline";

// Unicode ranges match the @fontsource-variable subsets the app document loads.
const LATIN_RANGE = "U+0000-00FF,U+0131,U+0152-0153,U+02BB-02BC,U+02C6,U+02DA,U+02DC,U+0304,U+0308,U+0329,U+2000-206F,U+20AC,U+2122,U+2191,U+2193,U+2212,U+2215,U+FEFF,U+FFFD";
const LATIN_EXTENDED_RANGE = "U+0100-02BA,U+02BD-02C5,U+02C7-02CC,U+02CE-02D7,U+02DD-02FF,U+0304,U+0308,U+0329,U+1D00-1DBF,U+1E00-1E9F,U+1EF2-1EFF,U+2020,U+20A0-20AB,U+20AD-20C0,U+2113,U+2C60-2C7F,U+A720-A7FF";

function inlineBytes(dataUrl: string): ArrayBuffer {
  const binary = atob(dataUrl.slice(dataUrl.indexOf(",") + 1));
  const bytes = new Uint8Array(binary.length);
  for (let index = 0; index < binary.length; index += 1) bytes[index] = binary.charCodeAt(index);
  return bytes.buffer;
}

// Host CSPs may forbid font requests, including data: URLs, so the bundled bytes are registered
// directly. A face that cannot load leaves the system fallback in place.
export function registerViewFonts(fonts: FontFaceSet): void {
  if (typeof FontFace !== "function") return;
  const faces = [
    ["Geist Variable", geistLatin, LATIN_RANGE],
    ["Geist Variable", geistLatinExtended, LATIN_EXTENDED_RANGE],
    ["Geist Mono Variable", geistMonoLatin, LATIN_RANGE],
    ["Geist Mono Variable", geistMonoLatinExtended, LATIN_EXTENDED_RANGE],
  ].map(([family, source, unicodeRange]) => new FontFace(family, inlineBytes(source), { weight: "100 900", style: "normal", unicodeRange }));
  for (const face of faces) {
    fonts.add(face);
    void face.load().catch(() => fonts.delete(face));
  }
}
