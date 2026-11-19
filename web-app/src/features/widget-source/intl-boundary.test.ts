import "ses";
import { beforeAll, describe, expect, it } from "vitest";
import type { Callable } from "@/features/chart-widget/runtime/worker/canonical-boundary";
import { evaluateChartSource } from "@/features/chart-widget/runtime/worker/module-source";
import type { TableFrame } from "@/features/table-widget/contract/abi";
import { evaluateTableSource } from "@/features/table-widget/runtime/worker/module-source";
import { createWidgetSourceIntl } from "./intl-boundary";

beforeAll(() => lockdown({ errorTaming: "unsafe-debug" }));

const sourceEvaluators: Record<string, (expression: string) => unknown> = {
  Chart(expression) {
    const module = evaluateChartSource(`export default defineChart({ render() { return (${expression}); } });`);
    return module.render({} as Parameters<typeof module.render>[0]);
  },
  Table(expression) {
    return evaluateTableSource(`export default defineTable({ render() { return (${expression}); } });`).render({} as TableFrame);
  },
};

describe.each(Object.entries(sourceEvaluators))("%s source standard APIs", (_sourceKind, evaluate) => {
  it("preserves SES collections, precise integers, promises and explicit Date/Math", () => {
    expect(evaluate(`[
      new Map([["a", 3]]).get("a"), new Set([1, 1]).size,
      String(9007199254740993n + 1n), new Uint8Array([257])[0],
      typeof Promise.resolve, new Date("2026-09-07T00:00:00Z").getUTCMonth(),
      Date.UTC(2026, 8, 7), Math.max(3, 7), Math.sqrt(16)
    ]`)).toEqual([3, 1, "9007199254740994", 1, "function", 8, 1788739200000, 7, 4]);
  });

  it("keeps ambient clock, randomness and external authority unavailable", () => {
    expect(evaluate(`[
      typeof fetch, typeof document, typeof setTimeout, typeof crypto,
      typeof SharedArrayBuffer, typeof Atomics, typeof WeakRef, typeof FinalizationRegistry
    ]`)).toEqual(["undefined", "undefined", "undefined", "undefined", "undefined", "undefined", "undefined", "undefined"]);
    for (const expression of ["Date.now()", "new Date()", "Date()", "Date(0)", "new Date(0).constructor.now()", "Math.random()"]) {
      expect(() => evaluate(expression)).toThrow();
    }
  });

  it("formats locales, exact decimal strings, BigInt, percent and explicit zoned dates", () => {
    expect(evaluate(`[
      new Intl.NumberFormat("ko-KR").format(1234.5),
      new Intl.NumberFormat("de-DE").format(1234.5),
      new Intl.NumberFormat("en-US", {style:"currency", currency:"USD"}).format("9007199254740993.12"),
      new Intl.NumberFormat("en-US").format(9007199254740993n),
      new Intl.NumberFormat("en-US", {style:"percent"}).format(0.125),
      new Intl.DateTimeFormat("en-US", {timeZone:"Asia/Seoul", hour:"2-digit", hourCycle:"h23"}).format(new Date("2026-09-07T00:00:00Z"))
    ]`)).toEqual(["1,234.5", "1.234,5", "$9,007,199,254,740,993.12", "9,007,199,254,740,993", "13%", "09"]);
  });

  it("provides all available named Intl constructors and standard result methods", () => {
    expect(evaluate(`[
      new Intl.Collator("en-US").compare("a", "b"),
      new Intl.PluralRules("en-US").select(1),
      new Intl.ListFormat("en-US").format(["A", "B"]),
      new Intl.RelativeTimeFormat("en-US").format(-1, "day"),
      new Intl.DisplayNames("en-US", {type:"region"}).of("KR"),
      Array.from(new Intl.Segmenter("en-US", {granularity:"word"}).segment("hello world"), part => part.segment),
      new Intl.Segmenter("en-US").segment("abc").containing(1).segment,
      new Intl.Locale("en").maximize().minimize().toString(),
      new Intl.NumberFormat(new Intl.Locale("de-DE")).format(1234),
      Intl.NumberFormat.supportedLocalesOf(["ko-KR"])[0],
      new Intl.NumberFormat("en-US").formatToParts(2)[0].value,
      new Intl.DateTimeFormat("en-US", {timeZone:"UTC"}).formatRange(0, 1000)
    ]`)).toEqual([-1, "one", "A and B", "1 day ago", "South Korea", ["hello", " ", "world"], "b", "en", "1.234", "ko-KR", "2", "1/1/1970"]);
  });

  it.each(["NumberFormat", "DateTimeFormat", "Collator", "PluralRules", "ListFormat", "RelativeTimeFormat", "Segmenter", "DisplayNames", "Locale"])(
    "requires an explicit nonempty locale for %s", (name) => {
      if (typeof (Intl as unknown as Record<string, unknown>)[name] !== "function") return;
      for (const locale of ["undefined", '""', "[]", "[undefined]", "[,]", "{}"] ) {
        expect(() => evaluate(`new Intl.${name}(${locale})`)).toThrow();
      }
    },
  );

  it.each(["undefined", "{}", '{timeZone:undefined}', '{timeZone:""}'])("requires explicit timeZone: %s", options => {
    expect(() => evaluate(`new Intl.DateTimeFormat("en-US", ${options})`)).toThrow(/timeZone/);
  });

  it("captures timeZone once so an accessor cannot revert to host defaults", () => {
    expect(evaluate(`(() => {
      let reads = 0;
      const formatter = new Intl.DateTimeFormat("en-US", { get timeZone() { reads++; return reads === 1 ? "UTC" : undefined; } });
      return [reads, formatter.resolvedOptions().timeZone];
    })()`)).toEqual([1, "UTC"]);
  });

  it.each(["format()", "format(undefined)", "formatToParts()", "formatToParts(undefined)", "formatRange()", "formatRangeToParts(undefined, 0)"])("blocks implicit current dates through %s", call => {
    expect(() => evaluate(`new Intl.DateTimeFormat("en-US", {timeZone:"UTC"}).${call}`)).toThrow();
  });

  it("blocks extracted getter, prototype and reconstructed constructor clock bypasses", () => {
    for (const expression of [
      `Object.getOwnPropertyDescriptor(Intl.DateTimeFormat.prototype, "format").get.call(new Intl.DateTimeFormat("en-US", {timeZone:"UTC"}))()`,
      `Intl.DateTimeFormat.prototype.formatToParts.call(new Intl.DateTimeFormat("en-US", {timeZone:"UTC"}))`,
      `new (new Intl.DateTimeFormat("en-US", {timeZone:"UTC"})).constructor("en-US").format()`,
      `Intl.DateTimeFormat.prototype.formatToParts.call({})`,
    ]) expect(() => evaluate(expression)).toThrow();
  });

  it("hardens facade authority without exposing native Intl prototypes or constructors", () => {
    expect(evaluate(`(() => {
      const formatter = new Intl.NumberFormat("en-US");
      const locale = new Intl.Locale("en").maximize();
      const segments = new Intl.Segmenter("en").segment("abc");
      const iterator = segments[Symbol.iterator]();
      return [
        Object.isFrozen(Intl), Object.isFrozen(Intl.NumberFormat), Object.isFrozen(Intl.NumberFormat.prototype),
        Object.isFrozen(formatter), Object.getPrototypeOf(Intl.NumberFormat.prototype) === null,
        formatter instanceof Intl.NumberFormat, formatter.constructor === Intl.NumberFormat,
        locale.constructor === Intl.Locale, Object.isFrozen(locale),
        Object.getPrototypeOf(segments) === null, Object.getPrototypeOf(iterator) === null,
        Object.getPrototypeOf(iterator.next()) === null,
        Object.getPrototypeOf(formatter.resolvedOptions()) === null,
        Object.getPrototypeOf(formatter.formatToParts(1)[0]) === null,
        formatter.format === formatter.format
      ];
    })()`)).toEqual(Array(15).fill(true));
    for (const expression of [
      `Intl.NumberFormat.prototype.formatToParts = () => []`,
      `Object.setPrototypeOf(Intl.NumberFormat.prototype, {})`,
      `Intl.NumberFormat.constructor("return globalThis")()`,
      `new Intl.NumberFormat("en").format.constructor("return globalThis")()`,
      `new Intl.Segmenter("en").segment("x")[Symbol.iterator]().next.constructor("return globalThis")()`,
    ]) expect(() => evaluate(expression)).toThrow();
  });

  it("keeps native Intl prototypes untouched and copies returned data", () => {
    const nativePrototype = Intl.NumberFormat.prototype;
    const nativeDescriptor = Object.getOwnPropertyDescriptor(nativePrototype, "format");
    const wasFrozen = Object.isFrozen(nativePrototype);
    expect(evaluate(`(() => {
      const formatter = new Intl.NumberFormat("en-US");
      const options = formatter.resolvedOptions();
      options.locale = "changed";
      const parts = formatter.formatToParts(1234);
      parts[0].value = "changed";
      return [formatter.resolvedOptions().locale, formatter.formatToParts(1234)[0].value];
    })()`)).toEqual(["en-US", "1"]);
    expect(Object.isFrozen(nativePrototype)).toBe(wasFrozen);
    expect(Object.getOwnPropertyDescriptor(nativePrototype, "format")).toEqual(nativeDescriptor);
  });

  it("does not leak constructor authority through native Intl errors", () => {
    expect(() => evaluate(`(() => {
      try { new Intl.NumberFormat("invalid_locale"); }
      catch (error) { return error.constructor.constructor("return globalThis")(); }
    })()`)).toThrow();
  });
});

describe("widget source Intl facade", () => {
  it("marks every callable facade as foreign to option callbacks", () => {
    const foreignFunctions = new WeakSet<Callable>();
    const intl = createWidgetSourceIntl(foreignFunctions) as unknown as typeof Intl;
    const formatter = new intl.NumberFormat("en-US");
    const iterator = new intl.Segmenter("en-US").segment("hi")[Symbol.iterator]();
    for (const fn of [intl.NumberFormat, intl.NumberFormat.supportedLocalesOf, formatter.format, formatter.formatToParts, iterator.next]) {
      expect(foreignFunctions.has(fn as Callable)).toBe(true);
    }
  });
});
