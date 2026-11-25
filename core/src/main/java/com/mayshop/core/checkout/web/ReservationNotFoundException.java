package com.mayshop.core.checkout.web;

/** No reservation with this id exists for the caller (unknown id or another user's). */
class ReservationNotFoundException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  ReservationNotFoundException() {
    super("Reservation not found");
  }
}
