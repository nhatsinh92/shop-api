package com.mayshop.cart.product;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** One item of the {@code POST /internal/products/lookup} response (the cached JSON value). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProductInfo(
    String sku,
    String slug,
    String name,
    String imageUrl,
    String variantLabel,
    Money price,
    Money listPrice,
    String stockLevel) {}
