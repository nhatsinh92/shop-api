package com.mayshop.core.flags.web;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

/**
 * Chain for {@code GET /api/flags}, ahead of the auth module's chain. The endpoint is public
 * ({@code security: []} in the contract) and no bearer token filter is installed here, so a request
 * with an invalid token is answered like one without a token.
 */
@Configuration(proxyBeanMethods = false)
class FlagsSecurityConfiguration {

  @Bean
  @Order(2)
  SecurityFilterChain flagsSecurity(HttpSecurity http) throws Exception {
    http.securityMatcher(
            PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.GET, "/api/flags"))
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .csrf(AbstractHttpConfigurer::disable)
        .formLogin(AbstractHttpConfigurer::disable)
        .httpBasic(AbstractHttpConfigurer::disable)
        .logout(AbstractHttpConfigurer::disable)
        .requestCache(cache -> cache.disable())
        .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
    return http.build();
  }
}
