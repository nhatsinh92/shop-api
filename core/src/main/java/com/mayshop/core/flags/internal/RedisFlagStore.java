package com.mayshop.core.flags.internal;

import java.util.HashMap;
import java.util.Map;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
class RedisFlagStore implements FlagStore {

  static final String HASH = "flags";

  private final StringRedisTemplate redis;

  RedisFlagStore(StringRedisTemplate redis) {
    this.redis = redis;
  }

  @Override
  public Map<String, String> read() {
    Map<String, String> result = new HashMap<>();
    redis.<String, String>opsForHash().entries(HASH).forEach(result::put);
    return result;
  }
}
