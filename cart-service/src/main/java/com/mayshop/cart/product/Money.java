package com.mayshop.cart.product;

/** An amount in VND (integer, no minor unit), as in the contract's {@code Money}. */
public record Money(long amount, String currency) {}
