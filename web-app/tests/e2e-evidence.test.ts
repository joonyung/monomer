import { readFileSync, readdirSync } from "node:fs";
import { join, resolve } from "node:path";
import { describe, expect, it } from "vitest";

const e2eDirectory = resolve(process.cwd(), "tests/e2e");
const rawEvidenceWrite = /(?:\bpath:|\b(?:writeFileSync|writeFile|mkdirSync|mkdir)\()\s*(?:["']|`(?!\$\{))/g;

describe("e2e evidence writes", () => {
  it("route every literal evidence path through evidencePath", () => {
    const offenders = readdirSync(e2eDirectory)
      .filter((name) => name.endsWith(".ts") && name !== "evidence.ts")
      .flatMap((name) => readFileSync(join(e2eDirectory, name), "utf8").split("\n").flatMap((line, index) => {
        rawEvidenceWrite.lastIndex = 0;
        return rawEvidenceWrite.test(line) ? [`${name}:${index + 1}: ${line.trim()}`] : [];
      }));
    expect(offenders).toEqual([]);
  });
});
