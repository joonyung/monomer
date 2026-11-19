import { existsSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { expect, it } from "vitest";
import { SCREEN_DOCS_PATHS, helpDocsUrlForPath, screenDocsUrl } from "./public-docs";

const docsSource = (path: string) => fileURLToPath(new URL(`../../../public-site/src/content/docs/en/docs/${path}.md`, import.meta.url));

it("points every screen at a published guide", () => {
  for (const path of Object.values(SCREEN_DOCS_PATHS)) {
    expect(existsSync(docsSource(path)), path).toBe(true);
  }
  expect(screenDocsUrl("lineage")).toBe("https://monomer.cc/en/docs/model/lineage/");
});

it("chooses the guide for the current screen", () => {
  const workspace = "/workspaces/0198ef28-2000-7000-8000-000000000001";
  expect(helpDocsUrlForPath(workspace)).toBe("https://monomer.cc/en/docs/start/overview/");
  expect(helpDocsUrlForPath(`${workspace}/activity`)).toBe("https://monomer.cc/en/docs/start/overview/");
  expect(helpDocsUrlForPath(`${workspace}/connectors`)).toBe("https://monomer.cc/en/docs/sources/connectors/");
  expect(helpDocsUrlForPath(`${workspace}/catalog`)).toBe("https://monomer.cc/en/docs/sources/catalog/");
  expect(helpDocsUrlForPath(`${workspace}/semantic-models/model-1/edit`)).toBe("https://monomer.cc/en/docs/model/semantic-models/");
  expect(helpDocsUrlForPath(`${workspace}/metrics/new`)).toBe("https://monomer.cc/en/docs/model/metrics/");
  expect(helpDocsUrlForPath(`${workspace}/explore`)).toBe("https://monomer.cc/en/docs/analyze/query-metrics/");
  expect(helpDocsUrlForPath(`${workspace}/dashboards/dashboard-1`)).toBe("https://monomer.cc/en/docs/dashboards/view/");
  expect(helpDocsUrlForPath(`${workspace}/pages/page-1`)).toBe("https://monomer.cc/en/docs/dashboards/pages/");
  expect(helpDocsUrlForPath(`${workspace}/settings/public-access`)).toBe("https://monomer.cc/en/docs/dashboards/public-pages/");
  expect(helpDocsUrlForPath("/settings/agents")).toBe("https://monomer.cc/en/docs/agents/connect/");
  expect(helpDocsUrlForPath("/settings/profile")).toBe("https://monomer.cc/en/docs/administer/user-settings/");
  expect(helpDocsUrlForPath("/workspaces")).toBe("https://monomer.cc/en/docs/start/workspaces/");
  expect(helpDocsUrlForPath(`${workspace}/unknown`)).toBe("https://monomer.cc/en/docs/");
  expect(helpDocsUrlForPath("/organizations/org-1/settings/members")).toBe("https://monomer.cc/en/docs/administer/members/");
  expect(helpDocsUrlForPath(`${workspace}/settings`)).toBe("https://monomer.cc/en/docs/administer/members/");
  expect(helpDocsUrlForPath("/organizations/org-1")).toBe("https://monomer.cc/en/docs/");
});
