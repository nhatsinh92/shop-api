package com.mayshop.cart.repository;

/** Status of a write and the snapshot read in the same script. */
public record WriteResult(WriteStatus status, CartSnapshot snapshot) {

  public boolean ok() {
    return status == WriteStatus.OK;
  }
}
