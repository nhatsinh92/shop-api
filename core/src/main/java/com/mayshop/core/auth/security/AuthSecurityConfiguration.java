package com.mayshop.core.auth.security;

import com.mayshop.core.auth.internal.UserAccounts;
import com.mayshop.core.auth.jwt.JwtProperties;
import com.mayshop.core.auth.jwt.SigningKeys;
import com.mayshop.core.auth.web.Problems;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Bearer verification in {@code core} (defence in depth behind the gateway). Operations the
 * contract marks {@code security: []} stay public; everything else needs a valid access token.
 */
@Configuration(proxyBeanMethods = false)
class AuthSecurityConfiguration {

  @Bean
  JwtDecoder accessTokenDecoder(SigningKeys keys, JwtProperties properties, UserAccounts users) {
    return AccessTokenDecoder.create(keys, properties, users);
  }

  @Bean
  SecurityFilterChain apiSecurity(HttpSecurity http) throws Exception {
    AuthenticationEntryPoint unauthenticated =
        (request, response, exception) ->
            Problems.write(
                response, HttpStatus.UNAUTHORIZED, "Unauthenticated", "AUTH_UNAUTHENTICATED");
    http.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        // CSRF protection of this API is SameSite=Lax on the cookies plus the Origin check of the
        // gateway (epic auth-gateway, "CORS and CSRF"), so Spring's CSRF token check is off.
        .csrf(AbstractHttpConfigurer::disable)
        .formLogin(AbstractHttpConfigurer::disable)
        .httpBasic(AbstractHttpConfigurer::disable)
        .logout(AbstractHttpConfigurer::disable)
        .requestCache(cache -> cache.disable())
        .authorizeHttpRequests(auth ->
            auth
                // Operations with `security: []` in contracts/openapi/core.yaml.
                .requestMatchers("/actuator/health", "/actuator/health/**")
                .permitAll()
                .requestMatchers("/.well-known/jwks.json")
                .permitAll()
                .requestMatchers(
                    HttpMethod.POST, "/api/auth/login", "/api/auth/refresh", "/api/auth/logout")
                .permitAll()
                // Member price needs a token, so it comes before the public product rules.
                .requestMatchers("/api/products/*/member-price")
                .authenticated()
                .requestMatchers(
                    HttpMethod.GET,
                    "/api/products",
                    "/api/products/*",
                    "/api/products/*/availability",
                    "/api/flash-sales/current",
                    "/api/recommendations")
                .permitAll()
                .anyRequest()
                .authenticated())
        .oauth2ResourceServer(oauth ->
            oauth
                .bearerTokenResolver(new AccessTokenResolver())
                .authenticationEntryPoint(unauthenticated)
                .jwt(Customizer.withDefaults()))
        .exceptionHandling(e -> e.authenticationEntryPoint(unauthenticated));
    return http.build();
  }
}
