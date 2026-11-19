import { toChartQueryResult, validateDashboardResult } from "@/features/dashboard/dashboard-result";
import type { ChartWidgetInputs } from "@/features/chart-widget/runtime/worker-protocol";

type ColumnSpec = Readonly<{ name: string; logicalType: string }>;

function result(queryRunId: string, columns: readonly ColumnSpec[], rows: readonly (readonly string[])[]) {
  return toChartQueryResult(validateDashboardResult({ queryType: "SEMANTIC" as const, reportingTimeZone: null,
    queryRunId,
    columns: columns.map((column) => ({ ...column, nullable: false, display: null })),
    rows: rows.map((row) => [...row]),
  }));
}

export const themeFixtureViewport = { width: 420, height: 260 } as const;

const weeks = ["Jun 2", "Jun 9", "Jun 16", "Jun 23", "Jun 30", "Jul 7", "Jul 14", "Jul 21", "Jul 28", "Aug 4", "Aug 11", "Aug 18"];
const currentRevenue = [412, 436, 428, 455, 471, 468, 492, 510, 503, 529, 548, 561];
const priorRevenue = [398, 405, 419, 414, 430, 441, 437, 452, 460, 458, 471, 480];

function weeklyRows(refresh: number): string[][] {
  return weeks.map((week, index) => [
    week,
    String((currentRevenue[index] + refresh * (index % 3 === 0 ? 14 : -6)) * 1000),
    String(priorRevenue[index] * 1000),
  ]);
}

const months = ["Mar", "Apr", "May", "Jun", "Jul", "Aug"];
const regions = ["North America", "Europe", "Asia Pacific", "Latin America", "Middle East"];
const regionRevenue = [1_003_000, 763_000, 581_000, 330_000, 212_000];
const channels = ["direct", "partner", "marketplace"] as const;
const channelOrders: Record<(typeof channels)[number], number[]> = {
  direct: [920, 960, 1010, 1080, 1120, 1190],
  partner: [520, 540, 560, 590, 610, 640],
  marketplace: [210, 230, 260, 280, 300, 330],
};
const categories = ["Apparel", "Electronics", "Home", "Beauty", "Outdoor", "Toys", "Books"];
const categoryChange = [8.4, -3.1, 5.2, 12.7, -6.8, 2.3, -1.4];
const weekdays = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"];
const conversionChannels = ["search", "social", "email", "referral", "display"];

const trendSource = `export default defineChart({ render({ queries }) {
  const rows = queries.weekly.rows;
  return {
    tooltip: { trigger: "axis" },
    grid: { left: 48, right: 16, top: 16, bottom: 32 },
    xAxis: { type: "category", data: rows.map((row) => row["metric_time.week"]) },
    yAxis: { type: "value" },
    series: [{ id: "net_revenue", name: "Net revenue", type: "line", data: rows.map((row) => Number(row["metric.net_revenue"])) }],
  };
} });`;

const areaSource = `export default defineChart({ render({ queries }) {
  const rows = queries.weekly.rows;
  return {
    tooltip: { trigger: "axis" },
    grid: { left: 48, right: 40, top: 16, bottom: 32 },
    xAxis: { type: "category", boundaryGap: false, data: rows.map((row) => row["metric_time.week"]) },
    yAxis: { type: "value", min: 350000 },
    series: [{
      id: "net_revenue", name: "Net revenue", type: "line", areaStyle: {}, showSymbol: false,
      markLine: { data: [{ type: "average", name: "Average" }], label: { formatter: "Avg" } },
      data: rows.map((row) => Number(row["metric.net_revenue"])),
    }],
  };
} });`;

const baselineSource = `export default defineChart({ render({ queries }) {
  const rows = queries.weekly.rows;
  return {
    tooltip: { trigger: "axis" },
    legend: { top: 0, left: 0 },
    grid: { left: 48, right: 16, top: 36, bottom: 32 },
    xAxis: { type: "category", data: rows.map((row) => row["metric_time.week"]) },
    yAxis: { type: "value" },
    series: [
      { id: "net_revenue", name: "This period", type: "line", data: rows.map((row) => Number(row["metric.net_revenue"])) },
      {
        id: "net_revenue_prior", name: "Prior period", type: "line", showSymbol: false,
        lineStyle: { color: "#6c7074", type: "dashed", width: 1.5 }, itemStyle: { color: "#6c7074" },
        data: rows.map((row) => Number(row["metric.net_revenue_prior"])),
      },
    ],
  };
} });`;

