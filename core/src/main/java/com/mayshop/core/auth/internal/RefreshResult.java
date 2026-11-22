package com.mayshop.core.auth.internal;

import java.time.Duration;

/** A new token pair; the raw tokens go only into {@code Set-Cookie}. */
public record RefreshResult(
    String accessToken, Duration accessTokenTtl, String refreshToken, Duration refreshTokenTtl) {}
