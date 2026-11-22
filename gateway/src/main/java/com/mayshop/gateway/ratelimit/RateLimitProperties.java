package com.mayshop.gateway.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Rate limits of the gateway. Per-minute limits use a fixed window; the core limit is a token
 * bucket refilled {@code coreRate} tokens per second up to {@code coreBurst}.
 */
@ConfigurationProperties(prefix = "gateway.rate-limit")
public record RateLimitProperties(
    int loginPerEmailPerMinute,
    int loginPerIpPerMinute,
    int refreshPerSessionPerMinute,
    int coreRate,
    int coreBurst) {}
