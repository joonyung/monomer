type Callable = (...args: never[]) => unknown;

const CONSTRUCTORS = {
  NumberFormat: { methods: ["resolvedOptions", "formatToParts", "formatRange", "formatRangeToParts"], getters: ["format"] },
  DateTimeFormat: { methods: ["resolvedOptions", "formatToParts", "formatRange", "formatRangeToParts"], getters: ["format"] },
  Collator: { methods: ["resolvedOptions"], getters: ["compare"] },
  PluralRules: { methods: ["resolvedOptions", "select", "selectRange"], getters: [] },
  ListFormat: { methods: ["resolvedOptions", "format", "formatToParts"], getters: [] },
  RelativeTimeFormat: { methods: ["resolvedOptions", "format", "formatToParts"], getters: [] },
  Segmenter: { methods: ["resolvedOptions", "segment"], getters: [] },
  DisplayNames: { methods: ["resolvedOptions", "of"], getters: [] },
  Locale: {
    methods: ["toString", "maximize", "minimize", "getCalendars", "getCollations", "getHourCycles", "getNumberingSystems", "getTextInfo", "getTimeZones", "getWeekInfo"],
    getters: ["baseName", "calendar", "caseFirst", "collation", "hourCycle", "language", "numberingSystem", "numeric", "region", "script", "calendars", "collations", "hourCycles", "numberingSystems", "textInfo", "timeZones", "weekInfo"],
  },
} as const;

type NativeInstance = Record<PropertyKey, unknown>;
type NativeConstructor = (new (...args: unknown[]) => NativeInstance) & {
  prototype: NativeInstance;
  supportedLocalesOf?: (...args: unknown[]) => unknown;
};

function copyResult(value: unknown): unknown {
  if (value === null || typeof value !== "object") return value;
  if (Array.isArray(value)) return value.map(copyResult);
  const result: Record<string, unknown> = Object.create(null);
  for (const [key, child] of Object.entries(value)) result[key] = copyResult(child);
  return result;
}

export function createWidgetSourceIntl(foreignFunctions: WeakSet<Callable>): Readonly<Record<string, unknown>> {
  const intl: Record<string, unknown> = Object.create(null);
  const localeInstances = new WeakMap<object, NativeInstance>();
  function expose<T extends Callable>(fn: T): T {
    foreignFunctions.add(fn as unknown as Callable);
    return harden(fn);
  }
  function localeTag(value: unknown): string {
    if (typeof value === "object" && value !== null && localeInstances.has(value)) {
      return String(localeInstances.get(value));
    }
    if (typeof value !== "string" || value.trim().length === 0) {
      throw new TypeError("Intl requires an explicit nonempty locale");
    }
    return value;
  }
  function locales(value: unknown): string | string[] {
    if (!Array.isArray(value)) return localeTag(value);
    if (value.length === 0) throw new TypeError("Intl requires an explicit nonempty locale");
    return Array.from(value, localeTag);
  }
  function segmentsFacade(segments: NativeInstance): object {
    const result = Object.create(null);
    result.containing = expose((index: unknown) => copyResult(Reflect.apply(segments.containing as Callable, segments, [index])));
    result[Symbol.iterator] = expose(() => {
      const iterator = Reflect.apply(segments[Symbol.iterator] as Callable, segments, []) as NativeInstance;
      const facade = Object.create(null);
      facade.next = expose(() => copyResult(Reflect.apply(iterator.next as Callable, iterator, [])));
      facade[Symbol.iterator] = expose(() => facade);
      return harden(facade);
    });
    return harden(result);
  }

  for (const [name, members] of Object.entries(CONSTRUCTORS)) {
    const Native = (Intl as unknown as Record<string, NativeConstructor>)[name];
    if (typeof Native !== "function") continue;
    const instances = new WeakMap<object, NativeInstance>();
    const prototype = Object.create(null);
    function wrap(instance: NativeInstance): object {
      const facade = Object.create(prototype);
      instances.set(facade, instance);
      if (name === "Locale") localeInstances.set(facade, instance);
      return harden(facade);
    }
    function receiver(value: unknown): NativeInstance {
      const instance = typeof value === "object" && value !== null ? instances.get(value) : undefined;
      if (!instance) throw new TypeError(`Invalid Intl.${name} receiver`);
      return instance;
    }
    function invoke(instance: NativeInstance, member: string, method: Callable, args: unknown[]): unknown {
      if (name === "DateTimeFormat" && (member === "format" || member === "formatToParts") && args[0] === undefined) {
        throw new TypeError("Intl.DateTimeFormat requires an explicit date");
      }
      const result = Reflect.apply(method, instance, args);
      if (name === "Locale" && (member === "maximize" || member === "minimize")) return wrap(result);
      if (name === "Segmenter" && member === "segment") return segmentsFacade(result);
      return copyResult(result);
    }
    const Constructor = function (this: unknown, locale: unknown, options: unknown) {
      if (!new.target && name !== "NumberFormat" && name !== "DateTimeFormat" && name !== "Collator") {
        throw new TypeError(`Intl.${name} requires new`);
      }
      const explicitLocales = name === "Locale" ? localeTag(locale) : locales(locale);
      let explicitOptions = options;
      if (name === "DateTimeFormat") {
        if (options === null || typeof options !== "object") {
          throw new TypeError("Intl.DateTimeFormat requires an explicit timeZone");
        }
        const timeZone = (options as Record<string, unknown>).timeZone;
        if (typeof timeZone !== "string" || timeZone.trim().length === 0) {
          throw new TypeError("Intl.DateTimeFormat requires an explicit timeZone");
        }
        explicitOptions = Object.create(options, { timeZone: { value: timeZone } });
      }
      return wrap(new Native(explicitLocales, explicitOptions));
    };
    Object.defineProperty(Constructor, "name", { value: name });
    Constructor.prototype = prototype;
    Object.defineProperty(prototype, "constructor", { value: Constructor });
    Object.defineProperty(prototype, Symbol.toStringTag, { value: `Intl.${name}` });
    for (const member of members.methods) {
      const method = Native.prototype[member];
      if (typeof method !== "function") continue;
      Object.defineProperty(prototype, member, { value: expose(function (this: unknown, ...args: unknown[]) {
        return invoke(receiver(this), member, method as Callable, args);
      }) });
    }
    for (const member of members.getters) {
      const getter = Object.getOwnPropertyDescriptor(Native.prototype, member)?.get;
      if (!getter) continue;
      const boundMethods = new WeakMap<object, Callable>();
      Object.defineProperty(prototype, member, { get: expose(function (this: unknown) {
        const instance = receiver(this);
        const value = Reflect.apply(getter, instance, []);
        if (typeof value !== "function") return copyResult(value);
        let bound = boundMethods.get(instance);
        if (!bound) {
          bound = expose((...args: unknown[]) => invoke(instance, member, value as Callable, args));
          boundMethods.set(instance, bound);
        }
        return bound;
      }) });
    }
    if (typeof Native.supportedLocalesOf === "function") {
      const supportedLocalesOf = Native.supportedLocalesOf;
      Object.defineProperty(Constructor, "supportedLocalesOf", { value: expose((locale: unknown, options: unknown) =>
        copyResult(Reflect.apply(supportedLocalesOf, Native, [locales(locale), options]))) });
    }
    intl[name] = expose(Constructor);
  }
  return harden(intl);
}
