package com.mayshop.gateway.ratelimit;

import reactor.core.publisher.Mono;

/** Decides whether one more request for a key is allowed. */
public interface RateLimiter {

  /** Outcome of a check; {@code retryAfterSeconds} is at least 1 when the request is denied. */
  record Decision(boolean allowed, long retryAfterSeconds) {
    /** The request may proceed. */
    public static Decision permit() {
      return new Decision(true, 0);
    }

    /** The request is denied for at least the given seconds. */
    public static Decision deny(long retryAfterSeconds) {
      return new Decision(false, Math.max(1, retryAfterSeconds));
    }
  }

  /** Counts a request in a fixed window of 60 seconds, allowing {@code limit} per window. */
  Mono<Decision> perMinute(String key, int limit);

  /** Takes a token from a bucket refilled {@code rate} per second up to {@code burst}. */
  Mono<Decision> bucket(String key, int rate, int burst);
}
