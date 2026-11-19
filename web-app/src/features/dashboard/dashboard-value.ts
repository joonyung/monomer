import type { DashboardJsonValue, DashboardValueControl, DashboardValueType } from "@/api/dashboard";
import { offsetSuffix, timeZoneOffsetMinutes, wallClockOffsetMinutes } from "@/lib/time-zone";

type DashboardValueControlOption = NonNullable<DashboardValueControl["options"]>[number];

const CANONICAL_DECIMAL = /^(?:0|-?(?:(?:[1-9][0-9]{0,19})(?:\.[0-9]{0,17}[1-9])?|0\.[0-9]{0,17}[1-9]))$/;

export function isCanonicalDecimal(value: string): boolean {
  return CANONICAL_DECIMAL.test(value);
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function isValidCalendarDate(year: number, month: number, day: number): boolean {
  if (month < 1 || month > 12 || day < 1) return false;
  const daysInMonth = [31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31][month - 1]
    + (month === 2 && (year % 4 === 0 && (year % 100 !== 0 || year % 400 === 0)) ? 1 : 0);
  return day <= daysInMonth;
}

function isDate(value: string): boolean {
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value);
  return match !== null && isValidCalendarDate(Number(match[1]), Number(match[2]), Number(match[3]));
}

interface ParsedTimestamp {
  epochSecond: number;
  fraction: string;
}

function daysFromCivil(year: number, month: number, day: number): number {
  const adjustedYear = year - (month <= 2 ? 1 : 0);
  const era = Math.floor(adjustedYear / 400);
  const yearOfEra = adjustedYear - era * 400;
  const monthOfYear = month + (month > 2 ? -3 : 9);
  const dayOfYear = Math.floor((153 * monthOfYear + 2) / 5) + day - 1;
  const dayOfEra = yearOfEra * 365 + Math.floor(yearOfEra / 4) - Math.floor(yearOfEra / 100) + dayOfYear;
  return era * 146097 + dayOfEra - 719468;
}

function parseTimestamp(value: string): ParsedTimestamp | undefined {
  const match = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.(\d+))?(Z|([+-])(\d{2}):(\d{2}))$/.exec(value);
  if (match === null) return undefined;
  const year = Number(match[1]);
  const month = Number(match[2]);
  const day = Number(match[3]);
  const hour = Number(match[4]);
  const minute = Number(match[5]);
  const second = Number(match[6]);
  const hasNumericOffset = match[8] !== "Z";
  const offsetHour = hasNumericOffset ? Number(match[10]) : 0;
  const offsetMinute = hasNumericOffset ? Number(match[11]) : 0;
  if (!isValidCalendarDate(year, month, day) || hour > 23 || minute > 59 || second > 59 || offsetHour > 23 || offsetMinute > 59) return undefined;
  const offsetSign = match[9] === "-" ? -1 : 1;
  const offsetSecond = offsetSign * (offsetHour * 3600 + offsetMinute * 60);
  const epochSecond = daysFromCivil(year, month, day) * 86400 + hour * 3600 + minute * 60 + second - offsetSecond;
  return { epochSecond, fraction: (match[7] ?? "").replace(/0+$/, "") };
}

function isTimestamp(value: string): boolean {
  return parseTimestamp(value) !== undefined;
}

function isScalarType(type: DashboardValueType): boolean {
  return type === "TEXT" || type === "NUMBER" || type === "BOOLEAN" || type === "DATE" || type === "TIMESTAMP";
}

function setItemType(type: DashboardValueType): DashboardValueType | undefined {
  if (!type.endsWith("_SET")) return undefined;
  return type.slice(0, -4) as DashboardValueType;
}

function rangeItemType(type: DashboardValueType): DashboardValueType | undefined {
  if (!type.endsWith("_RANGE")) return undefined;
  return type.slice(0, -6) as DashboardValueType;
}

function decimalCompare(left: string, right: string): number {
  const [leftInteger, leftFraction = ""] = left.replace(/^-/, "").split(".");
  const [rightInteger, rightFraction = ""] = right.replace(/^-/, "").split(".");
  const leftSign = left.startsWith("-") ? -1 : 1;
  const rightSign = right.startsWith("-") ? -1 : 1;
  if (leftSign !== rightSign) return leftSign - rightSign;
  const integerComparison = leftInteger.padStart(rightInteger.length, "0").localeCompare(rightInteger.padStart(leftInteger.length, "0"));
  if (integerComparison !== 0) return integerComparison * leftSign;
  return leftFraction.padEnd(rightFraction.length, "0").localeCompare(rightFraction.padEnd(leftFraction.length, "0")) * leftSign;
}

function scalarCompare(type: DashboardValueType, left: string, right: string): number {
  if (type === "NUMBER") return decimalCompare(left, right);
  if (type === "DATE") return left.localeCompare(right);
  if (type === "TIMESTAMP") return compareTimestamps(left, right);
  return left.localeCompare(right);
}

