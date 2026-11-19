function createSemanticMutationIdempotencyKey(): string {
  return crypto.randomUUID();
}

export interface SemanticMutationIntent {
  requestSignature: string;
  idempotencyKey: string;
}

export function stableSemanticMutationIntent(
  previousIntent: SemanticMutationIntent | null,
  requestSignature: string,
): SemanticMutationIntent {
  if (previousIntent?.requestSignature === requestSignature) {
    return previousIntent;
  }
  return {
    requestSignature,
    idempotencyKey: createSemanticMutationIdempotencyKey(),
  };
}
