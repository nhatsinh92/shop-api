package com.mayshop.cart.security;

import org.jspecify.annotations.Nullable;

/**
 * Who is calling a cart endpoint. A request is a verified user, or a guest; an unverifiable caller
 * never gets this far (the security filters answer 401).
 */
public sealed interface CallerIdentity {

  /**
   * A signed-in user whose Bearer token was verified. {@code guestId} is the valid {@code
   * guest_cart} cookie of the request, if any (the merge endpoint needs it).
   */
  record User(String id, @Nullable String guestId) implements CallerIdentity {}

  /** A guest; {@code id} is null when the request has no valid {@code guest_cart} cookie. */
  record Guest(@Nullable String id) implements CallerIdentity {}
}
