package com.mayshop.core.auth.jwt;

import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Publishes the public signing keys for the gateway (contract operation {@code getJwks}). */
@RestController
class JwksController {

  private final SigningKeys keys;

  JwksController(SigningKeys keys) {
    this.keys = keys;
  }

  @GetMapping(path = "/.well-known/jwks.json", produces = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<Map<String, List<Map<String, String>>>> jwks() {
    return ResponseEntity.ok()
        .header(HttpHeaders.CACHE_CONTROL, "public, max-age=300")
        .body(Map.of("keys", keys.jwks()));
  }
}
