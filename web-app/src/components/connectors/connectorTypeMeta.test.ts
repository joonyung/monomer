import { describe, expect, it } from "vitest";
import {
  CONNECTOR_TYPE_META,
} from "@/components/connectors/connectorTypeMeta";
import { CONNECTOR_TYPE_ORDER } from "@/lib/connectors/mutation-contract";

describe("connector type metadata", () => {
  it("labels the sample read type while keeping creation limited to external warehouses", () => {
    const externalConnectorTypes = [
      "POSTGRESQL",
      "BIGQUERY",
      "SNOWFLAKE",
      "DATABRICKS",
      "TRINO",
      "REDSHIFT",
    ];

    expect(CONNECTOR_TYPE_ORDER).toEqual(externalConnectorTypes);
    expect(CONNECTOR_TYPE_META.DUCKDB.label).toBe("Sample data");
    expect(CONNECTOR_TYPE_ORDER).not.toContain("DUCKDB");
  });
});
