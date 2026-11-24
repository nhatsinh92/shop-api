package com.mayshop.cart.repository;

/** The user cart after a merge and how many guest lines did not fit under the SKU limit. */
public record MergeResult(CartSnapshot snapshot, int droppedLines) {}
