package com.mayshop.core.auth.internal;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Session settings.
 *
 * @param refreshTokenTtl lifetime of a refresh token, also the {@code Max-Age} of its cookie
 */
@ConfigurationProperties("auth.session")
public record SessionProperties(@DefaultValue("14d") Duration refreshTokenTtl) {}
