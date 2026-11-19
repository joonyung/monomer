export const TABLE_SOURCE_BYTES = 256 * 1024;
export const TABLE_SVG_BYTES = 32 * 1024;
export const TABLE_SVG_NODES = 512;
export const TABLE_SVG_DEPTH = 16;
export const TABLE_BATCH_BYTES = 512 * 1024;
export const TABLE_BATCH_CELLS = 2048;
export const TABLE_PREPARE_TIMEOUT_MS = 10_000;
export const TABLE_BATCH_TIMEOUT_MS = 2_000;
export const TABLE_TEXT_LENGTH = 4096;
export const TABLE_TOOLTIP_LENGTH = 8192;
const encoder = new TextEncoder();
export function byteLength(value: string): number { return encoder.encode(value).byteLength; }
