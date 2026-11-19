import { useEffect, useMemo, useRef, useState } from "react";
import type { DashboardJsonObject, DashboardWidget } from "@/api/dashboard";
import type { NamedDashboardResult } from "@/features/dashboard/dashboard-result";
import type { TableFrame, TableValue } from "@/features/table-widget/contract/abi";
import { TABLE_BATCH_CELLS } from "@/features/table-widget/runtime/limits";
import { TableWidgetRuntime } from "@/features/table-widget/runtime/table-widget-runtime";
import type { TableBatch, TableBatchRequest } from "@/features/table-widget/runtime/worker-protocol";
import { useDashboardWidgetEnvironment } from "./DashboardWidgetEnvironment";

type TableWidget = Extract<DashboardWidget, { type: "TABLE" }>;
type TableInput = {
  source: string;
  frame: TableFrame;
  displayFractionDigits: ReadonlyMap<string, number>;
  queryRunIds: Record<string, string | undefined>;
  missing: string | undefined;
};
// The Host formats cells with this locale and these digits. The locale is also the frame locale, and the Worker
// formats each displayText from both.
export type TableSourceDisplay = Readonly<{ locale: string; fractionDigits: ReadonlyMap<string, number> }>;

function tableValuesEqual(left: TableValue, right: TableValue): boolean {
  if (left === right) return true;
  if (left === null || right === null || typeof left !== "object" || typeof right !== "object") return false;
  if (Array.isArray(left) || Array.isArray(right)) {
    return Array.isArray(left) && Array.isArray(right) && left.length === right.length && left.every((value, index) => tableValuesEqual(value, right[index]));
  }
  const leftEntries = Object.entries(left);
  const rightRecord = right as Record<string, TableValue>;
  return leftEntries.length === Object.keys(right).length && leftEntries.every(([name, value]) => Object.hasOwn(rightRecord, name) && tableValuesEqual(value, rightRecord[name]));
}

function fractionDigitsEqual(left: ReadonlyMap<string, number>, right: ReadonlyMap<string, number>): boolean {
  return left === right || left.size === right.size && [...left].every(([field, digits]) => right.get(field) === digits);
}

export function tableInputsEqual(left: TableInput | null, right: TableInput | null): boolean {
  if (left === right) return true;
  if (!left || !right || left.source !== right.source || left.missing !== right.missing) return false;
  if (!fractionDigitsEqual(left.displayFractionDigits, right.displayFractionDigits)) return false;
  if (left.frame.locale !== right.frame.locale || left.frame.rowQueryName !== right.frame.rowQueryName) return false;
  const names = Object.keys(left.frame.queries);
  return names.length === Object.keys(right.frame.queries).length && names.every((name) => {
    const query = right.frame.queries[name];
    return query && left.queryRunIds[name] === right.queryRunIds[name]
      && left.frame.queries[name].columns === query.columns && left.frame.queries[name].rows === query.rows;
  }) && tableValuesEqual(left.frame.parameters, right.frame.parameters) && tableValuesEqual(left.frame.variables, right.frame.variables);
}

function tableValue(value: unknown): TableValue {
  if (value === null || typeof value === "string" || typeof value === "number" || typeof value === "boolean") return value;
  if (Array.isArray(value)) return value.map(tableValue);
  if (typeof value === "object") return Object.fromEntries(Object.entries(value).map(([key, nested]) => [key, tableValue(nested)]));
  return null;
}

