package com.mayshop.core.auth.jwt;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Access-token settings, shared in meaning with the gateway (it checks {@code iss} and {@code
 * aud}).
 *
 * @param privateKey PKCS#8 RSA private key in PEM form; blank means an ephemeral key at startup
 * @param previousPublicKey optional X.509 public key in PEM form, published during key rotation
 * @param issuer value of the {@code iss} claim
 * @param audience value of the {@code aud} claim
 * @param accessTokenTtl lifetime of an access token
 */
@ConfigurationProperties("auth.jwt")
public record JwtProperties(
    String privateKey,
    String previousPublicKey,
    @DefaultValue("mayshop-core") String issuer,
    @DefaultValue("mayshop") String audience,
    @DefaultValue("10m") Duration accessTokenTtl) {}
