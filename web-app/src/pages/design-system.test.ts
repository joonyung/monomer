import { readFileSync, readdirSync } from "node:fs";
import { extname, join, resolve } from "node:path";
import tailwindcss from "@tailwindcss/postcss";
import postcss from "postcss";
import { describe, expect, it } from "vitest";

const webRoot = resolve(process.cwd());
const repositoryRoot = resolve(webRoot, "..");
const globalsCss = readFileSync(join(webRoot, "src/pages/globals.css"), "utf8");

function sourceFiles(directory: string): string[] {
  return readdirSync(directory, { withFileTypes: true }).flatMap((entry) => {
    const path = join(directory, entry.name);
    if (entry.isDirectory()) return sourceFiles(path);
    return [".astro", ".css", ".ts", ".tsx"].includes(extname(entry.name)) ? [path] : [];
  });
}

describe("ink-dominant design system", () => {
  it("assigns ink to actions and sandstone to sparse markers", () => {
    expect(globalsCss).toContain("--accent: oklch(0.185 0.012 250)");
    expect(globalsCss).toContain("--accent-hover: oklch(0.12 0.01 250)");
    expect(globalsCss).toContain("--accent-marker: #f2a864");
    expect(globalsCss).toContain("--color-accent-marker: var(--accent-marker)");
  });

  it("keeps the retired purple palette out of Web and Docs application chrome", () => {
    const design = readFileSync(join(webRoot, "DESIGN.md"), "utf8");
    const docsDesign = readFileSync(join(repositoryRoot, "public-site/DESIGN.md"), "utf8");
    const docsSource = sourceFiles(join(repositoryRoot, "public-site/src"))
      .map((path) => readFileSync(path, "utf8"))
      .join("\n");
    const source = sourceFiles(join(webRoot, "src"))
      .filter((path) => !path.endsWith("design-system.test.ts"))
      .map((path) => readFileSync(path, "utf8"))
      .join("\n");
    const retiredPurple = [
      /#533afd/gi,
      /#4434d4/gi,
      /#2e2b8c/gi,
      /#b9b9f9/gi,
      /#665efd/gi,
      /#362baa/gi,
      /#d6d9fc/gi,
      /#f96bee/gi,
      /rgba\(83,\s*58,\s*253/gi,
      /rgba\(50,\s*50,\s*93/gi,
      /rgba\(3,\s*3,\s*39/gi,
    ];

    for (const retiredColor of retiredPurple) {
      expect(`${design}\n${docsDesign}\n${source}\n${docsSource}`).not.toMatch(retiredColor);
    }
  });

  it("ships the single Focus Sandstone ring and selection tint contract", () => {
    expect(globalsCss).toContain("--selection-tint: rgb(242 168 100 / 0.06)");
    expect(globalsCss).toContain("--focus-ring: #c1742d");
    expect(globalsCss).toContain("--color-selection-tint: var(--selection-tint)");
    expect(globalsCss).toContain("--color-focus-ring: var(--focus-ring)");
    expect(globalsCss).toContain("outline: 2px solid var(--focus-ring) !important");
    expect(globalsCss).toContain("outline-offset: 2px !important");
    expect(globalsCss).toContain("outline: 2px solid Highlight !important");
    expect(globalsCss).toContain("box-shadow: none !important");
    expect(globalsCss).not.toContain("--focus-contrast");
    expect(globalsCss).not.toContain("--accent-marker-halo");
    expect(globalsCss).not.toContain("--shadow-focus");

    const design = readFileSync(join(webRoot, "DESIGN.md"), "utf8");
    const connectorDialog = readFileSync(
      join(webRoot, "src/components/connectors/ConnectorMutationDialog.tsx"),
      "utf8",
    );
    expect(design).toContain("selected-choice Focus Sandstone");
    expect(design).toContain("3.43:1");
    expect(connectorDialog).toContain('style={{ color: "var(--focus-ring)" }}');
    expect(design).toContain("### Large modal dialogs");
    expect(design).toContain("840px");
    expect(design).toContain("8px");
    expect(design).toContain("var(--shadow-modal)");
    expect(design).toContain("Below `768px`");
    expect(design).toContain("While saving, disable close, Escape, and outside dismissal");
  });

  it("keeps production typography and surface radius within the shared roles", () => {
    const productSource = sourceFiles(join(webRoot, "src"))
      .filter((path) => !path.includes("/dev/"))
      .filter((path) => !path.endsWith(".test.ts") && !path.endsWith(".test.tsx"))
      .filter((path) => !path.endsWith("routeTree.gen.ts") && !path.endsWith("generated.ts"))
      .map((path) => readFileSync(path, "utf8"))
      .join("\n");

    expect(productSource).not.toMatch(/font-(?:semibold|bold|extrabold|black)/);
    expect(productSource).not.toMatch(/rounded-(?:xl|2xl|3xl)/);
    expect(productSource).not.toMatch(/text-(?:xs|sm|base|\[(?:10|11|12|13|14|15|16|17)px\]) font-light/);
  });

  it("removes color from disabled controls instead of fading them", () => {
    const productFiles = sourceFiles(join(webRoot, "src"))
      .filter((path) => !path.includes("/dev/"))
      .filter((path) => !path.endsWith(".test.ts") && !path.endsWith(".test.tsx"));
    for (const path of productFiles) {
      expect(readFileSync(path, "utf8"), path.slice(webRoot.length + 1)).not.toMatch(/disabled:opacity/);
    }
  });

  it("routes standard actions and fields through the primitives", () => {
    const productFiles = sourceFiles(join(webRoot, "src"))
      .filter((path) => extname(path) === ".tsx" && !path.includes("/dev/") && !path.endsWith(".test.tsx"));
    const restatedButtonStyle = /(?:^|\s)(?:h-\d+|h-\[|px-\d|py-\d|text-\[\d+px\]|text-(?:xs|sm|base)|rounded|font-(?:normal|medium)|focus:)/;
    const handRolledTextAction = /(?:^|\s)bg-(?:accent|error)(?:\s|$)[\s\S]*(?:^|\s)px-\d|(?:^|\s)px-\d[\s\S]*(?:^|\s)bg-(?:accent|error)(?:\s|$)/;
    // Special-purpose text editors: the command palette, Find table, and table picker searches, the SQL editor fallback, dashboard value controls, and the filter value chip list.
    const rawTextFieldOwners = ["components/ui/Input.tsx", "components/layout/CommandPalette.tsx", "components/catalog/FindTableDialog.tsx", "components/catalog/CatalogTablePicker.tsx", "components/explore/SqlEditor.tsx", "components/dashboard/DashboardValueEditor.tsx", "components/explore/FilterValueList.tsx"];
    const openingTags = (source: string, tag: string) => [...source.matchAll(new RegExp(`<${tag}\\b`, "g"))].map((match) => {
      let depth = 0;
      let end = match.index + tag.length + 1;
      for (; end < source.length; end += 1) {
        if (source[end] === "{") depth += 1;
        else if (source[end] === "}") depth -= 1;
        else if (source[end] === ">" && depth === 0) break;
      }
      return source.slice(match.index, end);
    });
    const classNameOf = (openingTag: string) => /className=(?:"([^"]*)"|\{`([^`]*)`\})/.exec(openingTag)?.slice(1).find((value) => value !== undefined) ?? "";
    for (const path of productFiles) {
      const source = readFileSync(path, "utf8");
      const relative = path.slice(webRoot.length + 1);
      expect(source, relative).not.toMatch(/\btext-white\b/);
      if (!relative.endsWith("components/ui/Input.tsx")) {
        for (const tag of openingTags(source, "select")) expect(tag, relative).toContain("multiple");
      }
      if (!rawTextFieldOwners.some((owner) => relative.endsWith(owner))) {
        expect(openingTags(source, "textarea"), `${relative} textarea`).toEqual([]);
        for (const tag of openingTags(source, "input")) expect(tag, `${relative} input`).toMatch(/type="(?:checkbox|radio|file|hidden|range|color)"/);
      }
      for (const tag of openingTags(source, "Button")) expect(classNameOf(tag), `${relative} Button className`).not.toMatch(restatedButtonStyle);
      for (const tagName of ["button", "a", "Link"]) {
        for (const tag of openingTags(source, tagName)) expect(classNameOf(tag), `${relative} <${tagName}> styled as a filled action`).not.toMatch(handRolledTextAction);
      }
    }
  });

  it("keeps one modal backdrop and Display page headings", () => {
    const productFiles = sourceFiles(join(webRoot, "src"))
      .filter((path) => extname(path) === ".tsx" && !path.includes("/dev/") && !path.endsWith(".test.tsx"));
    for (const path of productFiles) {
      const relative = path.slice(webRoot.length + 1);
      const source = readFileSync(path, "utf8");
      if (!relative.endsWith("components/ui/Dialog.tsx")) {
        expect(source, `${relative} backdrop`).not.toMatch(/bg-(?:foreground|heading|black)\/\d+[^"`]*backdrop-blur|backdrop-blur[^"`]*bg-(?:foreground|heading|black)\/\d+/);
      }
      for (const match of source.matchAll(/<h1\b[^>]*className=(?:"([^"]*)"|\{`([^`]*)`\})/g)) {
        const className = match[1] ?? match[2] ?? "";
        if (!className.includes("sr-only") && !relative.endsWith("pages/login/page.tsx")) expect(className, `${relative} h1`).toContain("type-display");
      }
    }
  });

  it("loads Geist and Geist Mono on both rendered surfaces", () => {
    const docsPackage = readFileSync(join(repositoryRoot, "public-site/package.json"), "utf8");
    const docsCss = readFileSync(join(repositoryRoot, "public-site/src/styles/custom.css"), "utf8");
    expect(docsPackage).toContain('"@fontsource-variable/geist"');
    expect(docsPackage).toContain('"@fontsource-variable/geist-mono"');
    expect(docsCss).toContain("@import '@fontsource-variable/geist'");
    expect(docsCss).toContain("@import '@fontsource-variable/geist-mono'");
  });

  it("generates a CSS rule for every elevation utility the product uses", async () => {
    const elevationLevels = ["ambient", "raised", "overlay", "modal"];
    for (const level of elevationLevels) expect(globalsCss).toMatch(new RegExp(`--shadow-${level}: `));
    const productFiles = sourceFiles(join(webRoot, "src"))
      .filter((path) => !path.endsWith(".test.ts") && !path.endsWith(".test.tsx"));
    const componentSource = productFiles
      .filter((path) => [".ts", ".tsx"].includes(extname(path)))
      .map((path) => readFileSync(path, "utf8"))
      .join("\n");
    expect(componentSource).not.toMatch(/var\(--shadow-|shadow-\[(?!inset)|boxShadow: "var/);
    const usedUtilities = new Set([...componentSource.matchAll(/(?<![\w-])shadow-([a-z0-9]+)(?![\w-])/g)].map((match) => match[1]));
    expect([...usedUtilities].filter((name) => !elevationLevels.includes(name))).toEqual([]);
    const stylesheetVariables = productFiles
      .filter((path) => extname(path) === ".css" && !path.endsWith("globals.css"))
      .flatMap((path) => [...readFileSync(path, "utf8").matchAll(/var\(--shadow-([a-z0-9-]+)\)/g)].map((match) => match[1]));
    expect(stylesheetVariables.filter((name) => !elevationLevels.includes(name))).toEqual([]);
    const compiled = await postcss([tailwindcss({ base: webRoot })]).process(globalsCss, { from: join(webRoot, "src/pages/globals.css") });
    for (const name of usedUtilities) {
      expect(compiled.css, `shadow-${name}`).toMatch(new RegExp(`\\.(?:[a-z0-9-]+\\\\:)*shadow-${name}(?::[a-z-]+)* \\{`));
    }
  }, 30_000);

  it("assigns Modal to dialog content and Overlay to menus and popovers", () => {
    const levelPattern = /(?<![\w-])shadow-(ambient|raised|overlay|modal)(?![\w-])/;
    for (const path of sourceFiles(join(webRoot, "src")).filter((file) => extname(file) === ".tsx" && !file.endsWith(".test.tsx"))) {
      const source = readFileSync(path, "utf8");
      const relative = path.slice(webRoot.length + 1);
      for (const match of source.matchAll(/<Dialog\.Content\b/g)) {
        const rest = source.slice(match.index);
        const openingTag = rest.slice(0, rest.search(/(?<!=)>\s*\n/) + 1);
        const level = levelPattern.exec(openingTag)?.[1];
        if (!level) continue;
        expect(level, `${relative} Dialog.Content`).toBe("modal");
      }
      source.split("\n").forEach((line, index) => {
        const level = levelPattern.exec(line)?.[1];
        if (level && /role="menu"|popover="/.test(line)) expect(level, `${relative}:${index + 1}`).toBe("overlay");
      });
    }
    // Modal dialogs share the Dialog primitive; raw Dialog.Content remains only in drawers.
    expect(readFileSync(join(webRoot, "src/components/ui/Dialog.tsx"), "utf8")).toContain("md:shadow-modal");
  });
});
