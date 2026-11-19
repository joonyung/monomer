import { describe, expect, it } from "vitest";
import {
  getDocumentContentFixture,
  getDocumentVersionFixture,
  resolveDocumentContentFixture,
  resolveDocumentSourceFixture,
} from "@/components/pages/documentContentFixtures";
import {
  PAGES_FIXTURE_HTML_PAGE_ID,
  PAGES_FIXTURE_MARKDOWN_PAGE_ID,
  PAGES_FIXTURE_NOTEBOOK_PAGE_ID,
} from "@/components/pages/pagesFixtures";

describe("document content fixtures", () => {
  it("resolves latest and exact immutable Markdown versions", () => {
    expect(getDocumentContentFixture(PAGES_FIXTURE_MARKDOWN_PAGE_ID)?.version.versionNo).toBe(3);
    expect(resolveDocumentContentFixture(PAGES_FIXTURE_MARKDOWN_PAGE_ID, undefined, null)?.version.versionNo).toBe(3);
    expect(getDocumentContentFixture(PAGES_FIXTURE_MARKDOWN_PAGE_ID, 2)?.version.versionNo).toBe(2);
    expect(getDocumentContentFixture(PAGES_FIXTURE_MARKDOWN_PAGE_ID, 99)).toBeNull();
  });

  it("keeps exact source bytes for all three viewer formats", async () => {
    for (const pageId of [
      PAGES_FIXTURE_MARKDOWN_PAGE_ID,
      PAGES_FIXTURE_NOTEBOOK_PAGE_ID,
      PAGES_FIXTURE_HTML_PAGE_ID,
    ]) {
      const document = getDocumentContentFixture(pageId);
      const source = await resolveDocumentSourceFixture(pageId, undefined, null, null);
      expect(document).not.toBeNull();
      expect(source?.bytes.byteLength).toBe(document?.version.contentSizeBytes);
      const digest = await crypto.subtle.digest("SHA-256", source?.bytes as Uint8Array<ArrayBuffer>);
      expect(Array.from(new Uint8Array(digest), (byte) => byte.toString(16).padStart(2, "0")).join(""))
        .toBe(document?.version.contentHash);
    }
  });

  it("orders version history newest first", () => {
    expect(getDocumentVersionFixture(PAGES_FIXTURE_MARKDOWN_PAGE_ID)?.items.map((item) => item.versionNo))
      .toEqual([3, 2, 1]);
  });
});
