package com.mayshop.cart.writebehind.store;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.util.backoff.ExponentialBackOff;

/**
 * Listener container for {@code cart-writer} (failed records are retried with backoff, forever) and
 * the scheduler of the daily snapshot cleanup.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
class WriteBehindConfiguration {

  static final String LISTENER_FACTORY = "cartWriterListenerFactory";

  private static final long INITIAL_BACKOFF_MS = 500;
  private static final long MAX_BACKOFF_MS = 30_000;

  @Bean(LISTENER_FACTORY)
  ConcurrentKafkaListenerContainerFactory<String, String> cartWriterListenerFactory(
      ConsumerFactory<String, String> consumers) {
    ExponentialBackOff backOff = new ExponentialBackOff(INITIAL_BACKOFF_MS, 2.0);
    backOff.setMaxInterval(MAX_BACKOFF_MS);
    // The default maximum elapsed time is unlimited: a record is retried until it succeeds.
    ConcurrentKafkaListenerContainerFactory<String, String> factory =
        new ConcurrentKafkaListenerContainerFactory<>();
    factory.setConsumerFactory(consumers);
    factory.setCommonErrorHandler(new DefaultErrorHandler(backOff));
    return factory;
  }
}
