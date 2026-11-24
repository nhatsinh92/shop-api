package com.mayshop.cart.web;

import com.mayshop.cart.security.CallerIdentity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Read endpoints of the cart. Reads never create a guest id or set {@code guest_cart}. */
@RestController
@RequestMapping("/api/cart")
class CartController {

  private static final String NO_STORE = "private, no-store";

  private final CartReadService reads;

  CartController(CartReadService reads) {
    this.reads = reads;
  }

  @GetMapping
  ResponseEntity<CartView.Cart> getCart(CallerIdentity identity) {
    return ResponseEntity.ok()
        .header(HttpHeaders.CACHE_CONTROL, NO_STORE)
        .body(reads.cart(identity));
  }

  @GetMapping("/summary")
  ResponseEntity<CartView.Summary> getCartSummary(CallerIdentity identity) {
    return ResponseEntity.ok()
        .header(HttpHeaders.CACHE_CONTROL, NO_STORE)
        .body(reads.summary(identity));
  }
}
