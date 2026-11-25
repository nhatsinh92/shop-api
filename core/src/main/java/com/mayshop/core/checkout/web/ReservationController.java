package com.mayshop.core.checkout.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.mayshop.core.checkout.Reservation;
import com.mayshop.core.checkout.Reservations;
import com.mayshop.core.product.MemberTier;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** {@code createCheckoutReservation} and {@code getCheckoutReservation} of {@code core.yaml}. */
@RestController
class ReservationController {

  static final String CACHE_CONTROL = "private, no-store";

  private final Reservations reservations;

  ReservationController(Reservations reservations) {
    this.reservations = reservations;
  }

  @PostMapping(
      path = "/api/checkout/reservations",
      consumes = {"application/json", "application/*+json", "*/*"})
  ResponseEntity<Body> createCheckoutReservation(
      @RequestBody(required = false) String body, Authentication authentication) {
    var lines = ReservationRequestParser.parse(body);
    Reservation created =
        reservations.create(userId(authentication), tierOf(authentication), lines);
    return ResponseEntity.status(HttpStatus.CREATED)
        .header("Cache-Control", CACHE_CONTROL)
        .body(Body.of(created));
  }

  @GetMapping("/api/checkout/reservations/{id}")
  ResponseEntity<Body> getCheckoutReservation(
      @PathVariable String id, Authentication authentication) {
    UUID reservationId = parseId(id);
    Reservation found =
        reservations
            .find(reservationId, userId(authentication))
            .orElseThrow(ReservationNotFoundException::new);
    return ResponseEntity.ok().header("Cache-Control", CACHE_CONTROL).body(Body.of(found));
  }

  /** An id that is not a UUID cannot exist: it is the same 404 as an unknown one. */
  private static UUID parseId(String id) {
    try {
      return UUID.fromString(id);
    } catch (IllegalArgumentException e) {
      throw new ReservationNotFoundException();
    }
  }

  private static UUID userId(Authentication authentication) {
    return UUID.fromString(jwt(authentication).getToken().getSubject());
  }

  private static MemberTier tierOf(Authentication authentication) {
    return MemberTier.fromClaim(jwt(authentication).getToken().getClaim("tier"));
  }

  private static JwtAuthenticationToken jwt(Authentication authentication) {
    if (authentication instanceof JwtAuthenticationToken jwt) {
      return jwt;
    }
    throw new IllegalStateException("A checkout request needs a JWT authentication");
  }

  record Money(long amount, String currency) {
    static Money vnd(long amount) {
      return new Money(amount, "VND");
    }
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  record ItemBody(
      String sku,
      String name,
      String imageUrl,
      String variantLabel,
      int quantity,
      Money unitPrice,
      Money lineTotal,
      boolean priceChanged) {}

  record Body(
      UUID id, Reservation.Status status, Instant expiresAt, List<ItemBody> items, Money subtotal) {

    static Body of(Reservation reservation) {
      List<ItemBody> items =
          reservation.items().stream()
              .map(item ->
                  new ItemBody(
                      item.sku(),
                      item.name(),
                      item.imageUrl(),
                      item.variantLabel(),
                      item.quantity(),
                      Money.vnd(item.unitPrice()),
                      Money.vnd(item.lineTotal()),
                      item.priceChanged()))
              .toList();
      return new Body(
          reservation.id(),
          reservation.status(),
          reservation.expiresAt(),
          items,
          Money.vnd(reservation.subtotal()));
    }
  }
}
