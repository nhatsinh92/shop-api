package com.mayshop.cart.repository;

import java.time.Instant;

/** One SKU in a cart; {@code addedAt} is the time of its first add. */
public record CartLine(String sku, int quantity, Instant addedAt) {}
