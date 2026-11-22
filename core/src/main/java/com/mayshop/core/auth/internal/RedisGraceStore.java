package com.mayshop.core.auth.internal;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
class RedisGraceStore implements GraceStore {

  static final String PREFIX = "auth:grace:";

  private final StringRedisTemplate redis;

  RedisGraceStore(StringRedisTemplate redis) {
    this.redis = redis;
  }

  @Override
  public void remember(String oldHash, UUID sessionId, Duration ttl) {
    redis.opsForValue().set(PREFIX + oldHash, sessionId.toString(), ttl);
  }

  @Override
  public Optional<UUID> find(String oldHash) {
    String value = redis.opsForValue().get(PREFIX + oldHash);
    return Optional.ofNullable(value).map(UUID::fromString);
  }
}
