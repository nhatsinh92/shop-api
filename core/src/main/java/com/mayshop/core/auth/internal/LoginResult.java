package com.mayshop.core.auth.internal;

import java.time.Duration;

/** What a successful login produces; the raw tokens go only into {@code Set-Cookie}. */
public record LoginResult(
    UserAccount user,
    String accessToken,
    Duration accessTokenTtl,
    String refreshToken,
    Duration refreshTokenTtl) {}
