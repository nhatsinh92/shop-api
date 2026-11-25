package com.mayshop.core.order.internal;

import com.mayshop.core.checkout.Payments;
import java.util.UUID;

/**
 * A validated {@code placeOrder} request.
 *
 * @param key the {@code Idempotency-Key}, a UUID in lower case
 * @param fingerprint see {@code RequestFingerprint}
 * @param shippingAddressJson the address as sent, stored as is ({@code district} left out when
 *     absent)
 * @param note may be null
 * @param mockOutcome the {@code X-Mock-Payment-Outcome} header ({@code fail} or {@code timeout}),
 *     or null
 */
public record PlaceOrder(
    UUID userId,
    String key,
    String fingerprint,
    UUID reservationId,
    String shippingOptionId,
    Payments.Method paymentMethod,
    String shippingAddressJson,
    String note,
    String mockOutcome) {}
