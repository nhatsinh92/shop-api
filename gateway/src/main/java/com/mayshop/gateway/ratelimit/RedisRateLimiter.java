package com.mayshop.gateway.ratelimit;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import reactor.core.publisher.Mono;

/**
 * Redis-backed limiter (Lua scripts, one round trip per check). It fails open: when Redis cannot be
 * reached the request is allowed and a WARN is logged, so a Redis outage never takes the shop down.
 * The error is never silent.
 */
public class RedisRateLimiter implements RateLimiter {

  private static final Logger LOG = LoggerFactory.getLogger(RedisRateLimiter.class);

  private final ReactiveStringRedisTemplate redis;
  private final RedisScript<String> window;
  private final RedisScript<String> bucket;

  /** Creates the limiter with the two Lua scripts (each answers {@code "first:second"}). */
  public RedisRateLimiter(
      ReactiveStringRedisTemplate redis, RedisScript<String> window, RedisScript<String> bucket) {
    this.redis = redis;
    this.window = window;
    this.bucket = bucket;
  }

  @Override
  public Mono<Decision> perMinute(String key, int limit) {
    return redis
        .execute(window, List.of("rl:w:" + key), List.of("60"))
        .next()
        .map(result -> {
          String[] parts = result.split(":");
          return Long.parseLong(parts[0]) <= limit
              ? Decision.permit()
              : Decision.deny(Long.parseLong(parts[1]));
        })
        .onErrorResume(this::failOpen);
  }

  @Override
  public Mono<Decision> bucket(String key, int rate, int burst) {
    return redis
        .execute(
            bucket, List.of("rl:b:" + key), List.of(String.valueOf(rate), String.valueOf(burst)))
        .next()
        .map(result -> {
          String[] parts = result.split(":");
          return "1".equals(parts[0]) ? Decision.permit() : Decision.deny(Long.parseLong(parts[1]));
        })
        .onErrorResume(this::failOpen);
  }

  private Mono<Decision> failOpen(Throwable error) {
    LOG.warn("Rate limit skipped, Redis unavailable: {}", error.toString());
    return Mono.just(Decision.permit());
  }
}
