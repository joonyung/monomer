import type { SemanticModelResponse } from "@/api/semantic-model";
import type { Metric } from "@/lib/types/metric";
import type { Connector } from "@/lib/types/connector";
import type { TimeSpineConfiguration } from "@/lib/types/time-spine";

export const SEMANTICS_FIXTURE_WORKSPACE_ID = "71000000-0000-0000-0000-000000000001";
export const SEMANTICS_FIXTURE_NOW_EPOCH_MS = Date.parse("2026-07-16T12:00:00.000Z");

export const SEMANTIC_MODEL_FIXTURES: SemanticModelResponse[] = [
  {
    id: "71000000-0000-0000-0000-000000000101",
    name: "commerce__orders",
    label: "Orders",
    description: "Order activity and recognized revenue across sales channels.",
    sourceRelations: [{ connectorId: "71000000-0000-0000-0000-000000000301", catalog: "analytics", schema: "commerce", relation: "fct_orders" }],
    aggregationTimeDimension: "ordered_at",
    entities: [
      { name: "order", label: "Order", description: "Unique order identity.", type: "primary", expression: "order_id" },
      { name: "customer", label: "Customer", description: "Customer responsible for the order.", type: "foreign", expression: "customer_id" },
      { name: "account", label: "Account", description: "Commercial account associated with the order.", type: "foreign", expression: "account_id" },
    ],
    dimensions: [
      { isPartition: false, name: "ordered_at", label: "Ordered at", description: "Order creation time.", type: "time", expression: "ordered_at", granularity: "day" },
      { isPartition: false, name: "channel", label: "Channel", description: "Sales channel for the order.", type: "categorical", expression: "sales_channel" },
    ],
    measures: [
      { name: "net_revenue", label: "Net revenue", description: "Recognized revenue after refunds.", aggregation: "sum", expression: "net_revenue", aggregationTimeDimension: "ordered_at" },
      { name: "order_count", label: "Order count", description: "Number of orders.", aggregation: "count", expression: "order_id", aggregationTimeDimension: "ordered_at" },
    ],
    createdAt: "2026-07-02T10:00:00.000Z",
    updatedAt: "2026-07-16T10:15:00.000Z",
  },
  {
    id: "71000000-0000-0000-0000-000000000102",
    name: "growth__subscriptions",
    label: "Subscriptions",
    description: "Subscription lifecycle, plan, and renewal definitions.",
    sourceRelations: [{ connectorId: "71000000-0000-0000-0000-000000000301", catalog: "analytics", schema: "growth", relation: "fct_subscriptions" }],
    aggregationTimeDimension: "started_at",
    entities: [
      { name: "subscription", label: "Subscription", description: "Unique subscription identity.", type: "primary", expression: "subscription_id" },
      { name: "account", label: "Account", description: "Account that owns the subscription.", type: "foreign", expression: "account_id" },
    ],
    dimensions: [
      { isPartition: false, name: "started_at", label: "Started at", description: "Subscription start time.", type: "time", expression: "started_at", granularity: "day" },
      { isPartition: false, name: "plan", label: "Plan", description: "Current subscription plan.", type: "categorical", expression: "plan_code" },
    ],
    measures: [
      { name: "subscription_revenue", label: "Subscription revenue", description: "Recurring subscription revenue.", aggregation: "sum", expression: "monthly_amount", aggregationTimeDimension: "started_at" },
      { name: "trial_started", label: "Trials started", description: "Trial starts.", aggregation: "count", expression: "trial_started_at", aggregationTimeDimension: "started_at" },
      { name: "paid_conversion", label: "Paid conversions", description: "Trials converted to paid plans.", aggregation: "count", expression: "paid_at", aggregationTimeDimension: "started_at" },
    ],
    createdAt: "2026-06-28T09:00:00.000Z",
    updatedAt: "2026-07-15T08:00:00.000Z",
  },
  {
    id: "71000000-0000-0000-0000-000000000103",
    name: "customer__accounts",
    label: "Customer Accounts",
    description: "Canonical customer and account attributes.",
    sourceRelations: [
      { connectorId: "71000000-0000-0000-0000-000000000301", catalog: "analytics", schema: "customer", relation: "dim_accounts" },
      { connectorId: "warehouse_backup", schema: "customer", relation: "dim_accounts" },
    ],
    entities: [
      { name: "customer", label: "Customer", description: "Canonical customer identity.", type: "primary", expression: "customer_id" },
      { name: "account", label: "Account", description: "Canonical commercial account.", type: "unique", expression: "account_id" },
    ],
    dimensions: [
      { isPartition: false, name: "segment", label: "Segment", description: "Customer segment.", type: "categorical", expression: "segment" },
      { isPartition: false, name: "region", label: "Region", description: "Customer billing region.", type: "categorical", expression: "region" },
    ],
    measures: [
      { name: "active_customers", label: "Active customers", description: "Distinct active customers.", aggregation: "count_distinct", expression: "customer_id" },
    ],
    createdAt: "2026-06-10T09:00:00.000Z",
    updatedAt: "2026-07-12T14:00:00.000Z",
  },
];

