import { isAbsolute, relative, resolve, sep } from "node:path";

// Specs name tracked evidence paths relative to web-app/, the Playwright working
// directory. A default run mirrors them by repository-relative path under the
// ignored test-results/evidence/ so it never rewrites tracked files;
// MONOMER_E2E_EVIDENCE=update refreshes the tracked files in place.
const webAppDirectory = resolve(import.meta.dirname, "../..");
const repositoryDirectory = resolve(webAppDirectory, "..");
const ignoredEvidenceDirectory = resolve(webAppDirectory, "test-results/evidence");

const evidenceMode = process.env.MONOMER_E2E_EVIDENCE ?? "";
if (evidenceMode !== "" && evidenceMode !== "update") {
  throw new Error(`MONOMER_E2E_EVIDENCE must be unset or "update", got "${evidenceMode}".`);
}

export function evidencePath(trackedPath: string): string {
  if (evidenceMode === "update") return trackedPath;
  const repositoryRelativePath = relative(repositoryDirectory, resolve(webAppDirectory, trackedPath));
  if (repositoryRelativePath === "" || repositoryRelativePath === ".." || repositoryRelativePath.startsWith(`..${sep}`) || isAbsolute(repositoryRelativePath)) {
    throw new Error(`Evidence path must name a location inside the repository: ${trackedPath}`);
  }
  return resolve(ignoredEvidenceDirectory, repositoryRelativePath);
}
