import type { CellView, RowView } from "../contract/abi";
import { TABLE_SVG_BYTES, TABLE_TEXT_LENGTH, TABLE_TOOLTIP_LENGTH, byteLength } from "./limits";
import type { TableCellOutput } from "./worker-protocol";

export const TABLE_COLOR = /^#[0-9a-fA-F]{6}(?:[0-9a-fA-F]{2})?$/;
const CELL_STYLE_KEYS = ["color", "background", "emphasis", "align", "tooltip", "graphic"];

export function ownRecord(value: unknown, keys: readonly string[]): Record<string, unknown> {
  if (value === null || typeof value !== "object" || Array.isArray(value)
    || (Object.getPrototypeOf(value) !== Object.prototype && Object.getPrototypeOf(value) !== null)) {
    throw new Error("Table output must be a plain object");
  }
  const output: Record<string, unknown> = Object.create(null);
  for (const key of Reflect.ownKeys(value)) {
    if (typeof key !== "string" || !keys.includes(key)) throw new Error("Unknown Table output property");
    const descriptor = Object.getOwnPropertyDescriptor(value, key);
    if (!descriptor || !("value" in descriptor) || !descriptor.enumerable) {
      throw new Error("Table output accessors are forbidden");
    }
    if (descriptor.value !== undefined) output[key] = descriptor.value;
  }
  return output;
}

function checkColor(value: unknown): void {
  if (value !== undefined && (typeof value !== "string" || !TABLE_COLOR.test(value))) {
    throw new Error("Table colors must use #RRGGBB or #RRGGBBAA");
  }
}

export function validateRowView(value: unknown): RowView | undefined {
  if (value === undefined) return undefined;
  const result = ownRecord(value, ["background"]);
  checkColor(result.background);
  return result as RowView;
}

function validateCellStyle(result: Record<string, unknown>): void {
  checkColor(result.color);
  checkColor(result.background);
  if (result.emphasis !== undefined && result.emphasis !== "normal" && result.emphasis !== "strong") throw new Error("Invalid Table emphasis");
  if (result.align !== undefined && !["start", "center", "end"].includes(result.align as string)) throw new Error("Invalid Table alignment");
  if (result.tooltip !== undefined && (typeof result.tooltip !== "string" || result.tooltip.length > TABLE_TOOLTIP_LENGTH)) throw new Error("Invalid Table tooltip");
  if (result.graphic !== undefined) {
    const graphic = ownRecord(result.graphic, ["svg"]);
    if (typeof graphic.svg !== "string" || byteLength(graphic.svg) > TABLE_SVG_BYTES) throw new Error("Table SVG exceeds its byte budget");
    result.graphic = graphic;
  }
}

// Text equal to the cell's displayText becomes the Host-text marker, so the text limit and the batch output
// budget count only text the source wrote itself.
export function validateCellView(value: unknown, displayText: string): TableCellOutput | undefined {
  if (value === undefined) return undefined;
  const result = ownRecord(value, ["text", ...CELL_STYLE_KEYS]);
  const usesHostText = result.text === displayText;
  if (!usesHostText && (typeof result.text !== "string" || result.text.length > TABLE_TEXT_LENGTH)) throw new Error("Invalid Table cell text");
  validateCellStyle(result);
  if (usesHostText) {
    delete result.text;
    result.hostText = true;
  }
  return result as TableCellOutput;
}

export function validateCellOutput(value: unknown): TableCellOutput | undefined {
  if (value === undefined) return undefined;
  const result = ownRecord(value, ["text", "hostText", ...CELL_STYLE_KEYS]);
  const hostText = result.hostText === true && result.text === undefined;
  const authoredText = result.hostText === undefined && typeof result.text === "string" && result.text.length <= TABLE_TEXT_LENGTH;
  if (!hostText && !authoredText) throw new Error("Invalid Table cell text");
  validateCellStyle(result);
  return result as TableCellOutput;
}

export function presentedCellView(output: TableCellOutput, hostText: string): CellView {
  if (!("hostText" in output)) return output;
  const { hostText: _marker, ...style } = output;
  return { ...style, text: hostText };
}

export function tableError(error: unknown): string {
  if (!(error instanceof Error)) return "Table presentation failed";
  const message = Object.getOwnPropertyDescriptor(error, "message");
  return message && "value" in message && typeof message.value === "string"
    ? message.value.slice(0, 4096)
    : "Table presentation failed";
}