function compareTimestamps(left: string, right: string): number {
  const leftParsed = parseTimestamp(left);
  const rightParsed = parseTimestamp(right);
  if (leftParsed === undefined || rightParsed === undefined) return left.localeCompare(right);
  if (leftParsed.epochSecond !== rightParsed.epochSecond) return leftParsed.epochSecond - rightParsed.epochSecond;
  const fractionLength = Math.max(leftParsed.fraction.length, rightParsed.fraction.length);
  return leftParsed.fraction.padEnd(fractionLength, "0").localeCompare(rightParsed.fraction.padEnd(fractionLength, "0"));
}

function padTimePart(value: number): string {
  return String(value).padStart(2, "0");
}

/**
 * datetime-local has no time zone. The Dashboard editors show a durable RFC3339 instant as the wall-clock
 * time in `timeZone` (the Workspace Reporting time zone when the reader knows it, otherwise UTC), never the
 * browser's own zone. Fractional seconds keep their exact digits. Empty when the value isn't an instant or
 * the browser lacks the zone.
 */
export function timestampToDateTimeLocal(value: string | undefined, timeZone: string): string {
  const parsed = value === undefined ? undefined : parseTimestamp(value);
  if (parsed === undefined) return "";
  const offset = timeZoneOffsetMinutes(timeZone, new Date(parsed.epochSecond * 1000));
  if (offset === null) return "";
  const date = new Date((parsed.epochSecond + offset * 60) * 1000);
  const fraction = parsed.fraction.length === 0 ? "" : `.${parsed.fraction}`;
  return `${String(date.getUTCFullYear()).padStart(4, "0")}-${padTimePart(date.getUTCMonth() + 1)}-${padTimePart(date.getUTCDate())}T${padTimePart(date.getUTCHours())}:${padTimePart(date.getUTCMinutes())}:${padTimePart(date.getUTCSeconds())}${fraction}`;
}

/** The instant a datetime-local value names as `timeZone`'s wall clock, written with that zone's offset. */
export function dateTimeLocalToTimestamp(value: string, timeZone: string): string | undefined {
  if (value.length === 0) return undefined;
  const match = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})(?::(\d{2})(\.\d+)?)?$/.exec(value);
  if (match === null) return undefined;
  const [, year, month, day, hour, minute, second = "00", fraction = ""] = match;
  if (!isValidCalendarDate(Number(year), Number(month), Number(day))) return undefined;
  const offset = wallClockOffsetMinutes(Date.UTC(Number(year), Number(month) - 1, Number(day), Number(hour), Number(minute), Number(second)), timeZone);
  if (offset === null) return undefined;
  const timestamp = `${year}-${month}-${day}T${hour}:${minute}:${second}${fraction}${offsetSuffix(offset)}`;
  return isTimestamp(timestamp) ? timestamp : undefined;
}

/** Checks only the stored value shape. Nullability is owned by the declaration. */
export function isDashboardValueShapeValid(type: DashboardValueType, value: unknown): boolean {
  if (value === null) return true;
  if (type === "TEXT" && typeof value === "string") return value.length > 0;
  if (type === "NUMBER" && typeof value === "string") return isCanonicalDecimal(value);
  if (type === "BOOLEAN" && typeof value === "boolean") return true;
  if (type === "DATE" && typeof value === "string") return isDate(value);
  if (type === "TIMESTAMP" && typeof value === "string") return isTimestamp(value);

  const itemType = setItemType(type);
  if (itemType && isScalarType(itemType)) {
    return Array.isArray(value)
      && value.length > 0
      && new Set(value.map((item) => dashboardValueKey(itemType, item))).size === value.length
      && value.every((item) => item !== null && isDashboardValueShapeValid(itemType, item));
  }

  const rangeType = rangeItemType(type);
  if (rangeType && isScalarType(rangeType)) {
    if (!isRecord(value) || Object.keys(value).length !== 2 || !Object.prototype.hasOwnProperty.call(value, "start") || !Object.prototype.hasOwnProperty.call(value, "end")) return false;
    if (!isDashboardValueShapeValid(rangeType, value.start) || !isDashboardValueShapeValid(rangeType, value.end)) return false;
    return typeof value.start === "string"
      && typeof value.end === "string"
      && scalarCompare(rangeType, value.start, value.end) <= 0;
  }
  return false;
}

function optionList(control: DashboardValueControl | null | undefined): DashboardValueControlOption[] | undefined {
  return control && Array.isArray(control.options) && control.options.length > 0 ? control.options : undefined;
}