const categoricalSource = `const channelOrder = ["direct", "partner", "marketplace"];
const channelLabel = { direct: "Direct", partner: "Partner", marketplace: "Marketplace" };
export default defineChart({ render({ queries }) {
  const rows = queries.channels.rows;
  const channels = channelOrder.filter((channel) => rows.some((row) => row["dimension.orders.channel"] === channel));
  const months = [...new Set(rows.map((row) => row["metric_time.month"]))];
  return {
    tooltip: { trigger: "axis" },
    legend: { top: 0, left: 0 },
    grid: { left: 44, right: 16, top: 36, bottom: 32 },
    xAxis: { type: "category", data: months },
    yAxis: { type: "value" },
    series: channels.map((channel) => ({
      id: channel, name: channelLabel[channel], type: "bar", stack: "orders",
      data: months.map((month) => Number(rows.find((row) => row["metric_time.month"] === month && row["dimension.orders.channel"] === channel)["metric.orders"])),
    })),
  };
} });`;

const groupedSource = `export default defineChart({ render({ queries }) {
  const rows = queries.quarters.rows;
  return {
    tooltip: { trigger: "axis" },
    legend: { top: 0, left: 0 },
    grid: { left: 44, right: 16, top: 36, bottom: 32 },
    xAxis: { type: "category", data: rows.map((row) => row["dimension.orders.region_short"]) },
    yAxis: { type: "value" },
    series: [
      { id: "orders_q3", name: "Q3", type: "bar", data: rows.map((row) => Number(row["metric.orders_q3"])) },
      { id: "orders_q2", name: "Q2", type: "bar", data: rows.map((row) => Number(row["metric.orders_q2"])) },
    ],
  };
} });`;

const rankingSource = `export default defineChart({ render({ queries }) {
  const rows = [...queries.regions.rows].reverse();
  return {
    tooltip: { trigger: "item" },
    grid: { left: 96, right: 48, top: 8, bottom: 24 },
    xAxis: { type: "value" },
    yAxis: { type: "category", data: rows.map((row) => row["dimension.orders.region"]) },
    series: [{
      id: "net_revenue_by_region", name: "Net revenue", type: "bar",
      label: { show: true, position: "right", formatter: (params) => "$" + Math.round(params.value / 1000) + "K" },
      data: rows.map((row) => Number(row["metric.net_revenue"])),
    }],
  };
} });`;

const multiLineSource = `const order = ["search", "social", "email", "referral", "display"];
const label = { search: "Search", social: "Social", email: "Email", referral: "Referral", display: "Display" };
export default defineChart({ render({ queries }) {
  const rows = queries.conversion.rows;
  const months = [...new Set(rows.map((row) => row["metric_time.month"]))];
  return {
    tooltip: { trigger: "axis", valueFormatter: (value) => value.toFixed(1) + "%" },
    legend: { top: 0, left: 0 },
    grid: { left: 40, right: 16, top: 36, bottom: 32 },
    xAxis: { type: "category", data: months },
    yAxis: { type: "value", axisLabel: { formatter: (value) => value + "%" } },
    series: order.map((channel) => ({
      id: channel, name: label[channel], type: "line",
      data: months.map((month) => Number(rows.find((row) => row["metric_time.month"] === month && row["dimension.sessions.channel"] === channel)["metric.conversion_rate"])),
    })),
  };
} });`;

const divergingSource = `export default defineChart({ render({ queries }) {
  const rows = queries.change.rows;
  return {
    tooltip: { trigger: "item", valueFormatter: (value) => (value > 0 ? "+" : "") + value + "%" },
    grid: { left: 40, right: 16, top: 16, bottom: 32 },
    xAxis: { type: "category", data: rows.map((row) => row["dimension.products.category"]), axisLabel: { interval: 0, fontSize: 10 } },
    yAxis: { type: "value", axisLabel: { formatter: (value) => value + "%" } },
    series: [{
      id: "change_vs_prior", name: "Change vs prior", type: "bar",
      markLine: { silent: true, label: { show: false }, lineStyle: { type: "solid" }, data: [{ yAxis: 0 }] },
      data: rows.map((row) => {
        const value = Number(row["metric.revenue_change_pct"]);
        return { value, itemStyle: { color: value >= 0 ? "#3870df" : "#e06d4f" } };
      }),
    }],
  };
} });`;

