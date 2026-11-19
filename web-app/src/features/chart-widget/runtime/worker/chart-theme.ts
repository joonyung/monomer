import { CATEGORICAL_CHART_COLORS } from "@/lib/chart-color-scale";

// zrender cannot parse OKLCH or CSS variables, so the Worker uses the DESIGN.md hex projection.
export const MONOMER_CHART_NEUTRALS = Object.freeze({
  heading: "#15191d",
  foreground: "#1e2226",
  label: "#42464a",
  secondary: "#6c7074",
  muted: "#c9cdd1",
  axisLine: "#dfe3e7",
  track: "#eceff3",
  splitLine: "#f0f2f4",
  surface: "#fdfdfe",
});

export const MONOMER_SEQUENTIAL_RAMP = Object.freeze(["#ecf4fe", "#c1dcfe", "#88b6fa", "#4c86eb", "#3055b6"] as const);
export const MONOMER_DIVERGING_RAMP = Object.freeze(["#b23a26", "#e2947f", MONOMER_CHART_NEUTRALS.track, "#8db3ea", "#2e62c9"] as const);

const FONT_FAMILY = '"Geist Variable", system-ui, -apple-system, "Segoe UI", sans-serif';
const INK_OVERLAY_SHADOW = "rgba(15, 19, 24, 0.1)";
const INK_HOVER_BAND = "rgba(15, 19, 24, 0.04)";
const MARK_RADIUS = 3;

function deepFreeze<T>(value: T): T {
  if (value !== null && typeof value === "object" && !Object.isFrozen(value)) {
    Object.freeze(value);
    for (const child of Object.values(value)) deepFreeze(child);
  }
  return value;
}

const axisLabel = { color: MONOMER_CHART_NEUTRALS.secondary, fontSize: 11, margin: 10 };
const axisName = { color: MONOMER_CHART_NEUTRALS.secondary, fontSize: 11 };
const pointerLabel = {
  backgroundColor: MONOMER_CHART_NEUTRALS.foreground,
  color: MONOMER_CHART_NEUTRALS.surface,
  fontSize: 11,
  padding: [4, 6],
  borderRadius: 4,
};

// Value labels keep ECharts' exact default formatting: a per-tick compact format cannot see sibling ticks
// and collapses fine or small-magnitude ticks into duplicate labels.
function axisTheme(): Record<string, unknown> {
  return {
    axisLine: { lineStyle: { color: MONOMER_CHART_NEUTRALS.axisLine } },
    axisTick: { show: false },
    axisLabel: { ...axisLabel, hideOverlap: true },
    splitLine: { lineStyle: { color: MONOMER_CHART_NEUTRALS.splitLine } },
    nameTextStyle: axisName,
  };
}

const surfaceEdge = { borderColor: MONOMER_CHART_NEUTRALS.surface };

