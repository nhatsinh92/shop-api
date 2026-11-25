package com.mayshop.core.order.idempotency;

/** What {@link IdempotencyStore#begin} decided for a {@code (user, key)} pair. */
public sealed interface BeginResult {

  /** The key is new: the caller runs the request and calls {@code complete}. */
  record Started() implements BeginResult {}

  /** The same request already completed: answer with the stored status and body. */
  record Replay(int status, String body) implements BeginResult {}

  /** The same request is still running in another transaction: answer 409. */
  record InProgress() implements BeginResult {}

  /** The key was used with a different request: answer 422. */
  record Reused() implements BeginResult {}
}
