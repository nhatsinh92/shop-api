package com.mayshop.core;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Core modular monolith. Application modules live in direct sub-packages: auth, product, order,
 * checkout. They talk only through public APIs and Spring application events.
 */
@SpringBootApplication
public class CoreApplication {

  public static void main(String[] args) {
    SpringApplication.run(CoreApplication.class, args);
  }
}
