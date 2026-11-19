import { CONTROL_API_URL } from "@/lib/auth/config";
import { sessionFetch } from "@/lib/api/client";
import { parseControlProblem } from "./problem";
import type { ControlOperationDefinition, ControlResponseDefinition } from "./operation";
import type {
  ControlOperationId,
  ControlProblemResponse,
  ControlRequest,
  ControlResponse,
  ControlResponseMetadata,
} from "./types";

export interface ControlFetch {
  (input: RequestInfo | URL, init?: RequestInit): Promise<Response>;
}

export interface CreateControlTransportOptions {
  fetchImplementation: ControlFetch;
}

export interface PreparedControlRequest {
  body: string | undefined;
  headers: Headers;
  method: string;
  url: URL;
}

export interface ControlTransport {
  request<OperationId extends ControlOperationId>(
    operation: ControlOperationDefinition<OperationId>,
    request: ControlRequest<OperationId>,
    signal: AbortSignal | undefined,
  ): Promise<ControlResponse<OperationId>>;
}

export class ControlTransportError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "ControlTransportError";
  }
}

export const CONTROL_RESPONSE_UNAVAILABLE_MESSAGE = "Monomer couldn't load this right now. Try again in a moment.";

// The message is user-facing copy; the contract violation stays in `detail` for diagnostics.
export class ControlResponseContractError extends Error {
  readonly detail: string;

  constructor(detail: string) {
    super(CONTROL_RESPONSE_UNAVAILABLE_MESSAGE);
    this.name = "ControlResponseContractError";
    this.detail = detail;
  }
}

export interface ControlResponseRail {
  contentType: string | null;
  definition: ControlResponseDefinition;
}

function appendParameters(url: URL, query: object | undefined): void {
  if (query === undefined) return;
  for (const [name, value] of Object.entries(query)) {
    if (value === undefined || value === null) continue;
    if (Array.isArray(value)) {
      value.forEach((entry) => url.searchParams.append(name, String(entry)));
      continue;
    }
    url.searchParams.set(name, String(value));
  }
}

function appendHeaders(headers: Headers, requestHeaders: object | undefined): void {
  if (requestHeaders === undefined) return;
  for (const [name, value] of Object.entries(requestHeaders)) {
    if (value === undefined || value === null) continue;
    if (Array.isArray(value)) {
      headers.set(name, value.map(String).join(","));
      continue;
    }
    headers.set(name, String(value));
  }
}

function interpolatePath(pathTemplate: string, parameters: object): string {
  if (!pathTemplate.startsWith("/") || pathTemplate.startsWith("//")) {
    throw new ControlTransportError("Control operations must use an absolute Control path.");
  }
  return pathTemplate.replace(/\{([^}]+)\}/g, (_match, name: string) => {
    const parameter = Object.entries(parameters).find(([parameterName]) => parameterName === name);
    if (parameter === undefined || parameter[1] === undefined || parameter[1] === null) {
      throw new ControlTransportError(`Control path parameter is required: ${name}`);
    }
    return encodeURIComponent(String(parameter[1]));
  });
}

function controlResponseMetadata(headers: Headers): ControlResponseMetadata {
  return {
    cacheControl: headers.get("Cache-Control"),
    eTag: headers.get("ETag"),
    location: headers.get("Location"),
    referrerPolicy: headers.get("Referrer-Policy"),
    retryAfter: headers.get("Retry-After"),
    wwwAuthenticate: headers.get("WWW-Authenticate"),
  };
}

// HTTP forbids a body on these statuses, yet Chromium still exposes an empty body stream for them.
const NULL_BODY_STATUSES: ReadonlySet<number> = new Set([204, 205, 304]);

export function controlContentType(headers: Headers): string | null {
  const contentType = headers.get("Content-Type");
  return contentType === null ? null : contentType.split(";", 1)[0]?.trim().toLowerCase() ?? null;
}

function responseDefinitionFor<OperationId extends ControlOperationId>(
  operation: ControlOperationDefinition<OperationId>,
  responseStatus: number,
): ControlResponseDefinition {
  const definition = operation.responses.find((candidate) => candidate.status === responseStatus);
  if (definition === undefined) {
    throw new ControlResponseContractError(
      `Control response status ${responseStatus} is not declared for ${operation.operationId}.`,
    );
  }
  return definition;
}

