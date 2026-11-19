import DOMPurify from "dompurify";

const RASTER_DATA_URI = /^data:image\/(?:png|jpeg|gif);base64,[a-z0-9+/=\s]+$/i;
const SAFE_FRAGMENT = /^#[A-Za-z][A-Za-z0-9_.:-]*$/;
const UNSAFE_CSS_FUNCTION = /(?:url|image|image-set|cross-fade|paint)\s*\(|@import|expression\s*\(|-moz-binding/i;
const SAFE_STYLE_PROPERTIES = new Set([
  "background",
  "background-color",
  "border",
  "border-bottom",
  "border-collapse",
  "border-color",
  "border-left",
  "border-radius",
  "border-right",
  "border-style",
  "border-top",
  "border-width",
  "color",
  "display",
  "font-family",
  "font-size",
  "font-style",
  "font-weight",
  "gap",
  "grid-template-columns",
  "height",
  "justify-content",
  "line-height",
  "margin",
  "margin-bottom",
  "margin-left",
  "margin-right",
  "margin-top",
  "max-height",
  "max-width",
  "min-height",
  "min-width",
  "overflow",
  "overflow-x",
  "overflow-y",
  "padding",
  "padding-bottom",
  "padding-left",
  "padding-right",
  "padding-top",
  "text-align",
  "text-decoration",
  "vertical-align",
  "white-space",
  "width",
]);

const ALLOWED_TAGS = [
  "a", "article", "aside", "b", "blockquote", "br", "caption", "code", "col", "colgroup",
  "dd", "del", "details", "div", "dl", "dt", "em", "figcaption", "figure", "footer", "h1",
  "h2", "h3", "h4", "h5", "h6", "header", "hr", "i", "img", "kbd", "li", "main", "mark",
  "nav", "ol", "p", "pre", "s", "samp", "section", "small", "span", "strong", "sub",
  "summary", "sup", "table", "tbody", "td", "tfoot", "th", "thead", "time", "tr", "u", "ul",
  "var",
];

const ALLOWED_ATTRIBUTES = [
  "alt", "aria-label", "aria-labelledby", "colspan", "datetime", "height", "href", "id", "open",
  "role", "rowspan", "scope", "src", "style", "title", "width",
];

export const STATIC_HTML_CHILD_CSP =
  "default-src 'none'; img-src data:; style-src 'unsafe-inline'; base-uri 'none'; form-action 'none'; frame-src 'none'; object-src 'none'";

function sanitizeInlineStyle(element: HTMLElement): void {
  const raw = element.getAttribute("style");
  if (!raw) return;
  if (UNSAFE_CSS_FUNCTION.test(raw)) {
    element.removeAttribute("style");
    return;
  }
  const parsed = document.createElement("span");
  parsed.setAttribute("style", raw);
  const safeDeclarations: string[] = [];
  for (const property of [...parsed.style]) {
    const normalized = property.toLowerCase();
    const value = parsed.style.getPropertyValue(property).trim();
    if (!SAFE_STYLE_PROPERTIES.has(normalized) || !value || UNSAFE_CSS_FUNCTION.test(value)) {
      continue;
    }
    safeDeclarations.push(`${normalized}: ${value}`);
  }
  if (safeDeclarations.length === 0) element.removeAttribute("style");
  else element.setAttribute("style", safeDeclarations.join("; "));
}

export function sanitizeStaticHtml(source: string): string {
  const parsed = new DOMParser().parseFromString(source, "text/html");
  for (const blocked of parsed.querySelectorAll("base, embed, form, iframe, link, meta, object, script, style")) {
    blocked.remove();
  }
  const sanitized = DOMPurify.sanitize(parsed.body.innerHTML, {
    ALLOWED_TAGS,
    ALLOWED_ATTR: ALLOWED_ATTRIBUTES,
    ALLOW_DATA_ATTR: false,
    ALLOW_ARIA_ATTR: true,
    FORBID_TAGS: ["base", "embed", "form", "iframe", "link", "meta", "object", "script", "style"],
    FORBID_ATTR: ["action", "formaction", "poster", "srcset", "target"],
    RETURN_TRUSTED_TYPE: false,
  });
  const template = document.createElement("template");
  template.innerHTML = sanitized;
  for (const element of [...template.content.querySelectorAll<HTMLElement>("*")]) {
    for (const attribute of [...element.attributes]) {
      if (attribute.name.toLowerCase().startsWith("on")) element.removeAttribute(attribute.name);
    }
    sanitizeInlineStyle(element);
    if (element instanceof HTMLImageElement) {
      if (!RASTER_DATA_URI.test(element.getAttribute("src") ?? "")) element.remove();
      continue;
    }
    element.removeAttribute("src");
    const href = element.getAttribute("href");
    if (href !== null && !SAFE_FRAGMENT.test(href)) element.removeAttribute("href");
  }
  return template.innerHTML;
}

export function staticHtmlChildDocument(source: string): string {
  const body = sanitizeStaticHtml(source);
  return `<!doctype html><html><head><meta http-equiv="Content-Security-Policy" content="${STATIC_HTML_CHILD_CSP}"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><style>html{color-scheme:light}body{margin:0;padding:32px;background:#fdfdfe;color:#1e2226;font-family:system-ui,-apple-system,sans-serif;line-height:1.55}img{max-width:100%;height:auto}table{max-width:100%;border-collapse:collapse}th,td{border:1px solid #dfe3e7;padding:8px;text-align:left}pre{overflow:auto}</style></head><body>${body}</body></html>`;
}
