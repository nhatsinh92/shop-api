package com.mayshop.cart;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Cart service: Redis-backed cart, the hot path at peak. Endpoints arrive in Phase 4. */
@SpringBootApplication
public class CartServiceApplication {

  public static void main(String[] args) {
    SpringApplication.run(CartServiceApplication.class, args);
  }
}