export function assertControlResponseRail<OperationId extends ControlOperationId>(
  operation: ControlOperationDefinition<OperationId>,
  response: Response,
): ControlResponseRail {
  const definition = responseDefinitionFor(operation, response.status);
  for (const headerName of definition.requiredHeaders) {
    if (!response.headers.has(headerName)) {
      throw new ControlResponseContractError(
        `Control response ${response.status} for ${operation.operationId} is missing required ${headerName}.`,
      );
    }
  }
  const contentType = controlContentType(response.headers);
  if (definition.contentTypes.length === 0) {
    if (contentType !== null || (response.body !== null && !NULL_BODY_STATUSES.has(response.status))) {
      throw new ControlResponseContractError(
        `Control response ${response.status} for ${operation.operationId} must not include a body.`,
      );
    }
    return { contentType, definition };
  }
  if (contentType === null || !definition.contentTypes.includes(contentType)) {
    throw new ControlResponseContractError(
      `Control response ${response.status} for ${operation.operationId} has undeclared content type ${contentType ?? "<none>"}.`,
    );
  }
  if (response.body === null) {
    throw new ControlResponseContractError(
      `Control response ${response.status} for ${operation.operationId} is missing its declared body.`,
    );
  }
  return { contentType, definition };
}

async function parseControlJsonBody<OperationId extends ControlOperationId>(
  operation: ControlOperationDefinition<OperationId>,
  response: Response,
): Promise<unknown> {
  try {
    return await response.json();
  } catch {
    throw new ControlResponseContractError(
      `Control response ${response.status} for ${operation.operationId} has invalid JSON.`,
    );
  }
}

function assertSessionOnlyHeaders(headers: Headers): void {
  if (headers.has("Authorization")) {
    throw new ControlTransportError("Control transport does not accept bearer authorization headers.");
  }
}

export function prepareControlRequest<OperationId extends ControlOperationId>(
  operation: ControlOperationDefinition<OperationId>,
  request: ControlRequest<OperationId>,
  accept: string,
): PreparedControlRequest {
  const url = new URL(interpolatePath(operation.pathTemplate, request.path as object), CONTROL_API_URL);
  appendParameters(url, request.query as object | undefined);
  const headers = new Headers({ Accept: accept });
  appendHeaders(headers, request.headers as object | undefined);
  assertSessionOnlyHeaders(headers);
  const body = request.body === undefined ? undefined : JSON.stringify(request.body);
  if (body !== undefined) headers.set("Content-Type", "application/json");
  return { body, headers, method: operation.method, url };
}

export async function readControlResponse<OperationId extends ControlOperationId>(
  operation: ControlOperationDefinition<OperationId>,
  response: Response,
): Promise<ControlResponse<OperationId>> {
  const rail = assertControlResponseRail(operation, response);
  let data: unknown;
  if (rail.definition.contentTypes.length === 0) {
    data = undefined;
  } else if (rail.contentType === "application/problem+json") {
    data = parseControlProblem(await parseControlJsonBody(operation, response));
  } else if (rail.contentType === "application/json") {
    data = await parseControlJsonBody(operation, response);
  } else if (rail.contentType === "text/event-stream") {
    throw new ControlResponseContractError(
      `Control event stream ${operation.operationId} must be opened through openControlSse.`,
    );
  } else {
    data = await response.text();
  }
  const typedResponse = {
    status: response.status,
    contentType: rail.contentType,
    data,
    declaredHeaders: Object.fromEntries(
      rail.definition.requiredHeaders.map((headerName) => [headerName, response.headers.get(headerName)]),
    ),
    headers: response.headers,
    metadata: controlResponseMetadata(response.headers),
  };
  return typedResponse as ControlResponse<OperationId>;
}

export function isControlProblemResponse<OperationId extends ControlOperationId>(
  response: ControlResponse<OperationId>,
): response is ControlProblemResponse<OperationId> {
  return response.contentType === "application/problem+json";
}

export function retryAfterMilliseconds(retryAfter: string | null, nowMilliseconds: number): number | null {
  if (retryAfter === null) return null;
  if (/^[0-9]+$/.test(retryAfter)) {
    return Number(retryAfter) * 1_000;
  }
  const retryDate = Date.parse(retryAfter);
  return Number.isNaN(retryDate) ? null : Math.max(0, retryDate - nowMilliseconds);
}

export function createControlTransport(
  options: CreateControlTransportOptions,
): ControlTransport {
  return {
    async request<OperationId extends ControlOperationId>(
      operation: ControlOperationDefinition<OperationId>,
      request: ControlRequest<OperationId>,
      signal: AbortSignal | undefined,
    ): Promise<ControlResponse<OperationId>> {
      const preparedRequest = prepareControlRequest(operation, request, "application/json, application/problem+json");
      const response = await options.fetchImplementation(preparedRequest.url, {
        method: preparedRequest.method,
        body: preparedRequest.body,
        cache: "no-store",
        credentials: "include",
        headers: preparedRequest.headers,
        signal,
      });
      return readControlResponse(operation, response);
    },
  };
}

export function createBrowserControlTransport(): ControlTransport {
  return createControlTransport({
    fetchImplementation: sessionFetch,
  });
}
