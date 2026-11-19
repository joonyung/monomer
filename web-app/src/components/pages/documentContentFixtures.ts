import type {
  PageContentDocumentResponse,
  PageContentFormat,
  PageContentVersionPageResponse,
  PageContentVersionResponse,
  VerifiedPageContentSource,
} from "@/api/page-content";
import {
  PAGES_FIXTURE_HTML_PAGE_ID,
  PAGES_FIXTURE_MARKDOWN_PAGE_ID,
  PAGES_FIXTURE_NOTEBOOK_PAGE_ID,
  PAGES_FIXTURE_WORKSPACE_ID,
} from "@/components/pages/pagesFixtures";

interface StoredFixtureVersion {
  document: PageContentDocumentResponse;
  source: string;
}

interface DocumentContentFixture {
  versions: PageContentVersionResponse[];
  contentByVersion: Record<number, StoredFixtureVersion>;
}

const FINALIZED_BY = { kind: "USER" as const, id: "pages-fixture-user", displayName: "Pages fixture user", agentApp: null };
const MARKDOWN_3 = "# Q2 operating review\n\nRevenue finished **8.4% above plan**.\n\n## Decisions\n\n- Keep the enterprise packaging experiment.\n- Review activation cohort quality weekly.\n";
const MARKDOWN_2 = "# Q2 operating review — draft\n\nEarlier review notes before finance sign-off.\n";
const MARKDOWN_1 = "# Q2 review outline\n\nInitial operating review outline.\n";
const NOTEBOOK = "{\"nbformat\":4,\"nbformat_minor\":5,\"metadata\":{\"language_info\":{\"name\":\"python\"}},\"cells\":[{\"id\":\"intro\",\"cell_type\":\"markdown\",\"metadata\":{},\"source\":[\"# Retention cohort analysis\\n\",\"Stored outputs only. No cells are executed in Monomer.\"]},{\"id\":\"query\",\"cell_type\":\"code\",\"metadata\":{},\"execution_count\":4,\"source\":[\"cohorts = retention_by_signup_month\\n\",\"cohorts.head()\"],\"outputs\":[{\"output_type\":\"stream\",\"name\":\"stdout\",\"text\":[\"loaded 3 cohorts\\\\n\"]},{\"output_type\":\"display_data\",\"metadata\":{},\"data\":{\"text/plain\":[\"January 82%\\\\nFebruary 84%\"],\"text/html\":[\"<script>parent.fetch('/v2/private')</script>\"]}},{\"output_type\":\"display_data\",\"metadata\":{},\"data\":{\"image/png\":\"iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Y9ZlM8AAAAASUVORK5CYII=\"}},{\"output_type\":\"display_data\",\"metadata\":{},\"data\":{\"application/vnd.jupyter.widget-view+json\":{\"model_id\":\"blocked\"}}}]}]}";
const HTML = "<!doctype html><html><head><title>Campaign readout</title><script>parent.fetch(\"/v2/private\")</script><meta http-equiv=\"refresh\" content=\"0;url=https://example.com\"><style>body{background-image:url(https://example.com/tracker.png)}</style></head><body><main style=\"max-width: 760px; margin: 0 auto\"><p>Campaign readout · Q2 2026</p><h1>Campaign efficiency by channel</h1><p>Paid search delivered the strongest qualified pipeline.</p><table><thead><tr><th>Channel</th><th>Return</th></tr></thead><tbody><tr><td>Paid search</td><td>10.3×</td></tr></tbody></table><img alt=\"Stored chart\" width=\"40\" height=\"40\" src=\"data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Y9ZlM8AAAAASUVORK5CYII=\"><img src=\"https://example.com/tracker.png\" onerror=\"parent.postMessage(1,`*`)\"><form action=\"https://example.com\"><button>Send</button></form></main></body></html>";

function attachmentUrl(pageId: string, versionNo: number): string {
  return `/v2/workspaces/${PAGES_FIXTURE_WORKSPACE_ID}/pages/${pageId}/content/attachment?versionNo=${versionNo}`;
}

function version(
  pageId: string,
  versionNo: number,
  format: PageContentFormat,
  contentHash: string,
  contentSizeBytes: number,
  finalizedAt: string,
): PageContentVersionResponse {
  return { pageId, versionNo, format, contentHash, contentSizeBytes, finalizedBy: FINALIZED_BY, finalizedAt };
}

function stored(title: string, versionValue: PageContentVersionResponse, source: string): StoredFixtureVersion {
  return {
    document: { title, version: versionValue, attachmentUrl: attachmentUrl(versionValue.pageId, versionValue.versionNo) },
    source,
  };
}

