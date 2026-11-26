package com.mayshop.cart.security;

import com.mayshop.cart.web.Problems;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.DispatcherType;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.MappedJwtClaimSetConverter;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Bearer verification against the JWKS of {@code core}, and the caller identity of the cart
 * endpoints. Actuator paths are public at the filter level; an anonymous caller of {@code
 * /api/cart/**} is a guest, which {@link CallerIdentityFilter} decides.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CartAuthProperties.class)
class SecurityConfiguration implements WebMvcConfigurer {

  @Bean
  JwtDecoder accessTokenDecoder(CartAuthProperties properties) {
    SimpleClientHttpRequestFactory http = new SimpleClientHttpRequestFactory();
    http.setConnectTimeout(properties.jwksConnectTimeout());
    http.setReadTimeout(properties.jwksReadTimeout());
    NimbusJwtDecoder decoder =
        NimbusJwtDecoder.withJwkSetUri(properties.jwksUri())
            .jwsAlgorithm(SignatureAlgorithm.RS256)
            .restOperations(new RestTemplate(http))
            .build();
    // core's issuer is a plain name, not a URL: keep the claim a String.
    decoder.setClaimSetConverter(
        MappedJwtClaimSetConverter.withDefaults(
            Map.of("iss", value -> value == null ? null : value.toString())));
    decoder.setJwtValidator(
        new DelegatingOAuth2TokenValidator<>(
            new JwtTimestampValidator(),
            new JwtIssuerValidator(properties.issuer()),
            audience(properties.audience())));
    return decoder;
  }

  private static OAuth2TokenValidator<Jwt> audience(String expected) {
    return jwt -> {
      List<String> audience = jwt.getAudience();
      return audience != null && audience.contains(expected)
          ? OAuth2TokenValidatorResult.success()
          : OAuth2TokenValidatorResult.failure(
              new OAuth2Error("invalid_token", "The audience is not accepted", null));
    };
  }

  @Bean
  SecurityFilterChain apiSecurity(HttpSecurity http, MeterRegistry meters) throws Exception {
    // Not a bean: a Filter bean would also be registered in the servlet chain, before security.
    CallerIdentityFilter identity = new CallerIdentityFilter(meters);
    AuthenticationEntryPoint unauthenticated =
        (request, response, exception) ->
            Problems.write(
                response, HttpStatus.UNAUTHORIZED, "Unauthenticated", "AUTH_UNAUTHENTICATED");
    http.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        // CSRF protection is SameSite=Lax on the cookies plus the Origin check of the gateway.
        .csrf(AbstractHttpConfigurer::disable)
        .formLogin(AbstractHttpConfigurer::disable)
        .httpBasic(AbstractHttpConfigurer::disable)
        .logout(AbstractHttpConfigurer::disable)
        .requestCache(cache -> cache.disable())
        .authorizeHttpRequests(auth ->
            // Actuator is public at the filter level. Which endpoints exist where is decided by the
            // exposure settings: the app port has handlers for health and info only (see
            // management.AppPortActuatorConfiguration), so prometheus there is a 404, not a 401;
            // the management port is internal and never published.
            auth.dispatcherTypeMatchers(DispatcherType.ERROR)
                // The error dispatch of a 404 must not turn into a 401.
                .permitAll()
                .requestMatchers("/actuator", "/actuator/**")
                .permitAll()
                // Guests are allowed here; CallerIdentityFilter rejects the unverifiable.
                .requestMatchers("/api/cart", "/api/cart/**")
                .permitAll()
                .anyRequest()
                .authenticated())
        .oauth2ResourceServer(
            oauth -> oauth.authenticationEntryPoint(unauthenticated).jwt(jwt -> {}))
        .exceptionHandling(e -> e.authenticationEntryPoint(unauthenticated))
        .addFilterAfter(identity, BearerTokenAuthenticationFilter.class);
    return http.build();
  }

  @Override
  public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
    resolvers.add(new CallerIdentityResolver());
  }
}