// Series keys must not include areaStyle, which turns every line into an area chart, showSymbol: false,
// which removes the hover target that the default item-trigger tooltip needs, or emphasis focus, whose
// blur state survives the Host's complete-option reapply after a rejected legend or zoom event.
// Bars and heatmap cells carry no canvas-colored border: dense marks narrower than the stroke would vanish.
// Text color is set per component, never globally: a global color disables ECharts' light inside-label fill.
export const MONOMER_CHART_THEME: Readonly<Record<string, unknown>> = deepFreeze({
  color: [...CATEGORICAL_CHART_COLORS],
  gradientColor: [...MONOMER_SEQUENTIAL_RAMP],
  backgroundColor: "transparent",
  textStyle: { fontFamily: FONT_FAMILY, fontSize: 12 },
  title: {
    textStyle: { color: MONOMER_CHART_NEUTRALS.heading, fontSize: 13, fontWeight: 400 },
    subtextStyle: { color: MONOMER_CHART_NEUTRALS.secondary, fontSize: 12 },
  },
  legend: {
    itemWidth: 12,
    itemHeight: 8,
    itemGap: 16,
    textStyle: { color: MONOMER_CHART_NEUTRALS.label, fontSize: 12 },
    inactiveColor: MONOMER_CHART_NEUTRALS.muted,
    pageIconColor: MONOMER_CHART_NEUTRALS.label,
    pageIconInactiveColor: MONOMER_CHART_NEUTRALS.axisLine,
    pageTextStyle: { color: MONOMER_CHART_NEUTRALS.secondary },
  },
  tooltip: {
    // ECharts confines only an explicit richText mode; the Worker resolves "auto" to richText without it.
    confine: true,
    backgroundColor: MONOMER_CHART_NEUTRALS.surface,
    borderColor: MONOMER_CHART_NEUTRALS.axisLine,
    borderWidth: 1,
    borderRadius: 6,
    padding: [8, 12],
    shadowBlur: 24,
    shadowOffsetY: 8,
    shadowColor: INK_OVERLAY_SHADOW,
    textStyle: { color: MONOMER_CHART_NEUTRALS.foreground, fontSize: 12 },
    axisPointer: {
      lineStyle: { color: MONOMER_CHART_NEUTRALS.secondary, opacity: 0.5, width: 1 },
      crossStyle: { color: MONOMER_CHART_NEUTRALS.secondary, opacity: 0.5, width: 1 },
      shadowStyle: { color: INK_HOVER_BAND },
      label: pointerLabel,
    },
  },
  axisPointer: {
    lineStyle: { color: MONOMER_CHART_NEUTRALS.secondary, opacity: 0.5, width: 1 },
    label: pointerLabel,
  },
  categoryAxis: axisTheme(),
  timeAxis: axisTheme(),
  valueAxis: axisTheme(),
  logAxis: axisTheme(),
  line: {
    lineStyle: { width: 2, cap: "round", join: "round" },
    symbol: "circle",
    symbolSize: 6,
    itemStyle: { ...surfaceEdge, borderWidth: 1.5 },
  },
  bar: {
    barMaxWidth: 40,
    barCategoryGap: "32%",
    barGap: "12%",
  },
  pie: {
    itemStyle: { ...surfaceEdge, borderWidth: 2, borderRadius: 4 },
    label: { fontSize: 11 },
    labelLine: { lineStyle: { color: MONOMER_CHART_NEUTRALS.muted } },
    emphasis: { scaleSize: 4 },
  },
  scatter: {
    symbolSize: 9,
    itemStyle: { ...surfaceEdge, borderWidth: 1, opacity: 0.85 },
  },
  heatmap: {
    itemStyle: { borderRadius: MARK_RADIUS },
  },
  funnel: {
    gap: 2,
    minSize: "24%",
    itemStyle: { borderWidth: 0 },
    label: { fontSize: 12 },
  },
  treemap: {
    breadcrumb: {
      itemStyle: { color: MONOMER_CHART_NEUTRALS.splitLine, borderColor: "transparent", shadowBlur: 0, textStyle: { color: MONOMER_CHART_NEUTRALS.label, fontSize: 11 } },
      emphasis: { itemStyle: { color: MONOMER_CHART_NEUTRALS.track } },
    },
    itemStyle: { ...surfaceEdge, borderWidth: 2, gapWidth: 2, borderRadius: MARK_RADIUS },
    label: { fontSize: 12 },
  },
  gauge: {
    progress: { show: true, roundCap: true, width: 10 },
    axisLine: { roundCap: true, lineStyle: { width: 10, color: [[1, MONOMER_CHART_NEUTRALS.track]] } },
    pointer: { show: false },
    anchor: { show: false },
    axisTick: { show: false },
    splitLine: { show: false },
    axisLabel: { show: false },
    title: { color: MONOMER_CHART_NEUTRALS.secondary, fontSize: 12, offsetCenter: [0, "32%"] },
    detail: { color: MONOMER_CHART_NEUTRALS.heading, fontSize: 26, fontWeight: 500, offsetCenter: [0, "0%"] },
  },
  radar: {
    axisName: { color: MONOMER_CHART_NEUTRALS.secondary, fontSize: 11 },
    axisLine: { lineStyle: { color: MONOMER_CHART_NEUTRALS.axisLine } },
    splitLine: { lineStyle: { color: MONOMER_CHART_NEUTRALS.splitLine } },
    splitArea: { show: false },
  },
  visualMap: {
    itemWidth: 12,
    itemHeight: 120,
    textStyle: { color: MONOMER_CHART_NEUTRALS.secondary, fontSize: 11 },
  },
  dataZoom: {
    borderColor: "transparent",
    backgroundColor: MONOMER_CHART_NEUTRALS.splitLine,
    fillerColor: "rgba(56, 112, 223, 0.12)",
    dataBackground: {
      lineStyle: { color: MONOMER_CHART_NEUTRALS.muted },
      areaStyle: { color: MONOMER_CHART_NEUTRALS.track },
    },
    handleStyle: { color: MONOMER_CHART_NEUTRALS.surface, borderColor: MONOMER_CHART_NEUTRALS.muted },
    moveHandleStyle: { color: MONOMER_CHART_NEUTRALS.axisLine },
    textStyle: { color: MONOMER_CHART_NEUTRALS.secondary, fontSize: 11 },
  },
  markLine: {
    symbol: ["none", "none"],
    lineStyle: { color: MONOMER_CHART_NEUTRALS.secondary, type: [4, 4], width: 1 },
    label: { color: MONOMER_CHART_NEUTRALS.secondary, fontSize: 11 },
  },
  animationDuration: 240,
  animationDurationUpdate: 180,
  animationEasing: "cubicOut",
  animationEasingUpdate: "cubicOut",
});

type OptionRecord = Readonly<Record<string, unknown>>;

function isRecord(value: unknown): value is OptionRecord {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}

const AXIS_KEYS = ["xAxis", "yAxis", "angleAxis", "radiusAxis", "singleAxis", "parallelAxis"] as const;

function mapRecords(value: unknown, transform: (record: OptionRecord) => OptionRecord): unknown {
  if (Array.isArray(value)) return value.map((item) => (isRecord(item) ? transform(item) : item));
  return isRecord(value) ? transform(value) : value;
}

