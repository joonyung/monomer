import type { components } from "@/api/control/generated";
import { sharedNumberFormat } from "./numeric-display";

export type MetricDisplay = components["schemas"]["MetricDisplay"];
export type MetricNumberFormat = Readonly<{
  style: "decimal" | "currency" | "percent" | "unit";
  currency?: string;
  unit?: string;
  minimumFractionDigits: number;
  maximumFractionDigits: number;
  signDisplay: "negative";
}>;

const DECIMAL_TEXT = /^([+-]?)(\d+)(?:\.(\d*))?(?:[eE]([+-]?\d+))?$/;

export function metricNumberFormat(display: MetricDisplay | null | undefined): MetricNumberFormat | null {
  if (!display) return null;
  const style = display.style === "PERCENT" ? "percent" : display.style === "CURRENCY" ? "currency" : display.unit ? "unit" : "decimal";
  return Object.freeze({
    style,
    ...(display.style === "CURRENCY" ? { currency: display.currency } : {}),
    ...(display.style === "NUMBER" && display.unit ? { unit: display.unit } : {}),
    minimumFractionDigits: display.decimals,
    maximumFractionDigits: display.decimals,
    signDisplay: "negative",
  });
}

function exactParts(formatter: Intl.NumberFormat, value: string): Intl.NumberFormatPart[] {
  // ECMA-402 accepts exact decimal strings; TypeScript still restricts this signature to number/bigint.
  return (formatter.formatToParts as (value: string) => Intl.NumberFormatPart[])(value);
}

function partsAreZero(parts: Intl.NumberFormatPart[], locale: string | undefined): boolean {
  const zero = sharedNumberFormat(locale, {}).formatToParts(0).find((part) => part.type === "integer")?.value ?? "0";
  return parts.filter((part) => part.type === "integer" || part.type === "fraction").every((part) => [...part.value].every((digit) => digit === zero));
}

function percentDecimal(value: string): string {
  const match = DECIMAL_TEXT.exec(value)!;
  if (match[4] !== undefined) return `${match[2]}${match[3] ? `.${match[3]}` : ""}e${BigInt(match[4]) + 2n}`;
  const fraction = (match[3] ?? "").padEnd(2, "0");
  const integer = `${match[2]}${fraction.slice(0, 2)}`.replace(/^0+(?=\d)/, "");
  const remainder = fraction.slice(2);
  return `${integer}${remainder ? `.${remainder}` : ""}`;
}

function rawWithAffixes(value: string, options: MetricNumberFormat, locale: string | undefined): string {
  const match = DECIMAL_TEXT.exec(value)!;
  const magnitude = options.style === "percent" ? percentDecimal(value) : value.replace(/^[+-]/, "");
  const parts = sharedNumberFormat(locale, { ...options, signDisplay: "auto" }).formatToParts(match[1] === "-" ? -0 : 0);
  let inserted = false;
  return parts.map((part) => {
    if (part.type === "integer") {
      if (inserted) return "";
      inserted = true;
      return magnitude;
    }
    return ["group", "decimal", "fraction"].includes(part.type) ? "" : part.value;
  }).join("");
}

export function formatMetricNumber(value: string, options: MetricNumberFormat, locale: string | undefined): string {
  const match = DECIMAL_TEXT.exec(value);
  if (!match) return value;
  const nonzero = /[1-9]/.test(`${match[2]}${match[3] ?? ""}`);
  const minimumFractionDigits = options.minimumFractionDigits ?? 0;
  const initialMaximum = options.maximumFractionDigits ?? minimumFractionDigits;
  for (let maximumFractionDigits = initialMaximum; maximumFractionDigits <= 20; maximumFractionDigits++) {
    const parts = exactParts(sharedNumberFormat(locale, { ...options, maximumFractionDigits }), value);
    if (!nonzero || !partsAreZero(parts, locale)) return parts.map((part) => part.value).join("");
  }
  return rawWithAffixes(value, options, locale);
}
