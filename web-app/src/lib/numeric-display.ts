type NumericCell = string | boolean | null | undefined;

const DECIMAL_TEXT = /^([+-]?)(\d+)(?:\.(\d+))?$/;
const numberFormats = new Map<string, Intl.NumberFormat>();

// Constructing Intl.NumberFormat costs far more than formatting, and table cells repeat a few option sets.
export function sharedNumberFormat(locale: string | undefined, options: Intl.NumberFormatOptions): Intl.NumberFormat {
  const key = JSON.stringify([locale ?? null, options]);
  let format = numberFormats.get(key);
  if (!format) {
    format = new Intl.NumberFormat(locale, options);
    numberFormats.set(key, format);
  }
  return format;
}

export function columnFractionDigits(values: readonly NumericCell[]): number {
  let significant = 0;
  let returned = 0;
  for (const value of values) {
    if (typeof value !== "string") continue;
    const fraction = DECIMAL_TEXT.exec(value)?.[3] ?? "";
    returned = Math.max(returned, fraction.length);
    significant = Math.max(significant, fraction.replace(/0+$/, "").length);
  }
  return Math.max(significant, Math.min(returned, 2));
}

export function formatNumericCell(value: string, locale: string | undefined, fractionDigits: number): string {
  const match = DECIMAL_TEXT.exec(value);
  if (!match) return value;
  const [, sign, integer, fraction = ""] = match;
  const format = sharedNumberFormat(locale, { useGrouping: true });
  const decimalSeparator = format.formatToParts(1.5).find((part) => part.type === "decimal")?.value ?? ".";
  const grouped = format.format(BigInt(integer));
  const significant = fraction.replace(/0+$/, "");
  const digits = significant.length > fractionDigits ? significant : significant.padEnd(fractionDigits, "0");
  return `${sign === "-" ? "-" : ""}${grouped}${digits ? `${decimalSeparator}${digits}` : ""}`;
}