// ECharts enables area fill whenever areaStyle exists, so an omitted opacity cannot come from the theme.
function withAreaDefaults(series: OptionRecord): OptionRecord {
  if ((series.type !== "line" && series.type !== "radar") || !isRecord(series.areaStyle)) return series;
  const areaStyle = series.areaStyle;
  if (areaStyle.color !== undefined || areaStyle.opacity !== undefined) return series;
  return { ...series, areaStyle: { ...areaStyle, opacity: 0.14 } };
}

type BarRadius = readonly [number, number, number, number];

// ECharts rounds the screen-top (or screen-right) corners whatever the value's sign, so the data end is chosen here.
const DATA_END_RADIUS = {
  vertical: { positive: [MARK_RADIUS, MARK_RADIUS, 0, 0], negative: [0, 0, MARK_RADIUS, MARK_RADIUS] },
  horizontal: { positive: [0, MARK_RADIUS, MARK_RADIUS, 0], negative: [MARK_RADIUS, 0, 0, MARK_RADIUS] },
} as const satisfies Record<string, Record<string, BarRadius>>;

function axisAt(axes: unknown, index: unknown): OptionRecord | undefined {
  const position = typeof index === "number" ? index : 0;
  const axis = Array.isArray(axes) ? axes[position] : position === 0 ? axes : undefined;
  return isRecord(axis) ? axis : undefined;
}

function barDatumValue(datum: unknown, horizontal: boolean): unknown {
  const value = isRecord(datum) ? datum.value : datum;
  return Array.isArray(value) ? value[horizontal ? 0 : 1] : value;
}

// Only the end of a bar away from the baseline is rounded; stacked segments stay square so the stack reads as one bar.
// Series whose values live in a dataset stay square because their sign is not visible here.
function withBarRadius(series: OptionRecord, axisOwner: OptionRecord): OptionRecord {
  if (series.type !== "bar" || series.coordinateSystem === "polar") return series;
  if (isRecord(series.itemStyle) && series.itemStyle.borderRadius !== undefined) return series;
  if (typeof series.stack === "string" && series.stack !== "") return series;
  if (!Array.isArray(series.data)) return series;
  const horizontal = axisAt(axisOwner.yAxis, series.yAxisIndex)?.type === "category";
  const radius = DATA_END_RADIUS[horizontal ? "horizontal" : "vertical"];
  const data = series.data.map((datum) => {
    const value = barDatumValue(datum, horizontal);
    if (typeof value !== "number" || value >= 0) return datum;
    if (!isRecord(datum)) return { value: datum, itemStyle: { borderRadius: radius.negative } };
    const itemStyle = isRecord(datum.itemStyle) ? datum.itemStyle : {};
    return itemStyle.borderRadius !== undefined ? datum : { ...datum, itemStyle: { ...itemStyle, borderRadius: radius.negative } };
  });
  return { ...series, data, itemStyle: { ...(isRecord(series.itemStyle) ? series.itemStyle : {}), borderRadius: radius.positive } };
}

function withoutPointerAnimation(record: OptionRecord): OptionRecord {
  return { ...record, axisPointer: { ...(isRecord(record.axisPointer) ? record.axisPointer : {}), animation: false } };
}

// Series, tooltip, and axis pointers read their own animation flag before the global one.
function presentOption(option: OptionRecord, reducedMotion: boolean, axisOwner: OptionRecord): OptionRecord {
  const presented: Record<string, unknown> = { ...option };
  if (option.series !== undefined) {
    presented.series = mapRecords(option.series, (series) => {
      const softened = withBarRadius(withAreaDefaults(series), axisOwner);
      return reducedMotion ? { ...softened, animation: false } : softened;
    });
  }
  if (!reducedMotion) return presented;
  presented.animation = false;
  presented.axisPointer = { ...(isRecord(option.axisPointer) ? option.axisPointer : {}), animation: false };
  if (option.tooltip !== undefined) presented.tooltip = mapRecords(option.tooltip, withoutPointerAnimation);
  for (const key of AXIS_KEYS) {
    if (option[key] !== undefined) {
      presented[key] = mapRecords(option[key], (axis) => (isRecord(axis.axisPointer) ? withoutPointerAnimation(axis) : axis));
    }
  }
  return presented;
}

// Host policy applied after source validation; the source ABI never sees it.
export function applyHostPresentationPolicy(option: OptionRecord, reducedMotion: boolean): OptionRecord {
  const presented = presentOption(option, reducedMotion, option) as Record<string, unknown>;
  const baseOption = isRecord(option.baseOption) ? option.baseOption : option;
  if (isRecord(option.baseOption)) presented.baseOption = presentOption(option.baseOption, reducedMotion, baseOption);
  if (Array.isArray(option.options)) {
    presented.options = option.options.map((timelineOption) => (
      isRecord(timelineOption) ? presentOption(timelineOption, reducedMotion, timelineOption.yAxis === undefined ? baseOption : timelineOption) : timelineOption
    ));
  }
  if (Array.isArray(option.media)) {
    presented.media = option.media.map((entry) => (
      isRecord(entry) && isRecord(entry.option) ? { ...entry, option: presentOption(entry.option, reducedMotion, entry.option.yAxis === undefined ? baseOption : entry.option) } : entry
    ));
  }
  return presented;
}
