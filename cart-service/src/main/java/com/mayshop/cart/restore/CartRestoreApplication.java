package com.mayshop.cart.restore;

import com.mayshop.cart.writebehind.store.CartSnapshotStore;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * The restore mode ({@code --cart.restore=true}): a small context with Redis and Postgres only, so
 * it serves no HTTP, runs no scheduler and starts no Kafka listener. It deliberately carries no
 * stereotype annotation, so the component scan of the normal application never picks it up.
 */
@EnableAutoConfiguration
@Import(CartSnapshotStore.class)
public class CartRestoreApplication {

  private static final Logger LOG = LoggerFactory.getLogger(CartRestoreApplication.class);

  @Bean
  CartRestoreService cartRestoreService(StringRedisTemplate redis, CartSnapshotStore store) {
    return new CartRestoreService(redis, store, Clock.systemUTC());
  }

  /** True when the arguments contain {@code --cart.restore=true}. */
  public static boolean requested(String... args) {
    ApplicationArguments arguments = new DefaultApplicationArguments(args);
    return arguments.getOptionValues("cart.restore") != null
        && arguments.getOptionValues("cart.restore").contains("true");
  }

  /**
   * Restores and returns the process exit code: 0 when the restore completed, 1 when Redis or
   * Postgres was unreachable or the context could not start.
   */
  public static int run(String... args) {
    SpringApplication app = new SpringApplication(CartRestoreApplication.class);
    app.setWebApplicationType(WebApplicationType.NONE);
    try (ConfigurableApplicationContext context = app.run(args)) {
      context.getBean(CartRestoreService.class).restore();
      return 0;
    } catch (Exception e) {
      LOG.error("Cart restore failed: {}", e.toString());
      return 1;
    }
  }
}
