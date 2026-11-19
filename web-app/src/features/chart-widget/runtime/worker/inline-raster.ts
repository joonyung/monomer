import { INLINE_RASTER_BYTE_LIMIT } from "./canonical-boundary";

export type InlineRaster = Readonly<{
  mimeType: "image/png" | "image/jpeg" | "image/webp";
  bytes: Uint8Array;
}>;

const INLINE_RASTER_PATTERN = /^data:(image\/(?:png|jpeg|webp));base64,([a-z0-9+/=]+)$/i;

function decodeBase64(payload: string): Uint8Array {
  const padding = payload.endsWith("==") ? 2 : payload.endsWith("=") ? 1 : 0;
  const fileBytes = Math.floor(payload.length * 3 / 4) - padding;
  if (fileBytes > INLINE_RASTER_BYTE_LIMIT) {
    throw new Error("Inline raster image exceeds the byte budget");
  }
  const binary = atob(payload);
  const bytes = new Uint8Array(binary.length);
  for (let index = 0; index < binary.length; index += 1) bytes[index] = binary.charCodeAt(index);
  return bytes;
}

export function parseInlineRasterDataUri(source: string): InlineRaster {
  const dataUri = source.startsWith("image://") ? source.slice("image://".length) : source;
  const match = INLINE_RASTER_PATTERN.exec(dataUri);
  if (!match) throw new Error("Only inline PNG, JPEG, and WebP data URIs are supported");
  const bytes = decodeBase64(match[2]);
  if (bytes.byteLength === 0) throw new Error("Inline raster image cannot be empty");
  return { mimeType: match[1].toLowerCase() as InlineRaster["mimeType"], bytes };
}

/**
 * zrender expects a synchronous image-like object and calls its load callback later.
 * A one-pixel OffscreenCanvas is the placeholder; the decoded bitmap is painted into
 * it before zrender is notified. No URL or browser Image object is ever created.
 */
export function loadInlineRasterImage(
  source: string,
  onload: () => void,
  onerror: () => void,
): HTMLImageElement {
  const raster = parseInlineRasterDataUri(source);
  if (typeof OffscreenCanvas === "undefined" || typeof createImageBitmap !== "function") {
    throw new Error("Inline raster decoding requires OffscreenCanvas and createImageBitmap");
  }
  const canvas = new OffscreenCanvas(1, 1);
  const initialContext = canvas.getContext("2d");
  if (!initialContext) throw new Error("Chart Worker cannot create a 2D image context");
  const image = canvas as unknown as HTMLImageElement;
  void createImageBitmap(new Blob([raster.bytes.buffer as ArrayBuffer], { type: raster.mimeType }))
    .then((bitmap) => {
      canvas.width = bitmap.width;
      canvas.height = bitmap.height;
      const context = canvas.getContext("2d");
      if (!context) throw new Error("Chart Worker cannot paint an inline raster image");
      context.drawImage(bitmap, 0, 0);
      bitmap.close();
      onload.call(image);
    })
    .catch(() => onerror.call(image));
  return image;
}
