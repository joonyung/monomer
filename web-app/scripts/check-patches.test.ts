import { mkdtempSync, mkdirSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterEach, describe, expect, it } from "vitest";
import { checkPatches } from "./check-patches.mjs";

const directories: string[] = [];

function fixture(contents: string | null): string {
  const root = mkdtempSync(join(tmpdir(), "monomer-patch-check-"));
  directories.push(root);
  mkdirSync(join(root, "patches"));
  mkdirSync(join(root, "node_modules", "fixture"), { recursive: true });
  writeFileSync(join(root, "patches", "fixture+1.0.0.patch"),
    "--- a/node_modules/fixture/index.js\n+++ b/node_modules/fixture/index.js\n@@ -1 +1 @@\n-before\n+after\n");
  if (contents !== null) writeFileSync(join(root, "node_modules", "fixture", "index.js"), contents);
  return root;
}

afterEach(() => directories.splice(0).forEach((directory) => rmSync(directory, { recursive: true, force: true })));

describe("required dependency patches", () => {
  it("accepts applied patches without reversing them", () => {
    const root = fixture("after\n");
    expect(checkPatches(root)).toBe(1);
    expect(readFileSync(join(root, "node_modules", "fixture", "index.js"), "utf8")).toBe("after\n");
  });

  it.each(["before\n", "incompatible\n", null])("rejects unpatched, incompatible or missing dependencies (%s)", (contents) => {
    const root = fixture(contents);
    expect(() => checkPatches(root)).toThrow(/fixture\+1\.0\.0\.patch.*npm ci/);
    if (contents !== null) {
      expect(readFileSync(join(root, "node_modules", "fixture", "index.js"), "utf8")).toBe(contents);
    }
  });

  it("checks every patch instead of accepting only the first", () => {
    const root = fixture("after\n");
    writeFileSync(join(root, "patches", "missing+1.0.0.patch"),
      "--- a/node_modules/fixture/missing.js\n+++ b/node_modules/fixture/missing.js\n@@ -1 +1 @@\n-before\n+after\n");
    expect(() => checkPatches(root)).toThrow(/missing\+1\.0\.0\.patch/);
  });
});
