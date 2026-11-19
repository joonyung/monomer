import type { TableFrame } from "../contract/abi";
import { TABLE_BATCH_BYTES, TABLE_BATCH_TIMEOUT_MS, TABLE_PREPARE_TIMEOUT_MS, TABLE_SOURCE_BYTES, byteLength } from "./limits";
import { tableError, validateCellOutput, validateRowView } from "./output-boundary";
import { validateStaticSvg } from "./static-svg";
import type { TableBatch, TableBatchRequest, TableWorkerRequest, TableWorkerResponse } from "./worker-protocol";
import { createTableWidgetWorker } from "./worker-factory";
export type { TableBatch, TableBatchRequest } from "./worker-protocol";

type PendingOperation = {
  resolve(value: TableWorkerResponse): void;
  reject(error: Error): void;
  timer: ReturnType<typeof setTimeout>;
};
export type TableWorkerFactory = (generation: number) => Worker;

export class TableWidgetRuntime {
  private worker: Worker | null = null;
  private generation = 0;
  private operationId = 0;
  private prepared = false;
  private pending = new Map<number, PendingOperation>();
  constructor(private readonly workerFactory: TableWorkerFactory = createTableWidgetWorker) {}

  async prepare(moduleSource: string, frame: TableFrame, displayFractionDigits: ReadonlyMap<string, number>): Promise<void> {
    this.dispose();
    if (byteLength(moduleSource) > TABLE_SOURCE_BYTES) throw new Error("Table source exceeds its byte budget");
    const generation = ++this.generation;
    const worker = this.workerFactory(generation);
    this.worker = worker;
    worker.onmessage = (event: MessageEvent<TableWorkerResponse>) => {
      if (this.worker !== worker) return;
      const response = event.data;
      if (response.generation === -1 && response.type === "failed") { this.fail(new Error(response.message)); return; }
      if (response.generation !== generation) return;
      const pending = this.pending.get(response.operationId);
      if (!pending) return;
      if (response.type === "failed") { this.fail(new Error(response.message)); return; }
      clearTimeout(pending.timer);
      this.pending.delete(response.operationId);
      pending.resolve(response);
    };
    worker.onerror = event => {
      if (this.worker === worker) this.fail(new Error(event.message || "Table Worker failed"));
    };
    worker.onmessageerror = () => { if (this.worker === worker) this.fail(new Error("Invalid Table Worker message")); };
    const response = await this.send({ type: "prepare", generation, operationId: ++this.operationId, moduleSource, frame, displayFractionDigits }, TABLE_PREPARE_TIMEOUT_MS);
    if (this.worker !== worker || response.type !== "prepared") throw new Error("Table frame was superseded");
    this.prepared = true;
  }

  async batch(request: TableBatchRequest): Promise<TableBatch> {
    if (!this.prepared) throw new Error("Table presentation is not prepared");
    const worker = this.worker;
    const response = await this.send({ type: "batch", generation: this.generation, operationId: ++this.operationId, request }, TABLE_BATCH_TIMEOUT_MS);
    if (this.worker !== worker) throw new Error("Table frame was superseded");
    if (response.type !== "batch") throw new Error("Invalid Table batch response");
    try {
      if (byteLength(JSON.stringify(response.batch)) > TABLE_BATCH_BYTES) throw new Error("Table batch exceeds its output byte budget");
      return {
        rows: response.batch.rows.map(row => {
          try { return { ...row, view: validateRowView(row.view) }; }
          catch (error) { return { rowIndex: row.rowIndex, error: tableError(error) }; }
        }),
        cells: response.batch.cells.map(cell => {
          try {
            const view = validateCellOutput(cell.view);
            if (view?.graphic) validateStaticSvg(view.graphic.svg);
            return { ...cell, view };
          } catch (error) { return { rowIndex: cell.rowIndex, field: cell.field, error: tableError(error) }; }
        }),
      };
    } catch (error) {
      this.fail(new Error(tableError(error)));
      throw error;
    }
  }

  dispose(): void { this.fail(new Error("Table frame was disposed or superseded")); }

  private fail(error: Error): void {
    this.worker?.terminate();
    this.worker = null;
    this.prepared = false;
    for (const operation of this.pending.values()) { clearTimeout(operation.timer); operation.reject(error); }
    this.pending.clear();
  }

  private send(request: TableWorkerRequest, deadline: number): Promise<TableWorkerResponse> {
    return new Promise((resolve, reject) => {
      if (!this.worker) { reject(new Error("Table Worker is unavailable")); return; }
      const timer = setTimeout(() => this.fail(new Error("Table Worker operation timed out")), deadline);
      this.pending.set(request.operationId, { resolve, reject, timer });
      try { this.worker.postMessage(request); }
      catch (error) { this.fail(new Error(tableError(error))); }
    });
  }
}