const comboSource = `export default defineChart({ render({ queries }) {
  const rows = queries.monthly.rows;
  return {
    tooltip: { trigger: "axis" },
    legend: { top: 0, left: 0 },
    grid: { left: 44, right: 44, top: 44, bottom: 32 },
    xAxis: { type: "category", data: rows.map((row) => row["metric_time.month"]) },
    yAxis: [
      { type: "value", name: "Orders", nameTextStyle: { align: "left" } },
      { type: "value", name: "AOV", position: "right", splitLine: { show: false }, axisLabel: { formatter: (value) => "$" + value } },
    ],
    series: [
      { id: "orders", name: "Orders", type: "bar", data: rows.map((row) => Number(row["metric.orders"])) },
      { id: "aov", name: "AOV", type: "line", yAxisIndex: 1, data: rows.map((row) => Number(row["metric.aov"])) },
    ],
  };
} });`;

const donutSource = `export default defineChart({ render({ queries }) {
  const rows = queries.plans.rows;
  const total = rows.reduce((sum, row) => sum + Number(row["metric.mrr"]), 0);
  return {
    tooltip: { trigger: "item" },
    legend: { orient: "vertical", right: 8, top: "middle" },
    title: { text: "$" + Math.round(total / 1000) + "K", subtext: "MRR", left: "35%", top: "middle", textAlign: "center", padding: 0, itemGap: 2, textStyle: { fontSize: 20, fontWeight: 500 } },
    series: [{
      id: "mrr_by_plan", name: "MRR", type: "pie", radius: ["54%", "80%"], center: ["35%", "50%"], label: { show: false },
      data: rows.map((row) => ({ name: row["dimension.subscriptions.plan"], value: Number(row["metric.mrr"]) })),
    }],
  };
} });`;

const scatterSource = `const lineLabel = { core: "Core", premium: "Premium", outlet: "Outlet" };
export default defineChart({ render({ queries }) {
  const rows = queries.products.rows;
  return {
    tooltip: { trigger: "item" },
    legend: { top: 0, left: 0 },
    grid: { left: 44, right: 16, top: 36, bottom: 40 },
    xAxis: { type: "value", name: "Price", nameLocation: "middle", nameGap: 26, axisLabel: { formatter: (value) => "$" + value } },
    yAxis: { type: "value", axisLabel: { formatter: (value) => value + "%" } },
    series: ["core", "premium", "outlet"].map((line) => ({
      id: line, name: lineLabel[line], type: "scatter",
      data: rows.filter((row) => row["dimension.products.line"] === line).map((row) => [Number(row["metric.price"]), Number(row["metric.margin_pct"])]),
    })),
  };
} });`;

const sequentialSource = `export default defineChart({ render({ queries }) {
  const rows = queries.heatmap.rows;
  const hours = [...new Set(rows.map((row) => row["dimension.orders.hour"]))];
  const days = [...new Set(rows.map((row) => row["dimension.orders.weekday"]))];
  const values = rows.map((row) => Number(row["metric.orders"]));
  return {
    tooltip: { trigger: "item" },
    grid: { left: 40, right: 16, top: 8, bottom: 56 },
    xAxis: { type: "category", data: hours },
    yAxis: { type: "category", data: days },
    visualMap: { min: Math.min(...values), max: Math.max(...values), orient: "horizontal", left: "center", bottom: 0, calculable: false },
    series: [{
      id: "orders_by_hour", name: "Orders", type: "heatmap", itemStyle: { borderColor: "#fdfdfe", borderWidth: 2 },
      data: rows.map((row) => [hours.indexOf(row["dimension.orders.hour"]), days.indexOf(row["dimension.orders.weekday"]), Number(row["metric.orders"])]),
    }],
  };
} });`;

const gaugeSource = `export default defineChart({ render({ queries }) {
  const value = Math.round(Number(queries.quota.rows[0]["metric.attainment_pct"]));
  return {
    series: [{
      id: "quota_attainment", name: "Quota attainment", type: "gauge", startAngle: 210, endAngle: -30, min: 0, max: 100,
      radius: "86%", center: ["50%", "58%"], detail: { formatter: "{value}%" },
      data: [{ value, name: "of Q3 quota" }],
    }],
  };
} });`;

const treemapSource = `export default defineChart({ render({ queries }) {
  const rows = queries.categories.rows;
  return {
    tooltip: { trigger: "item" },
    series: [{
      id: "revenue_by_category", name: "Revenue", type: "treemap", roam: false, nodeClick: false, breadcrumb: { show: false },
      top: 0, left: 0, right: 0, bottom: 0,
      data: rows.map((row) => ({ name: row["dimension.products.category"], value: Number(row["metric.revenue"]) })),
    }],
  };
} });`;

