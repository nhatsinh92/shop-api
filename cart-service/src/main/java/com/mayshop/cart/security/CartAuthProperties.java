package com.mayshop.cart.security;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Token verification settings (the {@code auth.jwt} block of {@code application.yml}). */
@ConfigurationProperties("auth.jwt")
public record CartAuthProperties(
    String issuer,
    String audience,
    String jwksUri,
    Duration jwksConnectTimeout,
    Duration jwksReadTimeout) {}
