import type { TableFrame, TablePresentation } from "../../contract/abi";
import { tableCellFormatter, type TableCellText } from "../../table-values";
import { TABLE_BATCH_BYTES, TABLE_BATCH_CELLS, byteLength } from "../limits";
import { ownRecord, tableError, validateCellView, validateRowView } from "../output-boundary";
import type { TableBatch, TableBatchRequest, TableCellOutput, TableWorkerRequest, TableWorkerResponse } from "../worker-protocol";
import { evaluateTableSource } from "./module-source";

const trustedSetTimeout = globalThis.setTimeout.bind(globalThis);

export type PreparedTablePresentation = Readonly<{ frame: TableFrame; presentation: TablePresentation; displayText: TableCellText }>;

export function prepareTablePresentation(source: string, frame: TableFrame, displayFractionDigits: ReadonlyMap<string, number>): PreparedTablePresentation {
  if (!Object.hasOwn(frame.queries, frame.rowQueryName)) throw new Error("Table row query is missing");
  const module = evaluateTableSource(source);
  const result = ownRecord(module.render(harden(frame)), ["row", "cell"]);
  if ([result.row, result.cell].some(callback => callback !== undefined && typeof callback !== "function")) {
    throw new Error("Table presentation must contain only row and cell callbacks");
  }
  return {
    frame,
    presentation: harden(result) as TablePresentation,
    displayText: tableCellFormatter(frame.queries[frame.rowQueryName].columns, frame.locale, displayFractionDigits),
  };
}

export function renderTableBatch({ frame, presentation, displayText }: PreparedTablePresentation, request: TableBatchRequest): TableBatch {
  if (request.rowIndices.length > TABLE_BATCH_CELLS || request.fields.length > TABLE_BATCH_CELLS
    || request.rowIndices.length * request.fields.length > TABLE_BATCH_CELLS
    || new Set(request.rowIndices).size !== request.rowIndices.length || new Set(request.fields).size !== request.fields.length) {
    throw new Error("Table batch exceeds its cell budget or contains duplicate addresses");
  }
  const query = frame.queries[frame.rowQueryName];
  const fields = new Set(query.columns.map(column => column.name));
  if (request.fields.some(field => !fields.has(field))) throw new Error("Unknown Table field");
  const rows: TableBatch["rows"][number][] = [];
  const cells: TableBatch["cells"][number][] = [];
  let bytes = byteLength('{"rows":[],"cells":[]}');
  function budget(value: unknown): void {
    bytes += byteLength(JSON.stringify(value)) + 1;
    if (bytes > TABLE_BATCH_BYTES) throw new Error("Table batch exceeds its output byte budget");
  }
  for (const rowIndex of request.rowIndices) {
    if (!Number.isSafeInteger(rowIndex) || rowIndex < 0 || rowIndex >= query.rows.length) throw new Error("Unknown Table row");
    const row = query.rows[rowIndex];
    let rowResult: TableBatch["rows"][number];
    try { rowResult = { rowIndex, view: validateRowView(presentation.row?.(harden({ row, rowIndex }))) }; }
    catch (error) { rowResult = { rowIndex, error: tableError(error) }; }
    budget(rowResult);
    rows.push(rowResult);
    for (const field of request.fields) {
      let cellResult: TableBatch["cells"][number];
      try {
        let view: TableCellOutput | undefined;
        if (presentation.cell) {
          const text = displayText(field, row[field]);
          view = validateCellView(presentation.cell(harden({ row, rowIndex, field, value: row[field] ?? null, displayText: text })), text);
        }
        cellResult = { rowIndex, field, view };
      } catch (error) { cellResult = { rowIndex, field, error: tableError(error) }; }
      budget(cellResult);
      cells.push(cellResult);
    }
  }
  return { rows, cells };
}

export function startTableWidgetWorkerRuntime(): void {
  let generation = -1;
  let prepared: PreparedTablePresentation | undefined;
  const respondAfterMicrotasks = (response: TableWorkerResponse): void => {
    // Detached source Promise jobs must drain before Host clears the operation deadline.
    trustedSetTimeout(() => self.postMessage(response), 0);
  };
  self.onmessage = (event: MessageEvent<TableWorkerRequest>) => {
    const request = event.data;
    const respond = (response: TableWorkerResponse) => self.postMessage(response);
    try {
      if (request.type === "prepare") {
        generation = request.generation;
        prepared = undefined;
        prepared = prepareTablePresentation(request.moduleSource, request.frame, request.displayFractionDigits);
        respondAfterMicrotasks({ type: "prepared", generation, operationId: request.operationId });
      } else if (request.generation === generation) {
        if (!prepared) throw new Error("Table presentation is not prepared");
        const batch = renderTableBatch(prepared, request.request);
        respondAfterMicrotasks({ type: "batch", generation, operationId: request.operationId, batch });
      }
    } catch (error) {
      respond({ type: "failed", generation: request.generation, operationId: request.operationId, message: tableError(error) });
    }
  };
}