const funnelSource = `export default defineChart({ render({ queries }) {
  const rows = queries.funnel.rows;
  return {
    tooltip: { trigger: "item" },
    series: [{
      id: "checkout_funnel", name: "Checkout", type: "funnel", sort: "none", top: 8, bottom: 8, left: "8%", width: "84%",
      label: { position: "inside", formatter: (params) => params.name + "  " + new Intl.NumberFormat("en-US").format(params.value) },
      data: rows.map((row) => ({ name: row["dimension.funnel.step"], value: Number(row["metric.sessions"]) })),
    }],
  };
} });`;

export type ThemeFixtureChart = Readonly<{
  id: string;
  title: string;
  description: string;
  inputs: (state: Readonly<{ refresh: number; reversed: boolean }>) => ChartWidgetInputs;
}>;

function inputs(moduleSource: string, queries: ChartWidgetInputs["queries"]): ChartWidgetInputs {
  return { moduleSource, queries, parameters: {}, variables: {}, locale: "en-US", viewport: themeFixtureViewport };
}

const weeklyColumns = [
  { name: "metric_time.week", logicalType: "TEXT" },
  { name: "metric.net_revenue", logicalType: "NUMBER" },
  { name: "metric.net_revenue_prior", logicalType: "NUMBER" },
];

