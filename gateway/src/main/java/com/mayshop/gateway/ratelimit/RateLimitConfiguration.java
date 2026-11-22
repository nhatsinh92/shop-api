package com.mayshop.gateway.ratelimit;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import tools.jackson.databind.json.JsonMapper;

/** Wires the Redis rate limits into the gateway. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RateLimitProperties.class)
public class RateLimitConfiguration {

  @Bean
  RateLimiter gatewayRateLimiter(ReactiveStringRedisTemplate redis) {
    return new RedisRateLimiter(redis, script("window.lua"), script("bucket.lua"));
  }

  @Bean
  RateLimitFilter rateLimitFilter(RateLimiter limiter, RateLimitProperties properties) {
    return new RateLimitFilter(limiter, properties, JsonMapper.builder().build());
  }

  private static RedisScript<String> script(String name) {
    return RedisScript.of(new ClassPathResource("ratelimit/" + name), String.class);
  }
}
