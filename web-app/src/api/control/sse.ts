import type { ControlOperationDefinition } from "./operation";
import {
  assertControlResponseRail,
  type ControlFetch,
  prepareControlRequest,
  readControlResponse,
} from "./transport";
import type {
  ControlRequest,
  ControlResponse,
  ControlSseOperationId,
  ControlSsePayload,
} from "./types";

export interface ControlSseEvent<OperationId extends ControlSseOperationId> {
  data: ControlSsePayload<OperationId>;
  event: string;
  id: string | null;
}

export interface ControlSseHandlers<OperationId extends ControlSseOperationId> {
  onEvent: (event: ControlSseEvent<OperationId>) => void;
}

export interface OpenControlSseOptions<OperationId extends ControlSseOperationId> {
  fetchImplementation: ControlFetch;
  handlers: ControlSseHandlers<OperationId>;
  operation: ControlOperationDefinition<OperationId>;
  request: ControlRequest<OperationId>;
  signal: AbortSignal;
}

export class ControlSseResponseError<OperationId extends ControlSseOperationId> extends Error {
  readonly response: ControlResponse<OperationId>;

  constructor(response: ControlResponse<OperationId>) {
    super(`Control event stream failed with ${response.status}.`);
    this.name = "ControlSseResponseError";
    this.response = response;
  }
}

export class ControlSseParseError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "ControlSseParseError";
  }
}

interface ParsedControlSseFrame {
  data: string;
  event: string;
  id: string | null;
}

export function parseControlSseFrame(frameText: string): ParsedControlSseFrame | null {
  const dataLines: string[] = [];
  let event = "message";
  let id: string | null = null;
  for (const line of frameText.split(/\r\n|\r|\n/)) {
    if (line.length === 0 || line.startsWith(":")) continue;
    const separatorIndex = line.indexOf(":");
    const field = separatorIndex === -1 ? line : line.slice(0, separatorIndex);
    const rawValue = separatorIndex === -1 ? "" : line.slice(separatorIndex + 1);
    const value = rawValue.startsWith(" ") ? rawValue.slice(1) : rawValue;
    if (field === "data") dataLines.push(value);
    if (field === "event") event = value;
    if (field === "id") id = value;
  }
  return dataLines.length === 0 ? null : { data: dataLines.join("\n"), event, id };
}

function emitFrames<OperationId extends ControlSseOperationId>(
  incomingText: string,
  state: { buffer: string },
  handlers: ControlSseHandlers<OperationId>,
): void {
  state.buffer += incomingText;
  const separator = /\r\n\r\n|\r\r|\n\n/;
  let match = separator.exec(state.buffer);
  while (match !== null) {
    const frame = parseControlSseFrame(state.buffer.slice(0, match.index));
    state.buffer = state.buffer.slice(match.index + match[0].length);
    if (frame !== null) {
      let data: unknown;
      try {
        data = JSON.parse(frame.data);
      } catch {
        throw new ControlSseParseError("Control event stream emitted invalid JSON data.");
      }
      handlers.onEvent({ ...frame, data: data as ControlSsePayload<OperationId> });
    }
    match = separator.exec(state.buffer);
  }
}

export async function openControlSse<OperationId extends ControlSseOperationId>(
  options: OpenControlSseOptions<OperationId>,
): Promise<void> {
  const preparedRequest = prepareControlRequest(
    options.operation,
    options.request,
    "text/event-stream, application/problem+json",
  );
  let response: Response;
  try {
    response = await options.fetchImplementation(preparedRequest.url, {
      method: preparedRequest.method,
      cache: "no-store",
      credentials: "include",
      headers: preparedRequest.headers,
      signal: options.signal,
    });
  } catch (error) {
    if (options.signal.aborted) return;
    throw error;
  }
  if (!response.ok) {
    throw new ControlSseResponseError(await readControlResponse(options.operation, response));
  }
  const rail = assertControlResponseRail(options.operation, response);
  if (rail.contentType !== "text/event-stream" || response.body === null) {
    throw new ControlSseParseError(`Control event stream ${options.operation.operationId} did not return text/event-stream.`);
  }

  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  const state = { buffer: "" };
  try {
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      emitFrames(decoder.decode(value, { stream: true }), state, options.handlers);
    }
    emitFrames(decoder.decode(), state, options.handlers);
  } catch (error) {
    if (options.signal.aborted) return;
    await reader.cancel(error).catch(() => undefined);
    throw error;
  }
}