function dashboardValueKey(type: DashboardValueType, value: unknown): string {
  if (type === "NUMBER" && typeof value === "string") {
    const sign = value.startsWith("-") ? "-" : "";
    const unsigned = value.replace(/^-/, "");
    const [integer, fraction = ""] = unsigned.split(".");
    return `${sign}${integer.replace(/^0+(?=\d)/, "")}.${fraction.replace(/0+$/, "") || "0"}`;
  }
  if (type === "TIMESTAMP" && typeof value === "string") {
    const parsed = parseTimestamp(value);
    return parsed === undefined ? value : `${parsed.epochSecond}:${parsed.fraction}`;
  }
  return JSON.stringify(value);
}

export function dashboardValuesEqual(type: DashboardValueType, left: unknown, right: unknown): boolean {
  if (left === null || right === null) return left === right;
  const itemType = setItemType(type);
  if (itemType && Array.isArray(left) && Array.isArray(right)) {
    return left.length === right.length && left.every((item, index) => dashboardValuesEqual(itemType, item, right[index]));
  }
  const rangeType = rangeItemType(type);
  if (rangeType && isRecord(left) && isRecord(right)) {
    return dashboardValuesEqual(rangeType, left.start, right.start) && dashboardValuesEqual(rangeType, left.end, right.end);
  }
  return dashboardValueKey(type, left) === dashboardValueKey(type, right);
}

function valueIsWithinBounds(type: DashboardValueType, value: unknown, control: DashboardValueControl): boolean {
  if (value === null) return true;
  const itemType = setItemType(type);
  const rangeType = rangeItemType(type);
  const boundedType = itemType ?? rangeType ?? type;
  if (boundedType !== "NUMBER" && boundedType !== "DATE" && boundedType !== "TIMESTAMP") return true;
  const min = control.min;
  const max = control.max;
  const check = (candidate: unknown): boolean => {
    if (typeof candidate !== "string") return false;
    if (min !== undefined && scalarCompare(boundedType, candidate, min) < 0) return false;
    if (max !== undefined && scalarCompare(boundedType, candidate, max) > 0) return false;
    return true;
  };
  if (itemType && Array.isArray(value)) return value.every(check);
  if (rangeType && isRecord(value)) return check(value.start) && check(value.end);
  return check(value);
}

/** Checks user input against a Parameter or INPUT control, not stored values. */
export function isDashboardControlValueValid(
  type: DashboardValueType,
  value: unknown,
  control?: DashboardValueControl | null,
): boolean {
  if (!isDashboardValueShapeValid(type, value)) return false;
  if (value === null || control === undefined || control === null) return true;
  const options = optionList(control);
  if (options) {
    const itemType = setItemType(type);
    const allowed = (candidate: unknown) => options.some((option) => dashboardValuesEqual(itemType ?? type, option.value, candidate));
    if (itemType && Array.isArray(value) && !value.every(allowed)) return false;
    if (!itemType && !rangeItemType(type) && !allowed(value)) return false;
  }
  return valueIsWithinBounds(type, value, control);
}

export function optionLabel(type: DashboardValueType, option: DashboardValueControlOption): string {
  if (option.label !== undefined && option.label !== null) return option.label;
  return valueToEditorText(type, option.value);
}

export function controlOptions(control?: DashboardValueControl | null): DashboardValueControlOption[] | undefined {
  return optionList(control);
}

export function parseEditorValue(type: DashboardValueType, raw: string): DashboardJsonValue | undefined {
  if (type === "TEXT" || type === "DATE" || type === "TIMESTAMP") return raw;
  if (type === "NUMBER") return isCanonicalDecimal(raw) ? raw : undefined;
  if (type === "BOOLEAN") {
    if (raw === "true") return true;
    if (raw === "false") return false;
    return undefined;
  }
  const itemType = setItemType(type);
  if (itemType) {
    const parts = raw.split(",").map((part) => part.trim()).filter(Boolean);
    if (parts.length === 0) return undefined;
    const values = parts.map((part) => parseEditorValue(itemType, part));
    if (values.some((value) => value === undefined)) return undefined;
    const typedValues = values as DashboardJsonValue[];
    return new Set(typedValues.map((value) => JSON.stringify(value))).size === typedValues.length ? typedValues : undefined;
  }
  return undefined;
}

export function valueToEditorText(type: DashboardValueType, value: unknown): string {
  if (value === null || value === undefined) return "";
  if (setItemType(type) && Array.isArray(value)) return value.map(String).join(", ");
  if (rangeItemType(type) && isRecord(value)) return `${String(value.start ?? "")} — ${String(value.end ?? "")}`;
  return typeof value === "boolean" ? String(value) : String(value);
}

export function dashboardValueToJson(value: unknown): DashboardJsonValue {
  if (value === null || typeof value === "string" || typeof value === "boolean" || typeof value === "number") return value;
  if (Array.isArray(value)) return value.map(dashboardValueToJson);
  if (typeof value === "object") {
    return Object.fromEntries(Object.entries(value).map(([key, nested]) => [key, dashboardValueToJson(nested)]));
  }
  throw new Error("Dashboard value is not JSON serializable.");
}
