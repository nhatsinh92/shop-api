package com.mayshop.core.order.web;

import com.mayshop.core.order.internal.OrderPlacement;
import com.mayshop.core.order.internal.OrderPlacement.Placed;
import com.mayshop.core.order.internal.OrderProblemException;
import com.mayshop.core.order.internal.OrderView;
import com.mayshop.core.order.internal.PlaceOrder;
import java.util.UUID;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** {@code placeOrder} and {@code getOrder} of {@code core.yaml}. */
@RestController
class OrderController {

  static final String CACHE_CONTROL = "private, no-store";

  private final OrderPlacement placement;

  OrderController(OrderPlacement placement) {
    this.placement = placement;
  }

  @PostMapping(
      path = "/api/orders",
      consumes = {"application/json", "application/*+json", "*/*"})
  ResponseEntity<String> placeOrder(
      @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestHeader(name = "X-Mock-Payment-Outcome", required = false) String mockOutcome,
      @RequestBody(required = false) String body,
      Authentication authentication) {
    // The key is checked first: before the body and before any database access.
    String key = PlaceOrderParser.key(idempotencyKey);
    PlaceOrder request = PlaceOrderParser.parse(userId(authentication), key, body, mockOutcome);
    Placed placed = placement.place(request);
    ResponseEntity.BodyBuilder response =
        ResponseEntity.status(HttpStatusCode.valueOf(placed.status()))
            .header("Cache-Control", CACHE_CONTROL)
            .contentType(MediaType.APPLICATION_JSON);
    if (placed.replayed()) {
      response.header("Idempotent-Replayed", "true");
    }
    return response.body(placed.body());
  }

  @GetMapping("/api/orders/{id}")
  ResponseEntity<OrderView> getOrder(@PathVariable String id, Authentication authentication) {
    UUID orderId;
    try {
      orderId = UUID.fromString(id);
    } catch (IllegalArgumentException e) {
      throw OrderProblemException.orderNotFound();
    }
    return ResponseEntity.ok()
        .header("Cache-Control", CACHE_CONTROL)
        .body(placement.get(orderId, userId(authentication)));
  }

  private static UUID userId(Authentication authentication) {
    if (authentication instanceof JwtAuthenticationToken jwt) {
      return UUID.fromString(jwt.getToken().getSubject());
    }
    throw new IllegalStateException("An order request needs a JWT authentication");
  }
}
