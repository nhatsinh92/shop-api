package com.mayshop.core.auth.internal;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Session settings.
 *
 * @param refreshTokenTtl lifetime of a refresh token, also the {@code Max-Age} of its cookie
 * @param refreshGrace how long a just-rotated refresh token is still accepted
 */
@ConfigurationProperties("auth.session")
public record SessionProperties(
    @DefaultValue("14d") Duration refreshTokenTtl, @DefaultValue("10s") Duration refreshGrace) {}
