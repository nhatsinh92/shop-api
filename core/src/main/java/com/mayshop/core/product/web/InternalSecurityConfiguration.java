package com.mayshop.core.product.web;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Chain for {@code /internal/**}, ahead of the auth module's chain (which ends with {@code
 * anyRequest().authenticated()}). It permits every request: internal callers carry no JWT, and the
 * handler itself checks {@code X-Internal-Token} and answers 404 otherwise. The chain only matches
 * {@code /internal/**}; every other path keeps the auth chain.
 */
@Configuration(proxyBeanMethods = false)
class InternalSecurityConfiguration {

  @Bean
  @Order(1)
  SecurityFilterChain internalSecurity(HttpSecurity http) throws Exception {
    http.securityMatcher("/internal/**")
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        // No cookie or session is involved: the shared secret travels in a header.
        .csrf(AbstractHttpConfigurer::disable)
        .formLogin(AbstractHttpConfigurer::disable)
        .httpBasic(AbstractHttpConfigurer::disable)
        .logout(AbstractHttpConfigurer::disable)
        .requestCache(cache -> cache.disable())
        .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
    return http.build();
  }
}
