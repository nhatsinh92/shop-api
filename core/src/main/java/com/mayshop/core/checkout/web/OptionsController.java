package com.mayshop.core.checkout.web;

import com.mayshop.core.checkout.Payments;
import com.mayshop.core.checkout.Reservations;
import com.mayshop.core.checkout.ShippingOptions;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** {@code listShippingOptions} and {@code listPaymentMethods} of {@code core.yaml}. */
@RestController
class OptionsController {

  private final ShippingOptions shippingOptions;
  private final Payments payments;
  private final Reservations reservations;

  OptionsController(ShippingOptions shippingOptions, Payments payments, Reservations reservations) {
    this.shippingOptions = shippingOptions;
    this.payments = payments;
    this.reservations = reservations;
  }

  @GetMapping("/api/checkout/shipping-options")
  ResponseEntity<ShippingBody> listShippingOptions(
      @RequestParam(name = "reservationId", required = false) String reservationId,
      Authentication authentication) {
    if (reservationId != null) {
      UUID id = parse(reservationId);
      if (reservations.find(id, userId(authentication)).isEmpty()) {
        throw new ReservationNotFoundException();
      }
    }
    List<ShippingItem> items =
        shippingOptions.list().stream()
            .map(option ->
                new ShippingItem(
                    option.id(),
                    option.name(),
                    ReservationController.Money.vnd(option.fee()),
                    option.estimatedDelivery()))
            .toList();
    return ok(new ShippingBody(items));
  }

  @GetMapping("/api/checkout/payment-methods")
  ResponseEntity<PaymentBody> listPaymentMethods() {
    List<PaymentItem> items =
        payments.list().stream()
            .map(status ->
                new PaymentItem(
                    status.method().name(), status.method().displayName(), status.available()))
            .toList();
    return ok(new PaymentBody(items));
  }

  private static <T> ResponseEntity<T> ok(T body) {
    return ResponseEntity.ok()
        .header("Cache-Control", ReservationController.CACHE_CONTROL)
        .body(body);
  }

  /** A value that is not a UUID violates the parameter's schema: 400, with the field named. */
  private static UUID parse(String value) {
    try {
      return UUID.fromString(value);
    } catch (IllegalArgumentException e) {
      throw new InvalidReservationRequestException(
          List.of(
              new InvalidReservationRequestException.Violation("reservationId", "must be a UUID")));
    }
  }

  private static UUID userId(Authentication authentication) {
    if (authentication instanceof JwtAuthenticationToken jwt) {
      return UUID.fromString(jwt.getToken().getSubject());
    }
    throw new IllegalStateException("A checkout request needs a JWT authentication");
  }

  record ShippingItem(
      String id, String name, ReservationController.Money fee, String estimatedDelivery) {}

  record ShippingBody(List<ShippingItem> items) {}

  record PaymentItem(String id, String name, boolean available) {}

  record PaymentBody(List<PaymentItem> items) {}
}
