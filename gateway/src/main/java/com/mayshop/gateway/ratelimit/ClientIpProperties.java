package com.mayshop.gateway.ratelimit;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Proxies whose {@code X-Forwarded-For} the gateway trusts, from {@code GATEWAY_TRUSTED_PROXIES}
 * (comma-separated IPv4 and IPv6 CIDRs, default none). An invalid entry fails startup.
 */
@ConfigurationProperties(prefix = "gateway.client-ip")
public record ClientIpProperties(@DefaultValue List<String> trustedProxies) {

  /** Validates every entry. */
  public ClientIpProperties {
    trustedProxies =
        trustedProxies == null
            ? List.of()
            : trustedProxies.stream().filter(s -> !s.isBlank()).map(String::trim).toList();
    trustedProxies.forEach(Cidr::parse);
  }
}
