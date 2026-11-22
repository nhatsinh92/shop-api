package com.mayshop.gateway.support;

import com.mayshop.gateway.ratelimit.RateLimiter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import reactor.core.publisher.Mono;

/** Counts requests per key without refilling; enough to test which key a request is counted on. */
public final class InMemoryRateLimiter implements RateLimiter {

  private final Map<String, AtomicInteger> counts = new ConcurrentHashMap<>();

  @Override
  public Mono<Decision> perMinute(String key, int limit) {
    return Mono.just(check(key, limit));
  }

  @Override
  public Mono<Decision> bucket(String key, int rate, int burst) {
    return Mono.just(check(key, burst));
  }

  /** Number of requests counted on keys starting with the prefix. */
  public int keysStartingWith(String prefix) {
    return (int) counts.keySet().stream().filter(k -> k.startsWith(prefix)).count();
  }

  public void reset() {
    counts.clear();
  }

  private Decision check(String key, int limit) {
    int n = counts.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
    return n <= limit ? Decision.permit() : Decision.deny(30);
  }
}
