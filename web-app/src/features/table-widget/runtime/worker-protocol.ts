import type { CellView, RowView, TableFrame } from "../contract/abi";

export type TableBatchRequest = Readonly<{ rowIndices: readonly number[]; fields: readonly string[] }>;
// A returned text equal to the cell's displayText crosses as hostText, and the Host renders its own text.
export type TableCellOutput = Readonly<Omit<CellView, "text"> & ({ text: string } | { hostText: true })>;
export type TableBatch = Readonly<{
  rows: readonly Readonly<{ rowIndex: number; view?: RowView; error?: string }>[];
  cells: readonly Readonly<{ rowIndex: number; field: string; view?: TableCellOutput; error?: string }>[];
}>;
export type TableWorkerRequest =
  | { type: "prepare"; generation: number; operationId: number; moduleSource: string; frame: TableFrame; displayFractionDigits: ReadonlyMap<string, number> }
  | { type: "batch"; generation: number; operationId: number; request: TableBatchRequest };
export type TableWorkerResponse =
  | { type: "prepared"; generation: number; operationId: number }
  | { type: "batch"; generation: number; operationId: number; batch: TableBatch }
  | { type: "failed"; generation: number; operationId: number; message: string };
