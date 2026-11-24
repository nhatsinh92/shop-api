package com.mayshop.cart.repository;

import java.time.Duration;

/** The Redis key of one cart and how long it lives after its last write. */
public record CartKey(String redisKey, Duration ttl) {

  static final Duration USER_TTL = Duration.ofDays(90);
  static final Duration GUEST_TTL = Duration.ofDays(30);

  public static CartKey forUser(String userId) {
    return new CartKey("cart:" + userId, USER_TTL);
  }

  public static CartKey forGuest(String guestId) {
    return new CartKey("cart:guest:" + guestId, GUEST_TTL);
  }
}
