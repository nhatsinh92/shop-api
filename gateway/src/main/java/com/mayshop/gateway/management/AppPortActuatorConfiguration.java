package com.mayshop.gateway.management;

import java.util.Arrays;
import org.springframework.boot.actuate.endpoint.ApiVersion;
import org.springframework.boot.actuate.endpoint.SecurityContext;
import org.springframework.boot.actuate.endpoint.web.WebServerNamespace;
import org.springframework.boot.actuate.info.InfoEndpoint;
import org.springframework.boot.health.actuate.endpoint.ReactiveHealthEndpointWebExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;

/**
 * Serves {@code health} and {@code info} on the app port. Actuator moves every endpoint to the
 * management port once {@code management.server.port} is set; probes and the demo stack still use
 * the app port for these two, while {@code prometheus} stays on the management port only.
 */
@Configuration(proxyBeanMethods = false)
class AppPortActuatorConfiguration {

  @Bean
  RouterFunction<ServerResponse> appPortActuator(
      ReactiveHealthEndpointWebExtension health, InfoEndpoint info) {
    return RouterFunctions.route()
        .GET("/actuator/health", request -> health(health, new String[0]))
        .GET(
            "/actuator/health/{*path}",
            request ->
                health(
                    health,
                    Arrays.stream(request.pathVariable("path").split("/"))
                        .filter(s -> !s.isEmpty())
                        .toArray(String[]::new)))
        .GET("/actuator/info", request -> ServerResponse.ok().bodyValue(info.info()))
        .build();
  }

  private static reactor.core.publisher.Mono<ServerResponse> health(
      ReactiveHealthEndpointWebExtension health, String[] path) {
    return health
        .health(ApiVersion.LATEST, WebServerNamespace.SERVER, SecurityContext.NONE, path)
        .flatMap(response ->
            ServerResponse.status(HttpStatusCode.valueOf(response.getStatus()))
                .bodyValue(response.getBody()))
        .switchIfEmpty(ServerResponse.notFound().build());
  }
}
