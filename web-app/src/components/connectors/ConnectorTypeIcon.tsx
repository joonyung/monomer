import {
  siDatabricks,
  siGooglebigquery,
  siPostgresql,
  siSnowflake,
  siTrino,
} from "simple-icons";
import type { ConnectorType } from "@/lib/types/connector";
import { Database } from "lucide-react";

interface ConnectorTypeIconProps {
  type: ConnectorType;
  className?: string;
}

interface BrandPath {
  viewBox: string;
  d: string;
}

const BRAND_PATHS: Partial<Record<ConnectorType, BrandPath>> = {
  POSTGRESQL: { viewBox: "0 0 24 24", d: siPostgresql.path },
  BIGQUERY: { viewBox: "0 0 24 24", d: siGooglebigquery.path },
  SNOWFLAKE: { viewBox: "0 0 24 24", d: siSnowflake.path },
  DATABRICKS: { viewBox: "0 0 24 24", d: siDatabricks.path },
  TRINO: { viewBox: "0 0 24 24", d: siTrino.path },
};

export function ConnectorTypeIcon({ type, className = "" }: ConnectorTypeIconProps) {
  if (type === "DUCKDB") return <Database className={className} aria-hidden="true" />;
  const brand = BRAND_PATHS[type];

  if (brand) {
    return (
      <svg
        viewBox={brand.viewBox}
        xmlns="http://www.w3.org/2000/svg"
        fill="currentColor"
        className={className}
        aria-hidden="true"
      >
        <path d={brand.d} />
      </svg>
    );
  }

  if (type === "REDSHIFT") {
    return (
      <svg
        viewBox="0 0 24 24"
        xmlns="http://www.w3.org/2000/svg"
        fill="none"
        stroke="currentColor"
        strokeWidth="1.6"
        strokeLinecap="round"
        strokeLinejoin="round"
        className={className}
        aria-hidden="true"
      >
        <circle cx="12" cy="12" r="9.5" />
        <path d="M7.5 9.5 12 13l4.5-3.5" />
        <path d="M7.5 13.5 12 17l4.5-3.5" />
      </svg>
    );
  }

  return null;
}
