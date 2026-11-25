package com.mayshop.core.order.internal;

/** A documented domain error of the order endpoints: a status, a stable code and a title. */
public class OrderProblemException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final int status;
  private final String code;

  public OrderProblemException(int status, String code, String title) {
    super(title);
    this.status = status;
    this.code = code;
  }

  public int status() {
    return status;
  }

  public String code() {
    return code;
  }

  public String title() {
    return getMessage();
  }

  static OrderProblemException notFound() {
    return new OrderProblemException(
        404, "CHECKOUT_RESERVATION_NOT_FOUND", "Reservation not found");
  }

  static OrderProblemException expired() {
    return new OrderProblemException(410, "CHECKOUT_RESERVATION_EXPIRED", "Reservation expired");
  }

  static OrderProblemException consumed() {
    return new OrderProblemException(
        410, "CHECKOUT_RESERVATION_CONSUMED", "Reservation already used");
  }

  static OrderProblemException paymentMethodUnavailable() {
    return new OrderProblemException(
        422, "CHECKOUT_PAYMENT_METHOD_UNAVAILABLE", "Payment method unavailable");
  }

  static OrderProblemException inProgress() {
    return new OrderProblemException(409, "IDEMPOTENCY_IN_PROGRESS", "Request in progress");
  }

  static OrderProblemException keyReused() {
    return new OrderProblemException(422, "IDEMPOTENCY_KEY_REUSED", "Idempotency key reused");
  }

  public static OrderProblemException orderNotFound() {
    return new OrderProblemException(404, "ORDER_NOT_FOUND", "Order not found");
  }
}
