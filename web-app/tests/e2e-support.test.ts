import { readFileSync, readdirSync } from "node:fs";
import { join, relative, resolve } from "node:path";
import { describe, expect, it } from "vitest";

const e2eDirectory = resolve(process.cwd(), "tests/e2e");
const guardedTestModule = join(e2eDirectory, "support/test.ts");
const playwrightValueImport = /^import\s+(?!type\b)[^;]*from\s+"@playwright\/test";/m;

function e2eSources(): string[] {
  return readdirSync(e2eDirectory, { recursive: true, encoding: "utf8" })
    .filter((name) => name.endsWith(".ts"))
    .map((name) => join(e2eDirectory, name));
}

describe("e2e support imports", () => {
  it("take test and expect only from the console-guarded support module", () => {
    const offenders = e2eSources()
      .filter((path) => path !== guardedTestModule && playwrightValueImport.test(readFileSync(path, "utf8")))
      .map((path) => relative(e2eDirectory, path));
    expect(offenders).toEqual([]);
  });

  it("import test from the support module in every spec", () => {
    const offenders = e2eSources()
      .filter((path) => path.endsWith(".spec.ts"))
      .filter((path) => !/^import \{[^}]*\btest\b[^}]*\} from "\.\/support\/test";$/m.test(readFileSync(path, "utf8")))
      .map((path) => relative(e2eDirectory, path));
    expect(offenders).toEqual([]);
  });
});
