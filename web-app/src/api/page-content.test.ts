import { beforeEach, describe, expect, it, vi } from "vitest";
import {
  _internal,
  pageContentAPI,
  pageContentLimits,
} from "@/api/page-content";
import { PagesApiError } from "@/api/pages";
import { useAuthStore } from "@/lib/auth/store";

const workspaceId = "019e501f-0000-7000-8000-000000000011";
const pageId = "019e501f-0000-7000-8000-000000000012";

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

async function sourceFile(contents = "hello"): Promise<{ file: File; sha256: string }> {
  const file = new File([contents], "brief.md", { type: "text/markdown" });
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(contents));
  const sha256 = Array.from(new Uint8Array(digest), (byte) => byte.toString(16).padStart(2, "0")).join("");
  return { file, sha256 };
}

describe("pageContentAPI", () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    useAuthStore.setState({
      status: "authenticated",
      accessToken: "pages-token",
      accessTokenExpiresAt: "2030-01-01T00:00:00Z",
    });
  });

  it("rejects a source larger than the shared 20 MiB limit before network access", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch");
    const oversized = new File(
      [new Uint8Array(pageContentLimits.maximumSourceBytes + 1)],
      "oversized.ipynb",
      { type: "application/x-ipynb+json" },
    );

    await expect(pageContentAPI.prepareUpload(workspaceId, "NOTEBOOK", oversized))
      .rejects.toMatchObject({
        name: "PagesApiError",
        message: `Choose a file between 1 byte and ${pageContentLimits.maximumSourceBytes} bytes.`,
      });
    expect(pageContentLimits.maximumSourceBytes).toBe(20 * 1024 * 1024);
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("hashes, uploads credentiallessly, and completes the exact part contract", async () => {
    const { file, sha256 } = await sourceFile();
    const fetchMock = vi.spyOn(globalThis, "fetch")
      .mockResolvedValueOnce(jsonResponse(201, {
        uploadRef: "upload-1",
        format: "MARKDOWN",
        byteSize: file.size,
        sha256,
        expiresAt: "2030-01-01T00:00:00Z",
        partSizeBytes: file.size,
        parts: [{
          partNumber: 1,
          contentLength: file.size,
          url: "https://objects.example.test/upload-1/part-1",
          requiredHeaders: { "Content-Length": String(file.size) },
        }],
      }))
      .mockResolvedValueOnce(new Response(null, {
        status: 200,
        headers: { ETag: '"part-etag"' },
      }))
      .mockResolvedValueOnce(jsonResponse(200, {
        result: "COMPLETED",
        upload: {
          uploadRef: "upload-1",
          format: "MARKDOWN",
          byteSize: file.size,
          sha256,
          expiresAt: "2030-01-01T00:00:00Z",
        },
      }));
    const onProgress = vi.fn();

    const prepared = await pageContentAPI.prepareUpload(
      workspaceId,
      "MARKDOWN",
      file,
      undefined,
      onProgress,
    );
    expect(prepared.uploadRef).toBe("upload-1");
    await expect(prepared.complete()).resolves.toBe("upload-1");

    expect(fetchMock).toHaveBeenCalledTimes(3);
    const createBody = JSON.parse(String(fetchMock.mock.calls[0][1]?.body));
    expect(createBody).toEqual({ format: "MARKDOWN", byteSize: file.size, sha256 });
    expect(fetchMock.mock.calls[1][0]).toBe("https://objects.example.test/upload-1/part-1");
    expect(fetchMock.mock.calls[1][1]).toMatchObject({
      method: "PUT",
      credentials: "omit",
      referrerPolicy: "no-referrer",
      cache: "no-store",
    });
    expect(JSON.parse(String(fetchMock.mock.calls[2][1]?.body))).toEqual({
      parts: [{ partNumber: 1, eTag: '"part-etag"' }],
    });
    expect(onProgress).toHaveBeenLastCalledWith({ uploadedBytes: file.size, totalBytes: file.size });
  });

  it("rejects an unsafe upload destination before PUT and aborts the session", async () => {
    const { file, sha256 } = await sourceFile();
    const fetchMock = vi.spyOn(globalThis, "fetch")
      .mockResolvedValueOnce(jsonResponse(201, {
        uploadRef: "upload-unsafe",
        format: "MARKDOWN",
        byteSize: file.size,
        sha256,
        expiresAt: "2030-01-01T00:00:00Z",
        partSizeBytes: file.size,
        parts: [{
          partNumber: 1,
          contentLength: file.size,
          url: "http://objects.example.test/part-1",
          requiredHeaders: { "Content-Length": String(file.size) },
        }],
      }))
      .mockResolvedValueOnce(new Response(null, { status: 204 }));

    await expect(pageContentAPI.prepareUpload(workspaceId, "MARKDOWN", file))
      .rejects.toMatchObject({ name: "PagesApiError", message: "The upload destination is not allowed." });

    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect(String(fetchMock.mock.calls[1][0])).toContain("/page-content-uploads/upload-unsafe");
    expect(fetchMock.mock.calls[1][1]?.method).toBe("DELETE");
  });

  it("rejects mismatched required headers", async () => {
    const { file, sha256 } = await sourceFile();
    const fetchMock = vi.spyOn(globalThis, "fetch")
      .mockResolvedValueOnce(jsonResponse(201, {
        uploadRef: "upload-malformed",
        format: "MARKDOWN",
        byteSize: file.size,
        sha256,
        expiresAt: "2030-01-01T00:00:00Z",
        partSizeBytes: file.size,
        parts: [{
          partNumber: 1,
          contentLength: file.size,
          url: "not a URL",
          requiredHeaders: { "Content-Length": "999" },
        }],
      }))
      .mockResolvedValueOnce(new Response(null, { status: 204 }));

    await expect(pageContentAPI.prepareUpload(workspaceId, "MARKDOWN", file))
      .rejects.toMatchObject({ name: "PagesApiError", message: "The upload session returned an invalid part contract." });
    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect(fetchMock.mock.calls[1][1]?.method).toBe("DELETE");
  });

  it("rejects malformed upload URLs before PUT", async () => {
    const { file, sha256 } = await sourceFile();
    const fetchMock = vi.spyOn(globalThis, "fetch")
      .mockResolvedValueOnce(jsonResponse(201, {
        uploadRef: "upload-malformed-url",
        format: "MARKDOWN",
        byteSize: file.size,
        sha256,
        expiresAt: "2030-01-01T00:00:00Z",
        partSizeBytes: file.size,
        parts: [{
          partNumber: 1,
          contentLength: file.size,
          url: "not a URL",
          requiredHeaders: { "Content-Length": String(file.size) },
        }],
      }))
      .mockResolvedValueOnce(new Response(null, { status: 204 }));

    await expect(pageContentAPI.prepareUpload(workspaceId, "MARKDOWN", file))
      .rejects.toMatchObject({ name: "PagesApiError", message: "The upload destination is not allowed." });
    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect(fetchMock.mock.calls[1][1]?.method).toBe("DELETE");
  });

  it("keeps uploaded parts recoverable when completion has an uncertain network result", async () => {
    const { file, sha256 } = await sourceFile();
    const completionBody = {
      result: "REPLAYED",
      upload: {
        uploadRef: "upload-recoverable",
        format: "MARKDOWN",
        byteSize: file.size,
        sha256,
        expiresAt: "2030-01-01T00:00:00Z",
      },
    };
    const fetchMock = vi.spyOn(globalThis, "fetch")
      .mockResolvedValueOnce(jsonResponse(201, {
        uploadRef: "upload-recoverable",
        format: "MARKDOWN",
        byteSize: file.size,
        sha256,
        expiresAt: "2030-01-01T00:00:00Z",
        partSizeBytes: file.size,
        parts: [{
          partNumber: 1,
          contentLength: file.size,
          url: "https://objects.example.test/upload-recoverable/part-1",
          requiredHeaders: { "Content-Length": String(file.size) },
        }],
      }))
      .mockResolvedValueOnce(new Response(null, { status: 200, headers: { ETag: '"etag"' } }))
      .mockRejectedValueOnce(new TypeError("network result lost"))
      .mockResolvedValueOnce(jsonResponse(200, completionBody));

    const prepared = await pageContentAPI.prepareUpload(workspaceId, "MARKDOWN", file);
    await expect(prepared.complete()).rejects.toMatchObject({ name: "PagesApiError", status: 0 });
    expect(fetchMock).toHaveBeenCalledTimes(3);
    expect(fetchMock.mock.calls.some(([, init]) => init?.method === "DELETE")).toBe(false);

    await expect(prepared.complete()).resolves.toBe("upload-recoverable");
    expect(fetchMock).toHaveBeenCalledTimes(4);
    expect(JSON.parse(String(fetchMock.mock.calls[2][1]?.body)))
      .toEqual(JSON.parse(String(fetchMock.mock.calls[3][1]?.body)));
  });

  it("pins exact content versions and disables response caching", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(jsonResponse(200, {
      title: "Brief",
      version: {
        pageId,
        versionNo: 3,
        format: "HTML",
        contentHash: "a".repeat(64),
        contentSizeBytes: 12,
        finalizedBy: { kind: "USER", id: "user-1", displayName: "Sam Kim", agentApp: null },
        finalizedAt: "2026-07-10T00:00:00Z",
      },
      attachmentUrl: `/v2/workspaces/${workspaceId}/pages/${pageId}/content/attachment?versionNo=3`,
    }));

    await pageContentAPI.getContent(workspaceId, pageId, 3);

    expect(String(fetchMock.mock.calls[0][0])).toContain(`/pages/${pageId}/content?versionNo=3`);
    expect(fetchMock.mock.calls[0][1]?.cache).toBe("no-store");
  });

  it("requests the next immutable history page with an exact cursor", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(jsonResponse(200, {
      items: [],
      nextBeforeVersionNo: 12,
    }));

    await pageContentAPI.listVersions(workspaceId, pageId, 37, 25);

    const url = new URL(String(fetchMock.mock.calls[0][0]));
    expect(url.pathname).toContain(`/pages/${pageId}/versions`);
    expect(url.searchParams.get("beforeVersionNo")).toBe("37");
    expect(url.searchParams.get("limit")).toBe("25");
    expect(fetchMock.mock.calls[0][1]?.cache).toBe("no-store");
  });

  it("hands the verified original source back as an opaque download, never as renderable HTML", async () => {
    const contents = "<form action=\"https://attacker.invalid\"><input name=\"password\"></form>";
    const { sha256 } = await sourceFile(contents);
    vi.spyOn(globalThis, "fetch").mockResolvedValue(new Response(contents, {
      status: 200,
      headers: { "Content-Type": "text/html", "Content-Disposition": "attachment; filename=\"brief.html\"" },
    }));

    const source = await pageContentAPI.readVerifiedSource({
      title: "Brief",
      version: {
        pageId,
        versionNo: 3,
        format: "HTML",
        contentHash: sha256,
        contentSizeBytes: new TextEncoder().encode(contents).byteLength,
        finalizedBy: { kind: "USER", id: "user-1", displayName: "Sam Kim", agentApp: null },
        finalizedAt: "2026-07-10T00:00:00Z",
      },
      attachmentUrl: `/v2/workspaces/${workspaceId}/pages/${pageId}/content/attachment?versionNo=3`,
    });

    expect(source.blob.type).toBe("application/octet-stream");
    expect(source.filename).toBe("brief.html");
    expect(new TextDecoder().decode(source.bytes)).toBe(contents);
  });

  it("rejects cross-origin attachment URLs before making a request", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch");

    await expect(pageContentAPI.downloadAttachment("https://attacker.invalid/file"))
      .rejects.toBeInstanceOf(PagesApiError);

    expect(fetchMock).not.toHaveBeenCalled();
  });
});

describe("page content response helpers", () => {
  it("uses safe fallback names for malformed encoded filenames", () => {
    expect(_internal.attachmentFilename("attachment; filename*=UTF-8''brief%20v3.md")).toBe("brief v3.md");
    expect(_internal.attachmentFilename("attachment; filename*=UTF-8''%E0%A4%A")).toBe("document");
  });
});
