package com.mayshop.cart.product;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Registers the product lookup settings. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ProductLookupProperties.class)
class ProductConfiguration {}
