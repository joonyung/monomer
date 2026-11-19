import { TABLE_COLOR } from "./output-boundary";
import { TABLE_SVG_BYTES, TABLE_SVG_DEPTH, TABLE_SVG_NODES, byteLength } from "./limits";

const NAMESPACE = "http://www.w3.org/2000/svg";
const MAX_NUMBER = 1_000_000;
const MAX_PATH_SEGMENTS = 1024;
const NUMBER = /[+-]?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][+-]?\d+)?/y;
const SVG_WHITESPACE = /[\t\n\f\r ]/;
const COMMON = ["id", "fill", "stroke", "stroke-width", "opacity", "fill-opacity", "stroke-opacity", "transform", "clip-path", "fill-rule", "clip-rule", "stroke-linecap", "stroke-linejoin", "stroke-miterlimit", "stroke-dasharray", "stroke-dashoffset"];
const TEXT = ["x", "y", "dx", "dy", "font-family", "font-size", "font-weight", "text-anchor", "dominant-baseline", "letter-spacing"];
const ATTRIBUTES: Record<string, readonly string[]> = {
  svg: [...COMMON, ...TEXT.slice(4), "xmlns", "viewBox", "width", "height", "preserveAspectRatio"],
  g: [...COMMON, ...TEXT.slice(4)],
  defs: ["id"],
  path: [...COMMON, "d"],
  rect: [...COMMON, "x", "y", "width", "height", "rx", "ry"],
  circle: [...COMMON, "cx", "cy", "r"],
  ellipse: [...COMMON, "cx", "cy", "rx", "ry"],
  line: [...COMMON, "x1", "y1", "x2", "y2"],
  polyline: [...COMMON, "points"],
  polygon: [...COMMON, "points"],
  text: [...COMMON, ...TEXT],
  tspan: [...COMMON, ...TEXT],
  linearGradient: ["id", "x1", "y1", "x2", "y2", "gradientUnits", "gradientTransform", "spreadMethod"],
  radialGradient: ["id", "cx", "cy", "r", "fx", "fy", "fr", "gradientUnits", "gradientTransform", "spreadMethod"],
  stop: ["id", "offset", "stop-color", "stop-opacity"],
  clipPath: ["id", "clipPathUnits", "transform"],
};
const ENUMS: Record<string, readonly string[]> = {
  "fill-rule": ["nonzero", "evenodd"], "clip-rule": ["nonzero", "evenodd"],
  "stroke-linecap": ["butt", "round", "square"], "stroke-linejoin": ["miter", "round", "bevel"],
  "font-weight": ["normal", "bold", "100", "200", "300", "400", "500", "600", "700", "800", "900"],
  "text-anchor": ["start", "middle", "end"],
  "dominant-baseline": ["auto", "middle", "central", "hanging", "alphabetic", "text-before-edge", "text-after-edge"],
  gradientUnits: ["userSpaceOnUse", "objectBoundingBox"], clipPathUnits: ["userSpaceOnUse", "objectBoundingBox"],
  spreadMethod: ["pad", "reflect", "repeat"],
};

function invalid(): never { throw new Error("Invalid static Table SVG"); }

// Tokenize every byte, then check each command's arity and numeric semantics.
function numberTokens(source: string): string[] {
  const tokens: string[] = [];
  let cursor = 0;
  let comma = false;
  while (cursor < source.length) {
    const char = source[cursor];
    if (SVG_WHITESPACE.test(char)) { cursor++; continue; }
    if (char === ",") {
      if (tokens.length === 0 || comma) invalid();
      comma = true; cursor++; continue;
    }
    NUMBER.lastIndex = cursor;
    const token = NUMBER.exec(source);
    if (!token) invalid();
    const value = Number(token[0]);
    if (!Number.isFinite(value) || Math.abs(value) > MAX_NUMBER) invalid();
    tokens.push(token[0]);
    if (tokens.length > 8192) invalid();
    cursor = NUMBER.lastIndex;
    comma = false;
  }
  if (comma || tokens.length === 0) invalid();
  return tokens;
}

export function validateSvgPath(source: string): void {
  let cursor = 0;
  let segments = 0;
  let first = true;
  const arities: Record<string, number> = { M: 2, L: 2, H: 1, V: 1, C: 6, S: 4, Q: 4, T: 2, A: 7, Z: 0 };
  while (cursor < source.length) {
    while (SVG_WHITESPACE.test(source[cursor] ?? "") && cursor < source.length) cursor++;
    if (cursor === source.length) break;
    const command = source[cursor++].toUpperCase();
    if (!Object.hasOwn(arities, command) || (first && command !== "M")) invalid();
    first = false;
    const start = cursor;
    while (cursor < source.length && !/[a-df-zA-DF-Z]/.test(source[cursor])) cursor++;
    const args = source.slice(start, cursor).replace(/^[\t\n\f\r ]+|[\t\n\f\r ]+$/g, "");
    const arity = arities[command];
    if (arity === 0) { if (args !== "") invalid(); segments++; }
    else {
      const tokens = numberTokens(args);
      const values = tokens.map(Number);
      if (values.length % arity !== 0) invalid();
      segments += values.length / arity;
      if (command === "A") {
        for (let index = 0; index < values.length; index += 7) {
          if (values[index] < 0 || values[index + 1] < 0
            || !["0", "1"].includes(tokens[index + 3]) || !["0", "1"].includes(tokens[index + 4])) invalid();
        }
      }
    }
    if (segments > MAX_PATH_SEGMENTS) invalid();
  }
  if (first) invalid();
}

