import type { components, operations } from "./generated";

export type ControlOperationId = keyof operations;
export type ControlProblem = components["schemas"]["ProblemDetails"];

type OperationParameters<OperationId extends ControlOperationId> =
  operations[OperationId] extends { parameters: infer Parameters }
    ? Parameters
    : never;

type ParameterSlot<
  OperationId extends ControlOperationId,
  Slot extends "path" | "query" | "header",
> = OperationParameters<OperationId> extends { [Key in Slot]?: infer Value }
  ? Value
  : never;

type OperationRequestBody<OperationId extends ControlOperationId> =
  operations[OperationId] extends { requestBody: infer RequestBody }
    ? RequestBody extends { content: infer Content }
      ? Content extends object
        ? Content[keyof Content]
        : never
      : never
    : never;

type BodyProperty<OperationId extends ControlOperationId> =
  [OperationRequestBody<OperationId>] extends [never]
    ? { body?: never }
    : { body: OperationRequestBody<OperationId> };

export type ControlPathParameters<OperationId extends ControlOperationId> =
  ParameterSlot<OperationId, "path">;

export type ControlQueryParameters<OperationId extends ControlOperationId> =
  ParameterSlot<OperationId, "query">;

export type ControlHeaderParameters<OperationId extends ControlOperationId> =
  ParameterSlot<OperationId, "header">;

export type ControlRequest<OperationId extends ControlOperationId> = {
  path: ControlPathParameters<OperationId>;
  query?: ControlQueryParameters<OperationId>;
  headers?: ControlHeaderParameters<OperationId>;
} & BodyProperty<OperationId>;

type OperationResponses<OperationId extends ControlOperationId> =
  operations[OperationId] extends { responses: infer Responses }
    ? Responses
    : never;

export type ControlResponseStatus<OperationId extends ControlOperationId> =
  Extract<keyof OperationResponses<OperationId>, number>;

type ResponseAt<
  OperationId extends ControlOperationId,
  Status extends ControlResponseStatus<OperationId>,
> = OperationResponses<OperationId> extends infer Responses
  ? Responses extends object
    ? Status extends keyof Responses
      ? Responses[Status]
      : never
    : never
  : never;

type NoDeclaredResponseHeaders = { readonly [HeaderName: string]: never };

type DeclaredResponseHeaders<Response> = Response extends { headers?: infer Headers }
  ? Headers extends object
    ? Headers
    : NoDeclaredResponseHeaders
  : NoDeclaredResponseHeaders;

type ResponseContent<Response> = Response extends { content: infer Content }
  ? Content extends object
    ? Content
    : never
  : Response extends { content?: infer Content }
    ? Content extends object
      ? Content
      : never
    : never;

export interface ControlResponseMetadata {
  cacheControl: string | null;
  eTag: string | null;
  location: string | null;
  referrerPolicy: string | null;
  retryAfter: string | null;
  wwwAuthenticate: string | null;
}

type ContentResponse<
  Status extends number,
  Response,
  Content extends object = ResponseContent<Response>,
> = {
  [ContentType in Extract<keyof Content, string>]: {
    status: Status;
    contentType: ContentType;
    data: Content[ContentType];
    declaredHeaders: DeclaredResponseHeaders<Response>;
    headers: Headers;
    metadata: ControlResponseMetadata;
  };
}[Extract<keyof Content, string>];

type EmptyResponse<Status extends number, Response> = {
  status: Status;
  contentType: null;
  data: undefined;
  declaredHeaders: DeclaredResponseHeaders<Response>;
  headers: Headers;
  metadata: ControlResponseMetadata;
};

type ResponseForStatus<
  OperationId extends ControlOperationId,
  Status extends ControlResponseStatus<OperationId>,
> = [ResponseContent<ResponseAt<OperationId, Status>>] extends [never]
  ? EmptyResponse<Status, ResponseAt<OperationId, Status>>
  : ContentResponse<Status, ResponseAt<OperationId, Status>>;

type GeneratedControlResponse<OperationId extends ControlOperationId> = {
  [Status in ControlResponseStatus<OperationId>]: ResponseForStatus<OperationId, Status>;
}[ControlResponseStatus<OperationId>];

export type ControlResponse<OperationId extends ControlOperationId> = GeneratedControlResponse<OperationId>;

export type ControlProblemResponse<OperationId extends ControlOperationId> = Extract<
  ControlResponse<OperationId>,
  { contentType: "application/problem+json" }
>;

export type ControlSseOperationId = {
  [OperationId in ControlOperationId]: Extract<
    ControlResponse<OperationId>,
    { contentType: "text/event-stream" }
  > extends never
    ? never
    : OperationId;
}[ControlOperationId];

export type ControlSsePayload<OperationId extends ControlSseOperationId> = Extract<
  ControlResponse<OperationId>,
  { contentType: "text/event-stream" }
>["data"];
