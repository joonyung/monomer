import type { ConnectorType } from "@/lib/types/connector";

export interface ConnectorTypeMeta {
  type: ConnectorType;
  label: string;
  iconColor: string;
}

export const CONNECTOR_TYPE_META: Record<ConnectorType, ConnectorTypeMeta> = {
  DUCKDB: { type: "DUCKDB", label: "Sample data", iconColor: "var(--foreground-secondary)" },
  POSTGRESQL: {
    type: "POSTGRESQL",
    label: "PostgreSQL",
    iconColor: "oklch(0.42 0.13 240)",
  },
  BIGQUERY: {
    type: "BIGQUERY",
    label: "BigQuery",
    iconColor: "oklch(0.43 0.16 265)",
  },
  SNOWFLAKE: {
    type: "SNOWFLAKE",
    label: "Snowflake",
    iconColor: "oklch(0.43 0.14 220)",
  },
  DATABRICKS: {
    type: "DATABRICKS",
    label: "Databricks",
    iconColor: "oklch(0.5 0.16 30)",
  },
  TRINO: {
    type: "TRINO",
    label: "Trino",
    iconColor: "oklch(0.45 0.16 320)",
  },
  REDSHIFT: {
    type: "REDSHIFT",
    label: "Redshift",
    iconColor: "oklch(0.48 0.16 50)",
  },
};
