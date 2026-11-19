import { apiFetch, apiJson } from "@/lib/api/client";
import { workspaceBffUrl } from "@/api/workspace-bff";
import { PagesApiError, toPagesApiError } from "@/api/pages";
import { CONTROL_API_URL } from "@/lib/auth/config";
import type { AgentApp } from "@/lib/types/provenance";

export type PageContentFormat = "MARKDOWN" | "NOTEBOOK" | "HTML";

export interface PageContentVersionResponse {
  pageId: string;
  versionNo: number;
  format: PageContentFormat;
  contentHash: string;
  contentSizeBytes: number;
  finalizedBy: { kind: "USER" | "AGENT" | "SYSTEM"; id: string; displayName: string | null; agentApp: AgentApp | null };
  finalizedAt: string;
}

export interface PageContentDocumentResponse {
  title: string;
  version: PageContentVersionResponse;
  attachmentUrl: string;
}

export interface PageContentVersionPageResponse {
  items: PageContentVersionResponse[];
  nextBeforeVersionNo: number | null;
}

export interface PageContentVersionMutationResponse {
  result: "APPLIED" | "REPLAYED";
  version: PageContentVersionResponse;
}

interface PageContentUploadPartResponse {
  partNumber: number;
  contentLength: number;
  url: string;
  requiredHeaders: Record<string, string>;
}

interface PageContentUploadSessionResponse {
  uploadRef: string;
  format: PageContentFormat;
  byteSize: number;
  sha256: string;
  expiresAt: string;
  partSizeBytes: number;
  parts: PageContentUploadPartResponse[];
}

interface PageContentUploadCompletionResponse {
  result: "COMPLETED" | "REPLAYED";
  upload: {
    uploadRef: string;
    format: PageContentFormat;
    byteSize: number;
    sha256: string;
    expiresAt: string;
  };
}

export interface PageContentUploadProgress {
  uploadedBytes: number;
  totalBytes: number;
}

export interface PageContentAttachmentDownload {
  blob: Blob;
  filename: string;
}

export interface VerifiedPageContentSource extends PageContentAttachmentDownload {
  bytes: Uint8Array<ArrayBuffer>;
}

export interface PageContentPreparedUpload {
  uploadRef: string;
  complete: (signal?: AbortSignal) => Promise<string>;
  abort: () => Promise<void>;
}

const MAXIMUM_SOURCE_BYTES = 20 * 1024 * 1024;

function uploadsUrl(workspaceId: string): string {
  return workspaceBffUrl(workspaceId, "/page-content-uploads");
}

function pageContentUrl(workspaceId: string, pageId: string): string {
  return workspaceBffUrl(
    workspaceId,
    `/pages/${encodeURIComponent(pageId)}/content`,
  );
}

async function contentJson<T>(
  url: string,
  init: Parameters<typeof apiJson<T>>[1],
): Promise<T> {
  try {
    return await apiJson<T>(url, { ...init, cache: "no-store" });
  } catch (error) {
    throw toPagesApiError(error);
  }
}

function validateSourceFile(file: File): void {
  if (!Number.isSafeInteger(file.size) || file.size <= 0 || file.size > MAXIMUM_SOURCE_BYTES) {
    throw new PagesApiError(
      `Choose a file between 1 byte and ${MAXIMUM_SOURCE_BYTES} bytes.`,
      0,
      null,
      null,
    );
  }
}

async function sha256Hex(file: File): Promise<string> {
  if (!globalThis.crypto?.subtle) {
    throw new PagesApiError("This browser cannot verify document content.", 0, null, null);
  }
  const digest = await globalThis.crypto.subtle.digest("SHA-256", await file.arrayBuffer());
  return Array.from(new Uint8Array(digest), (byte) => byte.toString(16).padStart(2, "0")).join("");
}

async function sha256Bytes(bytes: Uint8Array<ArrayBuffer>): Promise<string> {
  if (!globalThis.crypto?.subtle) {
    throw new PagesApiError("This browser cannot verify document content.", 0, null, null);
  }
  const digest = await globalThis.crypto.subtle.digest("SHA-256", bytes);
  return Array.from(new Uint8Array(digest), (byte) => byte.toString(16).padStart(2, "0")).join("");
}

