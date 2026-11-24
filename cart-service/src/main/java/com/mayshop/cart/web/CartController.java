package com.mayshop.cart.web;

import com.mayshop.cart.repository.CartKey;
import com.mayshop.cart.repository.CartRepository;
import com.mayshop.cart.security.CallerIdentity;
import com.mayshop.cart.security.GuestCartCookies;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Cart endpoints. Reads never create a guest id or set {@code guest_cart}; the first successful add
 * of a guest without a valid cookie does.
 */
@RestController
@RequestMapping("/api/cart")
class CartController {

  private static final String NO_STORE = "private, no-store";

  private static final int MAX_SKU_LENGTH = 64;

  private final CartReadService reads;
  private final CartWriteService writes;
  private final GuestCartCookies guestCookies;

  CartController(CartReadService reads, CartWriteService writes, GuestCartCookies guestCookies) {
    this.reads = reads;
    this.writes = writes;
    this.guestCookies = guestCookies;
  }

  @PostMapping("/items")
  ResponseEntity<CartView.Cart> addCartItem(
      CallerIdentity identity, @RequestBody Map<String, Object> body) {
    String sku = sku(body.get("sku"));
    int quantity = quantity(body.get("quantity"));
    String newGuestId = null;
    CartKey key;
    switch (identity) {
      case CallerIdentity.User user -> key = CartKey.forUser(user.id());
      case CallerIdentity.Guest guest -> {
        if (guest.id() == null) {
          newGuestId = guestCookies.newId();
          key = CartKey.forGuest(newGuestId);
        } else {
          key = CartKey.forGuest(guest.id());
        }
      }
    }
    CartView.Cart cart = writes.add(key, sku, quantity);
    ResponseEntity.BodyBuilder response =
        ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, NO_STORE);
    if (newGuestId != null) {
      response.header(HttpHeaders.SET_COOKIE, guestCookies.setCookie(newGuestId));
    }
    return response.body(cart);
  }

  @PatchMapping("/items/{sku}")
  ResponseEntity<CartView.Cart> updateCartItem(
      CallerIdentity identity, @PathVariable String sku, @RequestBody Map<String, Object> body) {
    String checkedSku = sku(sku);
    int quantity = quantity(body.get("quantity"));
    CartKey key = existingKey(identity);
    return ok(writes.update(key, checkedSku, quantity));
  }

  @DeleteMapping("/items/{sku}")
  ResponseEntity<CartView.Cart> removeCartItem(CallerIdentity identity, @PathVariable String sku) {
    return ok(writes.remove(existingKey(identity), sku(sku)));
  }

  /**
   * Merges the guest cart into the user cart after login and clears {@code guest_cart}. Without the
   * cookie the user cart is returned unchanged; a malformed or unknown id changes nothing but the
   * cookie is still cleared.
   */
  @PostMapping("/merge")
  ResponseEntity<CartView.Cart> mergeGuestCart(
      CallerIdentity identity,
      @CookieValue(name = GuestCartCookies.NAME, required = false) String rawGuestCookie) {
    if (!(identity instanceof CallerIdentity.User user)) {
      throw CartProblemException.unauthenticated();
    }
    CartKey userKey = CartKey.forUser(user.id());
    if (rawGuestCookie == null) {
      return ok(reads.cart(identity));
    }
    CartView.Cart cart =
        user.guestId() == null
            ? reads.cart(identity)
            : writes.merge(userKey, CartKey.forGuest(user.guestId()));
    return ResponseEntity.ok()
        .header(HttpHeaders.CACHE_CONTROL, NO_STORE)
        .header(HttpHeaders.SET_COOKIE, guestCookies.clearCookie())
        .body(cart);
  }

  /** The key of a cart that must already exist: a guest without a cookie has none (404). */
  private static CartKey existingKey(CallerIdentity identity) {
    return switch (identity) {
      case CallerIdentity.User user -> CartKey.forUser(user.id());
      case CallerIdentity.Guest guest -> {
        if (guest.id() == null) {
          throw CartProblemException.itemNotFound();
        }
        yield CartKey.forGuest(guest.id());
      }
    };
  }

  private static ResponseEntity<CartView.Cart> ok(CartView.Cart cart) {
    return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, NO_STORE).body(cart);
  }

  private static String sku(Object value) {
    if (value instanceof String s && !s.isBlank() && s.length() <= MAX_SKU_LENGTH) {
      return s;
    }
    throw CartProblemException.validation();
  }

  /**
   * A whole number of at least 1. More than the per-SKU limit is 422 here (the contract documents
   * it on both operations), not a validation error.
   */
  private static int quantity(Object value) {
    long n;
    if (value instanceof Integer i) {
      n = i;
    } else if (value instanceof Long l) {
      n = l;
    } else if (value instanceof java.math.BigInteger b && b.bitLength() < 63) {
      n = b.longValue();
    } else {
      throw CartProblemException.validation();
    }
    if (n < 1) {
      throw CartProblemException.validation();
    }
    if (n > CartRepository.MAX_QUANTITY) {
      throw CartProblemException.quantityLimit(CartRepository.MAX_QUANTITY);
    }
    return (int) n;
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