const markdownVersions = [
  version(PAGES_FIXTURE_MARKDOWN_PAGE_ID, 3, "MARKDOWN", "0f82c1c0c0e208266a36e892228bf8a6dc8808e9571f2faa1e4991fd3042d659", 163, "2026-07-10T08:40:00Z"),
  version(PAGES_FIXTURE_MARKDOWN_PAGE_ID, 2, "MARKDOWN", "282a236f0ad24c61964c3b5b337a66e47af6391077257fe5db9a56eb6085dbc4", 79, "2026-07-03T08:40:00Z"),
  version(PAGES_FIXTURE_MARKDOWN_PAGE_ID, 1, "MARKDOWN", "3dd8ac64aedacd534fdc5645e177792d345b81816b88e227c495ad106f348cfa", 55, "2026-06-26T08:40:00Z"),
];
const notebookVersion = version(PAGES_FIXTURE_NOTEBOOK_PAGE_ID, 1, "NOTEBOOK", "41925d99fb68a2b4c38a1027b668213cecddaf22c4b4480e1b0394fde0c60a22", 902, "2026-07-10T07:12:00Z");
const htmlVersion = version(PAGES_FIXTURE_HTML_PAGE_ID, 1, "HTML", "31805b563bf6f7847fcd99327205d3bedca6fcf04b05926b230fbc4cbf1b443f", 894, "2026-07-10T06:30:00Z");

const FIXTURES: Record<string, DocumentContentFixture> = {
  [PAGES_FIXTURE_MARKDOWN_PAGE_ID]: {
    versions: markdownVersions,
    contentByVersion: {
      3: stored("Q2 operating brief", markdownVersions[0] as PageContentVersionResponse, MARKDOWN_3),
      2: stored("Q2 operating brief", markdownVersions[1] as PageContentVersionResponse, MARKDOWN_2),
      1: stored("Q2 operating brief", markdownVersions[2] as PageContentVersionResponse, MARKDOWN_1),
    },
  },
  [PAGES_FIXTURE_NOTEBOOK_PAGE_ID]: {
    versions: [notebookVersion],
    contentByVersion: { 1: stored("Retention cohort analysis", notebookVersion, NOTEBOOK) },
  },
  [PAGES_FIXTURE_HTML_PAGE_ID]: {
    versions: [htmlVersion],
    contentByVersion: { 1: stored("Campaign readout", htmlVersion, HTML) },
  },
};

function verifiedSource(source: string, format: PageContentFormat, title: string): VerifiedPageContentSource {
  const bytes = new TextEncoder().encode(source) as Uint8Array<ArrayBuffer>;
  const extension = format === "MARKDOWN" ? "md" : format === "NOTEBOOK" ? "ipynb" : "html";
  return { blob: new Blob([bytes]), bytes, filename: `${title}.${extension}` };
}

export function getDocumentContentFixture(pageId: string, versionNo?: number): PageContentDocumentResponse | null {
  const fixture = FIXTURES[pageId];
  if (!fixture) return null;
  const resolvedVersion = versionNo ?? fixture.versions[0]?.versionNo;
  return resolvedVersion === undefined ? null : fixture.contentByVersion[resolvedVersion]?.document ?? null;
}

export function getDocumentVersionFixture(pageId: string): PageContentVersionPageResponse | null {
  const fixture = FIXTURES[pageId];
  return fixture ? { items: fixture.versions, nextBeforeVersionNo: null } : null;
}

export function resolveDocumentContentFixture(
  pageId: string,
  versionNo: number | undefined,
  replacement: PageContentDocumentResponse | null,
): PageContentDocumentResponse | null {
  if (replacement && (versionNo === undefined || replacement.version.versionNo === versionNo)) return replacement;
  return getDocumentContentFixture(pageId, versionNo);
}

export async function resolveDocumentSourceFixture(
  pageId: string,
  versionNo: number | undefined,
  replacement: PageContentDocumentResponse | null,
  replacementSource: File | null,
): Promise<VerifiedPageContentSource | null> {
  if (replacement && (versionNo === undefined || replacement.version.versionNo === versionNo)) {
    if (!replacementSource) return null;
    const bytes = new Uint8Array(await replacementSource.arrayBuffer()) as Uint8Array<ArrayBuffer>;
    return { blob: replacementSource, bytes, filename: replacementSource.name };
  }
  const fixture = FIXTURES[pageId];
  const resolvedVersion = versionNo ?? fixture?.versions[0]?.versionNo;
  if (!fixture || resolvedVersion === undefined) return null;
  const storedVersion = fixture.contentByVersion[resolvedVersion];
  return storedVersion
    ? verifiedSource(storedVersion.source, storedVersion.document.version.format, storedVersion.document.title)
    : null;
}