// Runs the TABLE source module in its Worker and renders the requested original addresses in batches
// within the Worker's cell budget. Late output for a replaced frame or request list is discarded.
export function useTableSourcePresentation(
  widget: TableWidget,
  results: Record<string, NamedDashboardResult>,
  parameters: DashboardJsonObject,
  variables: DashboardJsonObject,
  requests: readonly TableBatchRequest[],
  display: TableSourceDisplay,
): { errorMessage: string | undefined; batch: TableBatch | undefined } {
  const { tableWorkerFactory } = useDashboardWidgetEnvironment();
  const candidateInput = useMemo<TableInput | null>(() => {
    if (!widget.module) return null;
    const names = [widget.queryName, ...widget.module.inputs.queries];
    const missing = names.find((name) => !results[name]);
    const frame: TableFrame = {
      rowQueryName: widget.queryName,
      queries: Object.fromEntries(names.filter((name) => results[name]).map((name) => [name, { columns: results[name].columns, rows: results[name].rows }])),
      parameters: Object.fromEntries(widget.module.inputs.parameters.map((name) => [name, tableValue(parameters[name] ?? null)])),
      variables: Object.fromEntries(widget.module.inputs.variables.map((name) => [name, tableValue(variables[name] ?? null)])),
      locale: display.locale,
    };
    return {
      source: widget.module.source, frame, displayFractionDigits: display.fractionDigits, missing,
      queryRunIds: Object.fromEntries(names.map((name) => [name, results[name]?.queryRunId])),
    };
  }, [widget.module, widget.queryName, results, parameters, variables, display]);
  const inputRef = useRef(candidateInput);
  if (!tableInputsEqual(inputRef.current, candidateInput)) inputRef.current = candidateInput;
  const input = inputRef.current;
  const [prepared, setPrepared] = useState<{ input: typeof input; runtime: TableWidgetRuntime; queue: Promise<void> } | null>(null);
  const [failure, setFailure] = useState<{ input: typeof input; message: string } | null>(null);
  const [presentation, setPresentation] = useState<{ input: typeof input; requests: readonly TableBatchRequest[]; batch: TableBatch } | null>(null);

  useEffect(() => {
    if (!input || input.missing) return;
    let active = true;
    const runtime = new TableWidgetRuntime(tableWorkerFactory);
    void runtime.prepare(input.source, input.frame, input.displayFractionDigits).then(() => {
      if (active) setPrepared({ input, runtime, queue: Promise.resolve() });
    }).catch((error: unknown) => {
      if (active) setFailure({ input, message: error instanceof Error ? error.message : "Table Worker error" });
    });
    return () => { active = false; runtime.dispose(); };
  }, [input, tableWorkerFactory]);

  useEffect(() => {
    if (!input || prepared?.input !== input || failure?.input === input) return;
    let active = true;
    const renderBatch = async () => {
      const rows: TableBatch["rows"][number][] = [];
      const cells: TableBatch["cells"][number][] = [];
      for (const request of requests) {
        for (let fieldOffset = 0; fieldOffset < request.fields.length; fieldOffset += TABLE_BATCH_CELLS) {
          const batchFields = request.fields.slice(fieldOffset, fieldOffset + TABLE_BATCH_CELLS);
          const rowsPerBatch = Math.max(1, Math.floor(TABLE_BATCH_CELLS / batchFields.length));
          for (let offset = 0; offset < request.rowIndices.length; offset += rowsPerBatch) {
            if (!active) return;
            const batch = await prepared.runtime.batch({ rowIndices: request.rowIndices.slice(offset, offset + rowsPerBatch), fields: batchFields });
            rows.push(...batch.rows);
            cells.push(...batch.cells);
          }
        }
      }
      if (active) setPresentation({ input, requests, batch: { rows, cells } });
    };
    prepared.queue = prepared.queue.then(renderBatch).catch((error: unknown) => {
      if (active) setFailure({ input, message: error instanceof Error ? error.message : "Table Worker error" });
    });
    return () => { active = false; };
  }, [input, prepared, failure, requests]);

  const errorMessage = input?.missing ? `No result for query '${input.missing}'.` : failure?.input === input ? failure?.message : undefined;
  const batch = !errorMessage && presentation?.input === input && presentation?.requests === requests ? presentation?.batch : undefined;
  return { errorMessage, batch };
}
