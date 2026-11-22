package com.mayshop.gateway.origin;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires CORS and the {@code Origin} check into the gateway. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OriginProperties.class)
public class OriginConfiguration {

  @Bean
  OriginFilter originFilter(OriginProperties properties) {
    return new OriginFilter(properties.webOrigin());
  }
}
