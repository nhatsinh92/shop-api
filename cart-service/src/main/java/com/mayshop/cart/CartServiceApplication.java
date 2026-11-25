package com.mayshop.cart;

import com.mayshop.cart.restore.CartRestoreApplication;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Cart service: Redis-backed cart, the hot path at peak. */
@SpringBootApplication
public class CartServiceApplication {

  public static void main(String[] args) {
    if (CartRestoreApplication.requested(args)) {
      System.exit(CartRestoreApplication.run(args));
    }
    SpringApplication.run(CartServiceApplication.class, args);
  }
}