export const themeFixtureCharts: readonly ThemeFixtureChart[] = [
  {
    id: "single-trend",
    title: "Net revenue",
    description: "Line · no source colors",
    inputs: ({ refresh }) => inputs(trendSource, { weekly: result("theme-weekly", weeklyColumns, weeklyRows(refresh)) }),
  },
  {
    id: "area-trend",
    title: "Net revenue with average",
    description: "Area · host-softened fill · reference line",
    inputs: ({ refresh }) => inputs(areaSource, { weekly: result("theme-area", weeklyColumns, weeklyRows(refresh)) }),
  },
  {
    id: "neutral-baseline",
    title: "Net revenue vs prior period",
    description: "Neutral baseline · dashed prior period",
    inputs: ({ refresh }) => inputs(baselineSource, { weekly: result("theme-baseline", weeklyColumns, weeklyRows(refresh)) }),
  },
  {
    id: "stable-categorical",
    title: "Orders by channel",
    description: "Stacked bar · canonical series order",
    inputs: ({ reversed }) => {
      const order = reversed ? [...channels].reverse() : [...channels];
      return inputs(categoricalSource, {
        channels: result("theme-channels", [
          { name: "metric_time.month", logicalType: "TEXT" },
          { name: "dimension.orders.channel", logicalType: "TEXT" },
          { name: "metric.orders", logicalType: "NUMBER" },
        ], order.flatMap((channel) => months.map((month, index) => [month, channel, String(channelOrders[channel][index])]))),
      });
    },
  },
  {
    id: "grouped-bar",
    title: "Orders by region, Q3 vs Q2",
    description: "Grouped bar · two series",
    inputs: () => inputs(groupedSource, {
      quarters: result("theme-quarters", [
        { name: "dimension.orders.region_short", logicalType: "TEXT" },
        { name: "metric.orders_q3", logicalType: "NUMBER" },
        { name: "metric.orders_q2", logicalType: "NUMBER" },
      ], [["NA", "4210", "3890"], ["EU", "3320", "3405"], ["APAC", "2480", "2105"], ["LATAM", "1290", "1180"], ["MEA", "760", "690"]]),
    }),
  },
  {
    id: "ranking-bar",
    title: "Net revenue by region",
    description: "Ranking · horizontal bar · direct labels",
    inputs: () => inputs(rankingSource, {
      regions: result("theme-regions", [
        { name: "dimension.orders.region", logicalType: "TEXT" },
        { name: "metric.net_revenue", logicalType: "NUMBER" },
      ], regions.map((region, index) => [region, String(regionRevenue[index])])),
    }),
  },
  {
    id: "multi-line",
    title: "Conversion rate by channel",
    description: "Five series · palette order",
    inputs: () => inputs(multiLineSource, {
      conversion: result("theme-conversion", [
        { name: "metric_time.month", logicalType: "TEXT" },
        { name: "dimension.sessions.channel", logicalType: "TEXT" },
        { name: "metric.conversion_rate", logicalType: "NUMBER" },
      ], conversionChannels.flatMap((channel, channelIndex) => months.map((month, monthIndex) => [
        month, channel, (4.8 - channelIndex * 0.8 + Math.sin(monthIndex + channelIndex) * 0.35 + monthIndex * 0.08).toFixed(2),
      ]))),
    }),
  },
  {
    id: "diverging",
    title: "Revenue change vs prior",
    description: "Diverging · coral below, blue above zero",
    inputs: () => inputs(divergingSource, {
      change: result("theme-change", [
        { name: "dimension.products.category", logicalType: "TEXT" },
        { name: "metric.revenue_change_pct", logicalType: "NUMBER" },
      ], categories.map((category, index) => [category, String(categoryChange[index])])),
    }),
  },
  {
    id: "combo",
    title: "Orders and average order value",
    description: "Bar + line · dual axis",
    inputs: () => inputs(comboSource, {
      monthly: result("theme-combo", [
        { name: "metric_time.month", logicalType: "TEXT" },
        { name: "metric.orders", logicalType: "NUMBER" },
        { name: "metric.aov", logicalType: "NUMBER" },
      ], months.map((month, index) => [month, String([1650, 1730, 1830, 1950, 2030, 2160][index]), String([288, 294, 291, 303, 309, 312][index])])),
    }),
  },
  {
    id: "donut",
    title: "MRR by plan",
    description: "Donut · separated segments",
    inputs: () => inputs(donutSource, {
      plans: result("theme-plans", [
        { name: "dimension.subscriptions.plan", logicalType: "TEXT" },
        { name: "metric.mrr", logicalType: "NUMBER" },
      ], [["Enterprise", "184000"], ["Business", "126000"], ["Team", "88000"], ["Starter", "41000"], ["Legacy", "17000"]]),
    }),
  },
  {
    id: "scatter",
    title: "Price vs margin",
    description: "Scatter · three product lines",
    inputs: () => inputs(scatterSource, {
      products: result("theme-products", [
        { name: "dimension.products.line", logicalType: "TEXT" },
        { name: "metric.price", logicalType: "NUMBER" },
        { name: "metric.margin_pct", logicalType: "NUMBER" },
      ], Array.from({ length: 36 }, (_, index) => {
        const line = ["core", "premium", "outlet"][index % 3];
        const [price, margin] = ({ core: [60, 42], premium: [160, 55], outlet: [35, 24] } as Record<string, number[]>)[line];
        return [line, String(Math.round(price + ((index * 37) % 50) - 20)), (margin + ((index * 13) % 14) - 6).toFixed(1)];
      })),
    }),
  },
  {
    id: "sequential",
    title: "Orders by weekday and hour",
    description: "Heatmap · sequential ramp · source cell gaps",
    inputs: () => inputs(sequentialSource, {
      heatmap: result("theme-heatmap", [
        { name: "dimension.orders.weekday", logicalType: "TEXT" },
        { name: "dimension.orders.hour", logicalType: "TEXT" },
        { name: "metric.orders", logicalType: "NUMBER" },
      ], weekdays.flatMap((day, dayIndex) => Array.from({ length: 12 }, (_, slot) => {
        const hour = 8 + slot;
        const weekend = dayIndex >= 5 ? 0.6 : 1;
        const peak = Math.exp(-((hour - 13) ** 2) / 10) + 0.5 * Math.exp(-((hour - 19) ** 2) / 6);
        return [day, String(hour).padStart(2, "0") + ":00", String(Math.round(40 + 220 * peak * weekend))];
      }))),
    }),
  },
  {
    id: "gauge",
    title: "Quota attainment",
    description: "Gauge · progress arc",
    inputs: () => inputs(gaugeSource, {
      quota: result("theme-quota", [{ name: "metric.attainment_pct", logicalType: "NUMBER" }], [["72.4"]]),
    }),
  },
  {
    id: "treemap",
    title: "Revenue by category",
    description: "Treemap · palette order by size",
    inputs: () => inputs(treemapSource, {
      categories: result("theme-categories", [
        { name: "dimension.products.category", logicalType: "TEXT" },
        { name: "metric.revenue", logicalType: "NUMBER" },
      ], [["Apparel", "420"], ["Electronics", "360"], ["Home", "240"], ["Beauty", "180"], ["Outdoor", "120"], ["Toys", "80"], ["Books", "50"]]),
    }),
  },
  {
    id: "funnel",
    title: "Checkout funnel",
    description: "Funnel · ordered steps",
    inputs: () => inputs(funnelSource, {
      funnel: result("theme-funnel", [
        { name: "dimension.funnel.step", logicalType: "TEXT" },
        { name: "metric.sessions", logicalType: "NUMBER" },
      ], [["Visited", "48200"], ["Added to cart", "12900"], ["Checkout", "6400"], ["Paid", "4100"]]),
    }),
  },
];