function validateUploadSession(
  session: PageContentUploadSessionResponse,
  format: PageContentFormat,
  file: File,
  sha256: string,
): void {
  if (
    session.format !== format ||
    session.byteSize !== file.size ||
    session.sha256 !== sha256 ||
    !Number.isSafeInteger(session.partSizeBytes) ||
    session.partSizeBytes <= 0 ||
    session.parts.length === 0 ||
    session.parts.length > 7 ||
    session.parts.reduce((total, part) => total + part.contentLength, 0) !== file.size
  ) {
    throw new PagesApiError("The upload session did not match the selected file.", 0, null, null);
  }
  session.parts.forEach((part, index) => {
    const requiredHeaderEntries = Object.entries(part.requiredHeaders);
    if (
      part.partNumber !== index + 1 ||
      !Number.isSafeInteger(part.contentLength) ||
      part.contentLength <= 0 ||
      part.contentLength > session.partSizeBytes ||
      requiredHeaderEntries.length !== 1 ||
      requiredHeaderEntries[0]?.[0].toLowerCase() !== "content-length" ||
      requiredHeaderEntries[0]?.[1] !== String(part.contentLength)
    ) {
      throw new PagesApiError("The upload session returned an invalid part contract.", 0, null, null);
    }
    let url: URL;
    try {
      url = new URL(part.url);
    } catch {
      throw new PagesApiError("The upload destination is not allowed.", 0, null, null);
    }
    const localDevelopmentHost = ["localhost", "127.0.0.1", "::1", "[::1]"].includes(url.hostname);
    if (!(url.protocol === "https:" || (url.protocol === "http:" && localDevelopmentHost))) {
      throw new PagesApiError("The upload destination is not allowed.", 0, null, null);
    }
    if (url.username || url.password) {
      throw new PagesApiError("The upload destination is not allowed.", 0, null, null);
    }
  });
}

async function uploadParts(
  session: PageContentUploadSessionResponse,
  file: File,
  signal: AbortSignal | undefined,
  onProgress: ((progress: PageContentUploadProgress) => void) | undefined,
): Promise<Array<{ partNumber: number; eTag: string }>> {
  const completedParts: Array<{ partNumber: number; eTag: string }> = [];
  let offset = 0;
  for (const part of session.parts) {
    const nextOffset = offset + part.contentLength;
    const partBody = await file.slice(offset, nextOffset).arrayBuffer();
    if (partBody.byteLength !== part.contentLength) {
      throw new PagesApiError("The selected file changed while it was uploading.", 0, null, null);
    }
    const response = await fetch(part.url, {
      method: "PUT",
      body: partBody,
      credentials: "omit",
      referrerPolicy: "no-referrer",
      cache: "no-store",
      signal,
    });
    if (!response.ok) {
      throw new PagesApiError(`File upload failed with ${response.status}.`, response.status, null, null);
    }
    const eTag = response.headers.get("ETag")?.trim();
    if (!eTag || eTag.length > 512) {
      throw new PagesApiError("The object store did not confirm the uploaded part.", 0, null, null);
    }
    completedParts.push({ partNumber: part.partNumber, eTag });
    offset = nextOffset;
    onProgress?.({ uploadedBytes: offset, totalBytes: file.size });
  }
  return completedParts;
}

async function abortUpload(workspaceId: string, uploadRef: string): Promise<void> {
  try {
    await apiFetch(`${uploadsUrl(workspaceId)}/${encodeURIComponent(uploadRef)}`, {
      method: "DELETE",
    });
  } catch {
    return;
  }
}

function validateUploadCompletion(
  completion: PageContentUploadCompletionResponse,
  session: PageContentUploadSessionResponse,
  format: PageContentFormat,
  file: File,
  sha256: string,
): void {
  if (
    (completion.result !== "COMPLETED" && completion.result !== "REPLAYED") ||
    completion.upload.uploadRef !== session.uploadRef ||
    completion.upload.format !== format ||
    completion.upload.byteSize !== file.size ||
    completion.upload.sha256 !== sha256
  ) {
    throw new PagesApiError("The completed upload did not match the selected file.", 0, null, null);
  }
}

function attachmentFilename(contentDisposition: string | null): string {
  if (!contentDisposition) return "document";
  const encoded = /filename\*=UTF-8''([^;]+)/i.exec(contentDisposition)?.[1];
  if (encoded) {
    try {
      return decodeURIComponent(encoded);
    } catch {
      return "document";
    }
  }
  return /filename="([^"]+)"/i.exec(contentDisposition)?.[1] ?? "document";
}

function authenticatedAttachmentUrl(rawUrl: string): string {
  const serviceOrigin = new URL(CONTROL_API_URL).origin;
  const url = new URL(rawUrl, `${serviceOrigin}/`);
  if (url.origin !== serviceOrigin || url.username || url.password) {
    throw new PagesApiError("The attachment URL is not allowed.", 0, null, null);
  }
  return url.toString();
}

