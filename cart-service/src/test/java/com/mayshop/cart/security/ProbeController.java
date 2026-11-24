package com.mayshop.cart.security;

import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Test-only endpoints under {@code /api/cart/**} that exercise identity and error mapping. */
@RestController
class ProbeController {

  record Body(String sku, int quantity) {}

  @GetMapping("/api/cart/probe")
  ResponseEntity<String> probe(CallerIdentity identity) {
    String text =
        switch (identity) {
          case CallerIdentity.User u -> "user:" + u.id() + ":" + u.guestId();
          case CallerIdentity.Guest g -> "guest:" + g.id();
        };
    return ResponseEntity.ok(text);
  }

  @GetMapping("/api/cart/probe/{n}")
  String number(@PathVariable int n) {
    return "n=" + n;
  }

  @PostMapping("/api/cart/probe")
  String body(@RequestBody Body body) {
    return body.sku();
  }

  @GetMapping("/api/cart/redis-down")
  String redisDown() {
    throw new RedisConnectionFailureException("Unable to connect to Redis");
  }
}
