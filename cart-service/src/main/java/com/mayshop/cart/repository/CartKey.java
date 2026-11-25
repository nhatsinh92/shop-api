package com.mayshop.cart.repository;

import java.time.Duration;

/** The Redis key of one cart and how long it lives after its last write. */
public record CartKey(String redisKey, Duration ttl) {

  /** Lifetime of a signed-in user's cart after its last write. */
  public static final Duration USER_TTL = Duration.ofDays(90);

  /** Lifetime of a guest cart after its last write; also the age after which its snapshot goes. */
  public static final Duration GUEST_TTL = Duration.ofDays(30);

  public static CartKey forUser(String userId) {
    return new CartKey("cart:" + userId, USER_TTL);
  }

  public static CartKey forGuest(String guestId) {
    return new CartKey("cart:guest:" + guestId, GUEST_TTL);
  }
}
