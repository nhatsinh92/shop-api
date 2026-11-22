package com.mayshop.gateway.auth;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/** Wires access-token verification into the gateway. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GatewayAuthProperties.class)
public class GatewayAuthConfiguration {

  @Bean
  JwksKeySource jwksKeySource(GatewayAuthProperties properties) {
    return new JwksKeySource(WebClient.create(), properties, Clock.systemUTC());
  }

  @Bean
  AccessTokenVerifier accessTokenVerifier(JwksKeySource keys, GatewayAuthProperties properties) {
    return new AccessTokenVerifier(keys, properties, Clock.systemUTC());
  }

  @Bean
  AccessCookieFilter accessCookieFilter(AccessTokenVerifier verifier, MeterRegistry meters) {
    return new AccessCookieFilter(verifier, meters);
  }
}
