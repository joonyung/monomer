import path from "node:path";
import react from "@vitejs/plugin-react";
import { defineConfig, type Plugin } from "vite";

// Builds the MCP Apps Dashboard view: one self-contained HTML file that chat hosts render in a
// sandboxed iframe. Every script, style, font and Worker is inside the file; the host CSP allows
// no other origin, and the host caches the HTML per conversation.

const VIEW_ENTRY = path.resolve(import.meta.dirname, "src/mcp-view/dashboard-view.html");
const VIEW_OUTPUT_FILE = "dashboard-view.html";
const DEFAULT_WORKER_FACTORIES = new Set([
  path.resolve(import.meta.dirname, "src/features/chart-widget/runtime/worker-factory.ts"),
  path.resolve(import.meta.dirname, "src/features/table-widget/runtime/worker-factory.ts"),
]);

// The app's default factories start same-origin Worker URLs, which do not exist in the host's
// sandbox. The view injects inline blob Worker factories instead; these stubs keep the URL Workers
// out of the file and fail loudly if anything still reaches them.
function replaceDefaultWorkerFactories(): Plugin {
  return {
    name: "monomer-mcp-view-default-worker-factories",
    enforce: "pre",
    load(id) {
      if (!DEFAULT_WORKER_FACTORIES.has(id.split("?")[0])) return null;
      const failure = 'throw new Error("The Dashboard view must supply its own Worker factory.");';
      return `export function createChartWidgetWorker() { ${failure} }\nexport function createTableWidgetWorker() { ${failure} }\n`;
    },
  };
}

// `<` before script or comment markup can only occur inside string, template or regex literals,
// where \x3C means the same character. An existing escaping backslash is folded into the new escape.
function escapeInlineScript(code: string): string {
  return code.replace(/(\\*)<(?=\/script|script|!--)/gi, (_match, backslashes: string) =>
    `${backslashes.length % 2 === 0 ? backslashes : backslashes.slice(1)}\\x3C`);
}

function escapeInlineStyle(css: string): string {
  return css.replace(/<(?=\/style)/gi, "\\3C ");
}

const INLINE_SCRIPT = /<script type="module">([\s\S]*?)<\/script>/g;
const MARKUP_CHECKS: readonly [RegExp, string][] = [
  [/<script\b[^>]*\bsrc\s*=/i, "a script loaded by URL"],
  [/<link\b/i, "a linked stylesheet, preload or icon"],
  [/\b(?:src|href)\s*=\s*["']?(?:https?:)?\/\//i, "an http(s) or protocol-relative src or href"],
  [/@import\b/i, "a CSS @import"],
  [/url\(\s*(?!["']?(?:data:|#))/i, "a CSS url() that is not a data: URL"],
  [/assets\//, "a reference to an emitted assets/ file"],
];
const SCRIPT_CHECKS: readonly [RegExp, string][] = [
  [/(?:^|["'`(\s=])(?:\.{0,2}\/)?assets\/[\w.-]+\.(?:js|css|woff2?|ttf|svg|png)/, "a reference to an emitted assets/ file"],
  [/new\s+(?:Shared)?Worker\s*\(\s*(?:new\s+URL\s*\(|["'`](?!data:|blob:))/, "a Worker started from a URL"],
  [/\bimportScripts\s*\(/, "importScripts"],
];

// Fails the build when the page would still fetch a script, style, font or Worker from a URL.
function assertSelfContained(html: string): void {
  const scripts = [...html.matchAll(INLINE_SCRIPT)].map((match) => match[1]);
  const markup = html.replace(INLINE_SCRIPT, "<script></script>");
  const failures = [
    ...MARKUP_CHECKS.filter(([pattern]) => pattern.test(markup)).map(([, description]) => description),
    ...SCRIPT_CHECKS.filter(([pattern]) => scripts.some((script) => pattern.test(script))).map(([, description]) => `${description} in an inline script`),
  ];
  if (scripts.length === 0) failures.push("no inline module script");
  if (failures.length > 0) throw new Error(`The Dashboard view must be self-contained, but it contains ${[...new Set(failures)].join(", ")}.`);
}

function inlineSingleFile(): Plugin {
  return {
    name: "monomer-mcp-view-single-file",
    apply: "build",
    enforce: "post",
    generateBundle(_options, bundle) {
      const pages = Object.values(bundle).filter((output) => output.type === "asset" && output.fileName.endsWith(".html"));
      if (pages.length !== 1) this.error(`Expected one HTML page, found ${pages.length}.`);
      const page = pages[0];
      const pageDirectory = path.posix.dirname(page.fileName);
      const inlined = new Set<string>([page.fileName]);
      const bundled = (reference: string) => {
        const fileName = path.posix.normalize(path.posix.join(pageDirectory, reference));
        const output = bundle[fileName];
        if (!output) this.error(`The page references ${reference}, which is not in the bundle.`);
        inlined.add(fileName);
        return output;
      };
      let html = String(page.type === "asset" ? page.source : "");
      html = html.replace(/<script\b[^>]*\bsrc="([^"]+)"[^>]*><\/script>/g, (_tag, source: string) => {
        const output = bundled(source);
        if (output.type !== "chunk") this.error(`The script ${source} is not a JavaScript chunk.`);
        return `<script type="module">${escapeInlineScript(output.code)}</script>`;
      });
      html = html.replace(/<link\b[^>]*\brel="stylesheet"[^>]*\bhref="([^"]+)"[^>]*>/g, (_tag, reference: string) => {
        const output = bundled(reference);
        if (output.type !== "asset") this.error(`The stylesheet ${reference} is not an asset.`);
        return `<style>${escapeInlineStyle(String(output.source))}</style>`;
      });
      const leftovers = Object.keys(bundle).filter((fileName) => !inlined.has(fileName));
      if (leftovers.length > 0) this.error(`The Dashboard view must be one file, but these outputs were not inlined: ${leftovers.join(", ")}.`);
      if (/<\/script/i.test(html.replace(/<\/script>\s*(?=<|$)/gi, ""))) this.error("An inlined script still closes the script element early.");
      try {
        assertSelfContained(html);
      } catch (error) {
        this.error(error instanceof Error ? error.message : String(error));
      }
      for (const fileName of inlined) delete bundle[fileName];
      this.emitFile({ type: "asset", fileName: VIEW_OUTPUT_FILE, source: html });
    },
  };
}

export default defineConfig({
  base: "./",
  envDir: false,
  publicDir: false,
  plugins: [replaceDefaultWorkerFactories(), react(), inlineSingleFile()],
  resolve: { tsconfigPaths: true },
  worker: {
    format: "es",
    rolldownOptions: { output: { codeSplitting: false } },
  },
  build: {
    outDir: "dist-mcp-view",
    emptyOutDir: true,
    assetsInlineLimit: () => true,
    cssCodeSplit: false,
    modulePreload: false,
    reportCompressedSize: false,
    chunkSizeWarningLimit: 4096,
    rolldownOptions: {
      input: VIEW_ENTRY,
      output: { codeSplitting: false },
    },
  },
});
