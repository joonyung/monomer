import { apiFetch, ApiError } from "./client";

export interface GraphQLError {
  message: string;
  path?: (string | number)[];
  extensions?: Record<string, unknown>;
}

export class GraphQLRequestError extends Error {
  readonly errors: GraphQLError[];

  constructor(errors: GraphQLError[]) {
    super(errors.map((e) => e.message).join("; "));
    this.name = "GraphQLRequestError";
    this.errors = errors;
  }
}

export interface GraphQLRequestOptions {
  endpoint: string;
  query: string;
  variables?: Record<string, unknown>;
  signal?: AbortSignal;
  skipRefresh?: boolean;
}

interface GraphQLResponse<T> {
  data?: T;
  errors?: GraphQLError[];
}

export async function graphqlRequest<T>(opts: GraphQLRequestOptions): Promise<T> {
  const res = await apiFetch(opts.endpoint, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ query: opts.query, variables: opts.variables ?? {} }),
    signal: opts.signal,
    skipRefresh: opts.skipRefresh,
  });
  const json = (await res.json()) as GraphQLResponse<T>;
  if (json.errors && json.errors.length > 0) {
    throw new GraphQLRequestError(json.errors);
  }
  if (!json.data) {
    throw new ApiError("GraphQL response missing data", 200, "");
  }
  return json.data;
}
