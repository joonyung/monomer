import type {
  DashboardJsonValue,
  DashboardParameterDefinition,
  DashboardValueType,
} from "@/api/dashboard";
import { controlOptions, dashboardValuesEqual, isDashboardControlValueValid, optionLabel, valueToEditorText } from "./dashboard-value";

function itemType(type: DashboardValueType): DashboardValueType {
  if (type.endsWith("_SET")) return type.slice(0, -4) as DashboardValueType;
  if (type.endsWith("_RANGE")) return type.slice(0, -6) as DashboardValueType;
  return type;
}

function formatDate(value: string): string {
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value);
  if (match === null) return value;
  const date = new Date(Date.UTC(Number(match[1]), Number(match[2]) - 1, Number(match[3])));
  return new Intl.DateTimeFormat("en-US", { month: "short", day: "numeric", year: "numeric", timeZone: "UTC" }).format(date);
}

function formatDateRange(start: string, end: string): string {
  const startMatch = /^(\d{4})-(\d{2})-(\d{2})$/.exec(start);
  const endMatch = /^(\d{4})-(\d{2})-(\d{2})$/.exec(end);
  if (startMatch === null || endMatch === null) return `${start} – ${end}`;
  const startDate = new Date(Date.UTC(Number(startMatch[1]), Number(startMatch[2]) - 1, Number(startMatch[3])));
  const endDate = new Date(Date.UTC(Number(endMatch[1]), Number(endMatch[2]) - 1, Number(endMatch[3])));
  if (startMatch[1] === endMatch[1]) {
    const first = new Intl.DateTimeFormat("en-US", { month: "short", day: "numeric", timeZone: "UTC" }).format(startDate);
    const last = new Intl.DateTimeFormat("en-US", { month: "short", day: "numeric", year: "numeric", timeZone: "UTC" }).format(endDate);
    return `${first} – ${last}`;
  }
  return `${formatDate(start)} – ${formatDate(end)}`;
}

function formatTimestamp(value: string): string {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return new Intl.DateTimeFormat("en-US", {
    month: "short",
    day: "numeric",
    year: "numeric",
    hour: "numeric",
    minute: "2-digit",
    timeZone: "UTC",
    timeZoneName: "short",
  }).format(date);
}

function formatScalar(type: DashboardValueType, value: unknown, definition: DashboardParameterDefinition): string {
  const options = controlOptions(definition.control);
  const matchingOption = options?.find((option) => dashboardValuesEqual(type, option.value, value));
  if (matchingOption) return optionLabel(type, matchingOption);
  if (typeof value === "boolean") return value ? "Yes" : "No";
  if (typeof value !== "string") return String(value);
  if (type === "DATE") return formatDate(value);
  if (type === "TIMESTAMP") return formatTimestamp(value);
  return value;
}

function formatRange(type: DashboardValueType, value: DashboardJsonValue): string | null {
  if (typeof value !== "object" || value === null || Array.isArray(value)) return null;
  const start = value.start;
  const end = value.end;
  if (typeof start !== "string" || typeof end !== "string") return null;
  if (type === "DATE_RANGE") return formatDateRange(start, end);
  if (type === "TIMESTAMP_RANGE") return `${formatTimestamp(start)} – ${formatTimestamp(end)}`;
  return null;
}

export function dashboardParameterLabel(name: string): string {
  const words = name
    .replace(/([a-z0-9])([A-Z])/g, "$1 $2")
    .replace(/[_-]+/g, " ")
    .trim();
  return words.length === 0 ? name : words[0].toUpperCase() + words.slice(1);
}

export function formatUnknownDashboardParameterValue(value: DashboardJsonValue): string {
  if (value === null) return "None";
  if (Array.isArray(value)) return value.map((candidate) => String(candidate)).join(", ");
  if (typeof value === "object") {
    const start = value.start;
    const end = value.end;
    if (typeof start === "string" && typeof end === "string") {
      const dateRange = /^\d{4}-\d{2}-\d{2}$/.test(start) && /^\d{4}-\d{2}-\d{2}$/.test(end);
      return dateRange ? formatDateRange(start, end) : `${start} – ${end}`;
    }
    return "Structured value";
  }
  if (typeof value === "boolean") return value ? "Yes" : "No";
  return String(value);
}

export function formatDashboardParameterValue(
  definition: DashboardParameterDefinition,
  value: DashboardJsonValue,
): string {
  if (value === null) {
    const choiceControl = !definition.type.endsWith("_RANGE")
      && (definition.type === "BOOLEAN" || controlOptions(definition.control) !== undefined);
    return choiceControl ? definition.control?.emptyLabel ?? "Any" : "None";
  }
  if (!isDashboardControlValueValid(definition.type, value, definition.control)) return valueToEditorText(definition.type, value);
  const range = formatRange(definition.type, value);
  if (range !== null) return range;
  if (Array.isArray(value)) {
    const scalarType = itemType(definition.type);
    return value.map((candidate) => formatScalar(scalarType, candidate, definition)).join(", ");
  }
  return formatScalar(definition.type, value, definition);
}