export const METRIC_FIXTURES: Metric[] = [
  {
    id: "71000000-0000-0000-0000-000000000201",
    name: "net_revenue",
    label: "Net Revenue",
    description: "Recognized revenue after discounts and refunds.",
    type: "simple",
    typeParams: { semanticModelName: "commerce__orders", measure: "net_revenue" },
    createdAt: "2026-07-03T10:00:00.000Z",
    updatedAt: "2026-07-16T09:30:00.000Z",
  },
  {
    id: "71000000-0000-0000-0000-000000000202",
    name: "revenue_per_customer",
    label: "Revenue per Customer",
    description: "Net revenue divided by active customers in the period.",
    type: "ratio",
    typeParams: {
      numerator: { metricName: "net_revenue" },
      denominator: { metricName: "active_customers" },
    },
    createdAt: "2026-07-03T10:00:00.000Z",
    updatedAt: "2026-07-15T11:00:00.000Z",
  },
  {
    id: "71000000-0000-0000-0000-000000000203",
    name: "monthly_recurring_revenue",
    label: "Monthly Recurring Revenue",
    description: "Normalized recurring subscription revenue for the month.",
    type: "derived",
    typeParams: {
      expression: "subscription_revenue",
      inputMetrics: [{ metricName: "subscription_revenue", alias: "subscription_revenue" }],
    },
    createdAt: "2026-07-01T10:00:00.000Z",
    updatedAt: "2026-07-14T15:00:00.000Z",
  },
  {
    id: "71000000-0000-0000-0000-000000000204",
    name: "rolling_28d_orders",
    label: "Rolling 28-day Orders",
    description: "Orders accumulated across the trailing 28-day window.",
    type: "cumulative",
    typeParams: { inputMetric: { metricName: "net_revenue" }, window: "28 day" },
    createdAt: "2026-06-20T10:00:00.000Z",
    updatedAt: "2026-07-12T13:00:00.000Z",
  },
  {
    id: "71000000-0000-0000-0000-000000000205",
    name: "trial_to_paid_conversion",
    label: "Trial to Paid Conversion",
    description: "Share of trial accounts that become paying customers.",
    type: "conversion",
    typeParams: {
      entity: "account",
      calculation: "conversion_rate",
      baseMetric: { metricName: "trial_started" },
      conversionMetric: { metricName: "paid_conversion" },
      window: "30 day",
    },
    createdAt: "2026-06-15T10:00:00.000Z",
    updatedAt: "2026-07-10T16:00:00.000Z",
  },
  {
    id: "71000000-0000-0000-0000-000000000206",
    name: "active_customers",
    label: "Active Customers",
    description: "Distinct customers active in the period.",
    type: "simple",
    typeParams: { semanticModelName: "customer__accounts", measure: "active_customers" },
    createdAt: "2026-07-03T10:00:00.000Z",
    updatedAt: "2026-07-15T09:00:00.000Z",
  },
  {
    id: "71000000-0000-0000-0000-000000000207",
    name: "subscription_revenue",
    label: "Subscription Revenue",
    description: "Recurring subscription revenue.",
    type: "simple",
    typeParams: { semanticModelName: "growth__subscriptions", measure: "subscription_revenue" },
    createdAt: "2026-07-01T10:00:00.000Z",
    updatedAt: "2026-07-14T14:00:00.000Z",
  },
  {
    id: "71000000-0000-0000-0000-000000000208",
    name: "trial_started",
    label: "Trials Started",
    description: "Trial subscriptions started.",
    type: "simple",
    typeParams: { semanticModelName: "growth__subscriptions", measure: "trial_started" },
    createdAt: "2026-06-15T10:00:00.000Z",
    updatedAt: "2026-07-10T15:00:00.000Z",
  },
  {
    id: "71000000-0000-0000-0000-000000000209",
    name: "paid_conversion",
    label: "Paid Conversions",
    description: "Trial subscriptions converted to paid plans.",
    type: "simple",
    typeParams: { semanticModelName: "growth__subscriptions", measure: "paid_conversion" },
    createdAt: "2026-06-15T10:00:00.000Z",
    updatedAt: "2026-07-10T15:30:00.000Z",
  },
];

export const CONNECTOR_FIXTURES: Connector[] = [
  {
    id: "71000000-0000-0000-0000-000000000301",
    workspaceId: SEMANTICS_FIXTURE_WORKSPACE_ID,
    name: "Warehouse production",
    type: "POSTGRESQL",
    properties: { host: "fixture.internal", port: 5432, database: "warehouse" },
    createdAtEpochMs: SEMANTICS_FIXTURE_NOW_EPOCH_MS,
    updatedAtEpochMs: SEMANTICS_FIXTURE_NOW_EPOCH_MS,
  },
  {
    id: "71000000-0000-0000-0000-000000000302",
    workspaceId: SEMANTICS_FIXTURE_WORKSPACE_ID,
    name: "Lakehouse",
    type: "TRINO",
    properties: { host: "fixture.internal", port: 443, catalog: "lake" },
    createdAtEpochMs: SEMANTICS_FIXTURE_NOW_EPOCH_MS,
    updatedAtEpochMs: SEMANTICS_FIXTURE_NOW_EPOCH_MS,
  },
];

export const TIME_SPINE_FIXTURES: TimeSpineConfiguration[] = [
  {
    connectorId: CONNECTOR_FIXTURES[0].id,
    definition: {
      schemaVersion: 1,
      timeSpines: [
        {
          relation: { catalog: "analytics", schema: "shared", relation: "dim_date" },
          primaryColumn: { name: "date_day", granularity: "day" },
        },
        {
          relation: { catalog: "analytics", schema: "shared", relation: "dim_date" },
          primaryColumn: { name: "week_start", granularity: "week" },
        },
      ],
    },
  },
];
