package com.mayshop.gateway.origin;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** The single web origin the gateway serves credentialed browser calls to. */
@ConfigurationProperties(prefix = "gateway")
public record OriginProperties(String webOrigin) {}