function transform(source: string): void {
  const arities: Record<string, readonly number[]> = { matrix: [6], translate: [1, 2], scale: [1, 2], rotate: [1, 3], skewX: [1], skewY: [1] };
  const token = /[\t\n\f\r ]*(matrix|translate|scale|rotate|skewX|skewY)[\t\n\f\r ]*\(([^()]*)\)[\t\n\f\r ]*/y;
  let cursor = 0;
  let count = 0;
  while (cursor < source.length) {
    token.lastIndex = cursor;
    const match = token.exec(source);
    if (!match || !arities[match[1]].includes(numberTokens(match[2]).length) || ++count > 32) invalid();
    cursor = token.lastIndex;
  }
  if (count === 0) invalid();
}

/** Returns only a fully validated XML image; never removes unsafe markup. */
export function validateStaticSvg(source: string): string {
  if (typeof source !== "string" || byteLength(source) > TABLE_SVG_BYTES || /<!|<\?/.test(source)) invalid();
  const document = new DOMParser().parseFromString(source, "image/svg+xml");
  const root = document.documentElement;
  if (document.doctype || root.localName !== "svg" || root.namespaceURI !== NAMESPACE || !root.hasAttribute("viewBox")) invalid();
  const ids = new Map<string, Element>();
  const references: { id: string; kind: "paint" | "clip" }[] = [];
  let nodeCount = 0;
  function walk(node: Node, depth: number, insideClip: boolean): void {
    if (++nodeCount > TABLE_SVG_NODES || depth > TABLE_SVG_DEPTH) invalid();
    if (node.nodeType === Node.TEXT_NODE) {
      if (node.textContent?.trim() && !["text", "tspan"].includes(node.parentElement?.localName ?? "")) invalid();
      return;
    }
    if (node.nodeType !== Node.ELEMENT_NODE) invalid();
    const element = node as Element;
    const tag = element.localName;
    if (element.namespaceURI !== NAMESPACE || element.prefix !== null || !Object.hasOwn(ATTRIBUTES, tag) || (tag === "svg" && element !== root)) invalid();
    const clipped = insideClip || tag === "clipPath";
    for (const attribute of element.attributes) {
      const { name, value } = attribute;
      if (!ATTRIBUTES[tag].includes(name) || (attribute.namespaceURI !== null && name !== "xmlns")) invalid();
      if (name === "xmlns") { if (element !== root || value !== NAMESPACE) invalid(); }
      else if (name === "id") {
        if (!/^[A-Za-z_][A-Za-z0-9_-]{0,63}$/.test(value) || ids.has(value)) invalid();
        ids.set(value, element);
      } else if (["fill", "stroke", "clip-path"].includes(name)) {
        const reference = /^url\(#([A-Za-z_][A-Za-z0-9_-]{0,63})\)$/.exec(value);
        if (reference) {
          if (name === "clip-path" && clipped) invalid();
          references.push({ id: reference[1], kind: name === "clip-path" ? "clip" : "paint" });
        } else if (name === "clip-path" ? value !== "none" : value !== "none" && !TABLE_COLOR.test(value)) invalid();
      } else if (name === "stop-color") { if (!TABLE_COLOR.test(value)) invalid(); }
      else if (name === "d") validateSvgPath(value);
      else if (name === "transform" || name === "gradientTransform") transform(value);
      else if (Object.hasOwn(ENUMS, name)) { if (!ENUMS[name].includes(value)) invalid(); }
      else if (name === "font-family") {
        if (!/^(?:system-ui|sans-serif|serif|monospace)(?:\s*,\s*(?:system-ui|sans-serif|serif|monospace))*$/.test(value)) invalid();
      } else if (name === "preserveAspectRatio") {
        if (!/^(?:none|x(?:Min|Mid|Max)Y(?:Min|Mid|Max)(?: (?:meet|slice))?)$/.test(value)) invalid();
      } else if (name === "stroke-dasharray" && value === "none") { /* explicit SVG sentinel */ }
      else {
        const values = numberTokens(value).map(Number);
        if (name === "viewBox") {
          if (values.length !== 4 || values[2] <= 0 || values[3] <= 0) invalid();
        } else if (name === "points") { if (values.length < 4 || values.length % 2 !== 0 || values.length > 2048) invalid(); }
        else if (name === "stroke-dasharray") { if (values.some(number => number < 0)) invalid(); }
        else {
          const textPosition = ["text", "tspan"].includes(tag) && ["x", "y", "dx", "dy"].includes(name);
          if (!textPosition && values.length !== 1) invalid();
          if (["opacity", "fill-opacity", "stroke-opacity", "stop-opacity", "offset"].includes(name) && values.some(number => number < 0 || number > 1)) invalid();
          if (["width", "height", "r", "rx", "ry", "fr", "stroke-width", "font-size", "stroke-miterlimit"].includes(name) && values.some(number => number < 0)) invalid();
        }
      }
    }
    for (const child of element.childNodes) walk(child, depth + 1, clipped);
  }
  for (const child of document.childNodes) {
    if (child !== root) invalid();
  }
  walk(root, 1, false);
  for (const reference of references) {
    const target = ids.get(reference.id);
    if (!target || !(reference.kind === "clip" ? target.localName === "clipPath" : ["linearGradient", "radialGradient"].includes(target.localName))) invalid();
  }
  return source;
}
