import { describe, expect, it, vi } from "vitest";
import { formatMetricNumber, metricNumberFormat } from "./metric-display";
import { columnFractionDigits, formatNumericCell } from "./numeric-display";

describe("metric display", () => {
  it("maps fixed styles to frozen standard Intl options", () => {
    expect(metricNumberFormat(null)).toBeNull();
    const options = metricNumberFormat({ style: "CURRENCY", decimals: 2, currency: "USD" })!;
    expect(options).toEqual({ style: "currency", currency: "USD", minimumFractionDigits: 2, maximumFractionDigits: 2, signDisplay: "negative" });
    expect(Object.isFrozen(options)).toBe(true);
  });

  it.each([
    ["9764678.030000000", { style: "CURRENCY", decimals: 2, currency: "USD" }, "$9,764,678.03"],
    ["9007199254740993.125", { style: "CURRENCY", decimals: 2, currency: "USD" }, "$9,007,199,254,740,993.13"],
    ["-9007199254740993.125", { style: "CURRENCY", decimals: 2, currency: "USD" }, "-$9,007,199,254,740,993.13"],
    ["0.4649721820", { style: "PERCENT", decimals: 1 }, "46.5%"],
    ["46.5", { style: "NUMBER", decimals: 0, unit: "percent" }, "47%"],
    ["320", { style: "NUMBER", decimals: 0, unit: "millisecond" }, "320 ms"],
    ["-0.000", { style: "NUMBER", decimals: 2 }, "0.00"],
    ["0.0004", { style: "PERCENT", decimals: 1 }, "0.04%"],
    ["-0.0004", { style: "PERCENT", decimals: 1 }, "-0.04%"],
    ["0.00000000000000000000001", { style: "PERCENT", decimals: 1 }, "0.000000000000000000001%"],
    ["-1e-23", { style: "PERCENT", decimals: 1 }, "-1e-21%"],
    ["0.000000000000000000001", { style: "CURRENCY", decimals: 2, currency: "USD" }, "$0.000000000000000000001"],
    ["-0.000000000000000000001", { style: "NUMBER", decimals: 0, unit: "percent" }, "-0.000000000000000000001%"],
    ["0.000000000000000000001", { style: "NUMBER", decimals: 0, unit: "day" }, "0.000000000000000000001 days"],
    ["-0.000000000000000000001", { style: "NUMBER", decimals: 0, unit: "day" }, "-0.000000000000000000001 days"],
    ["NaN", { style: "NUMBER", decimals: 2 }, "NaN"],
    ["Infinity", { style: "PERCENT", decimals: 2 }, "Infinity"],
  ] as const)("formats exact %s", (raw, display, expected) => {
    expect(formatMetricNumber(raw, metricNumberFormat(display)!, "en-US")).toBe(expected);
  });

  it("uses reader locale and handles zero expansion with non-Latin numerals", () => {
    expect(formatMetricNumber("1234.5", metricNumberFormat({ style: "CURRENCY", decimals: 2, currency: "EUR" })!, "de-DE")).toBe("1.234,50 €");
    const arabic = formatMetricNumber("0.0004", metricNumberFormat({ style: "PERCENT", decimals: 1 })!, "ar-EG");
    expect(arabic).toContain("٠٫٠٤");
  });

  it("reuses Intl number formats so zero expansion cost does not grow with the cell count", () => {
    const currency = metricNumberFormat({ style: "CURRENCY", decimals: 2, currency: "USD" })!;
    const NativeNumberFormat = Intl.NumberFormat;
    const construct = vi.spyOn(Intl, "NumberFormat").mockImplementation(function (locales, options) {
      return new NativeNumberFormat(locales, options);
    } as typeof Intl.NumberFormat);
    try {
      expect(formatMetricNumber("0.000000000000000000001", currency, "en-CA")).toBe("US$0.000000000000000000001");
      expect(formatNumericCell("1234.5", "en-CA", 2)).toBe("1,234.50");
      const firstCellConstructions = construct.mock.calls.length;
      expect(firstCellConstructions).toBeGreaterThan(0);
      for (let cell = 0; cell < 2048; cell++) {
        expect(formatMetricNumber("0.000000000000000000001", currency, "en-CA")).toBe("US$0.000000000000000000001");
        expect(formatNumericCell("1234.5", "en-CA", 2)).toBe("1,234.50");
      }
      expect(construct.mock.calls.length).toBe(firstCellConstructions);
    } finally { construct.mockRestore(); }
  });
});

it("uses exact minimal column digits for warehouse scales without rounding", () => {
  const values = ["10430427.950000000", "12.500000000", null];
  const digits = columnFractionDigits(values);
  expect(digits).toBe(2);
  expect(formatNumericCell(values[0]!, "en-US", digits)).toBe("10,430,427.95");
  expect(formatNumericCell("9007199254740993.123456789", "en-US", digits)).toBe("9,007,199,254,740,993.123456789");
  expect(formatNumericCell("1234567.00", "en-IN", digits)).toBe("12,34,567.00");
});
