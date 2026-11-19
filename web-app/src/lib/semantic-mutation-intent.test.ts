import { describe, expect, it } from "vitest";
import { stableSemanticMutationIntent } from "@/lib/semantic-mutation-intent";

describe("semantic mutation intent idempotency", () => {
  it("reuses the key for the same failed intent and rotates for changed content", () => {
    const originalIntent = stableSemanticMutationIntent(
      null,
      '{"name":"orders"}',
    );
    const retryIntent = stableSemanticMutationIntent(
      originalIntent,
      '{"name":"orders"}',
    );
    const changedIntent = stableSemanticMutationIntent(
      retryIntent,
      '{"name":"renamed_orders"}',
    );

    expect(retryIntent).toBe(originalIntent);
    expect(retryIntent.idempotencyKey).toBe(originalIntent.idempotencyKey);
    expect(changedIntent.idempotencyKey).not.toBe(originalIntent.idempotencyKey);
  });
});
