package com.mayshop.cart.web;

import org.springframework.http.HttpStatus;

/** A documented cart error (404, 409, 422) that the exception handler turns into a problem. */
final class CartProblemException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final HttpStatus status;
  private final String code;
  private final transient Integer limit;
  private final String detail;

  private CartProblemException(
      HttpStatus status, String title, String code, Integer limit, String detail) {
    super(title);
    this.status = status;
    this.code = code;
    this.limit = limit;
    this.detail = detail;
  }

  static CartProblemException productNotFound() {
    return new CartProblemException(
        HttpStatus.NOT_FOUND, "Product not found", "PRODUCT_NOT_FOUND", null, null);
  }

  static CartProblemException itemNotFound() {
    return new CartProblemException(
        HttpStatus.NOT_FOUND, "Cart item not found", "CART_ITEM_NOT_FOUND", null, null);
  }

  static CartProblemException outOfStock() {
    return new CartProblemException(
        HttpStatus.CONFLICT, "Out of stock", "CART_OUT_OF_STOCK", null, null);
  }

  static CartProblemException quantityLimit(int limit) {
    return new CartProblemException(
        HttpStatus.UNPROCESSABLE_CONTENT,
        "Cart quantity limit reached",
        "CART_QTY_LIMIT",
        limit,
        "A cart holds at most " + limit + " units of one SKU.");
  }

  static CartProblemException itemsLimit(int limit) {
    return new CartProblemException(
        HttpStatus.UNPROCESSABLE_CONTENT,
        "Cart items limit reached",
        "CART_ITEMS_LIMIT",
        limit,
        "A cart holds at most " + limit + " distinct SKUs.");
  }

  static CartProblemException unauthenticated() {
    return new CartProblemException(
        HttpStatus.UNAUTHORIZED, "Unauthenticated", "AUTH_UNAUTHENTICATED", null, null);
  }

  static CartProblemException validation() {
    return new CartProblemException(
        HttpStatus.BAD_REQUEST, "Validation failed", "VALIDATION_FAILED", null, null);
  }

  HttpStatus status() {
    return status;
  }

  String code() {
    return code;
  }

  Integer limit() {
    return limit;
  }

  String detail() {
    return detail;
  }
}