export const pageContentAPI = {
  async prepareUpload(
    workspaceId: string,
    format: PageContentFormat,
    file: File,
    signal?: AbortSignal,
    onProgress?: (progress: PageContentUploadProgress) => void,
  ): Promise<PageContentPreparedUpload> {
    validateSourceFile(file);
    const sha256 = await sha256Hex(file);
    let session: PageContentUploadSessionResponse | null = null;
    try {
      session = await contentJson<PageContentUploadSessionResponse>(uploadsUrl(workspaceId), {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ format, byteSize: file.size, sha256 }),
        signal,
      });
      validateUploadSession(session, format, file, sha256);
      const parts = await uploadParts(session, file, signal, onProgress);
      const preparedSession = session;
      return {
        uploadRef: preparedSession.uploadRef,
        complete: async (completionSignal?: AbortSignal) => {
          const completion = await contentJson<PageContentUploadCompletionResponse>(
            `${uploadsUrl(workspaceId)}/${encodeURIComponent(preparedSession.uploadRef)}/complete`,
            {
              method: "POST",
              headers: { "Content-Type": "application/json" },
              body: JSON.stringify({ parts }),
              signal: completionSignal,
            },
          );
          validateUploadCompletion(completion, preparedSession, format, file, sha256);
          return preparedSession.uploadRef;
        },
        abort: () => abortUpload(workspaceId, preparedSession.uploadRef),
      };
    } catch (error) {
      if (session) await abortUpload(workspaceId, session.uploadRef);
      throw error;
    }
  },

  async getContent(
    workspaceId: string,
    pageId: string,
    versionNo?: number,
    signal?: AbortSignal,
  ): Promise<PageContentDocumentResponse> {
    const query = versionNo === undefined ? "" : `?versionNo=${encodeURIComponent(versionNo)}`;
    return contentJson(`${pageContentUrl(workspaceId, pageId)}${query}`, { signal });
  },

  async listVersions(
    workspaceId: string,
    pageId: string,
    beforeVersionNo: number | null,
    limit: number,
    signal?: AbortSignal,
  ): Promise<PageContentVersionPageResponse> {
    const params = new URLSearchParams({ limit: String(limit) });
    if (beforeVersionNo !== null) params.set("beforeVersionNo", String(beforeVersionNo));
    return contentJson(
      `${workspaceBffUrl(workspaceId, `/pages/${encodeURIComponent(pageId)}/versions`)}?${params}`,
      { signal },
    );
  },

  async replaceContent(
    workspaceId: string,
    pageId: string,
    uploadRef: string,
    requestId: string,
    signal?: AbortSignal,
  ): Promise<PageContentVersionMutationResponse> {
    return contentJson(
      workspaceBffUrl(workspaceId, `/pages/${encodeURIComponent(pageId)}/versions`),
      {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ uploadRef, requestId }),
        signal,
      },
    );
  },

  async downloadAttachment(
    attachmentUrl: string,
    signal?: AbortSignal,
  ): Promise<PageContentAttachmentDownload> {
    try {
      const response = await apiFetch(authenticatedAttachmentUrl(attachmentUrl), {
        signal,
        cache: "no-store",
      });
      return {
        blob: await response.blob(),
        filename: attachmentFilename(response.headers.get("Content-Disposition")),
      };
    } catch (error) {
      throw toPagesApiError(error);
    }
  },

  async readVerifiedSource(
    document: PageContentDocumentResponse,
    signal?: AbortSignal,
  ): Promise<VerifiedPageContentSource> {
    const attachment = await this.downloadAttachment(document.attachmentUrl, signal);
    if (attachment.blob.size !== document.version.contentSizeBytes) {
      throw new PagesApiError("The document source size did not match its version.", 0, null, null);
    }
    const bytes = new Uint8Array(await attachment.blob.arrayBuffer());
    if (await sha256Bytes(bytes) !== document.version.contentHash) {
      throw new PagesApiError("The document source hash did not match its version.", 0, null, null);
    }
    return {
      blob: new Blob([bytes], { type: "application/octet-stream" }),
      filename: attachment.filename,
      bytes,
    };
  },
};

export const pageContentLimits = {
  maximumSourceBytes: MAXIMUM_SOURCE_BYTES,
};

export const _internal = {
  attachmentFilename,
  authenticatedAttachmentUrl,
  sha256Bytes,
};
