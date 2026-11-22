package com.mayshop.core.product.persistence;

import java.util.List;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.modulith.events.core.EventPublicationRepository;

/**
 * Keeps the JDBC event publication registry of Spring Modulith from breaking application contexts
 * that have no database. Its auto-configuration requires a {@code DataSource} bean without a
 * condition, so contexts that exclude the DataSource auto-configuration (the slices that run
 * against in-memory fakes) could not start. When no {@code dataSource} bean is defined, its beans
 * are replaced by a map-backed registry that forgets completed publications. With a database
 * nothing changes.
 */
@Configuration(proxyBeanMethods = false)
class EventRegistryConfiguration {

  private static final List<String> JDBC_REGISTRY_BEANS =
      List.of(
          "databaseType",
          "jdbcEventPublicationRepositorySettings",
          "jdbcEventPublicationRepository",
          "databaseSchemaInitializer");

  @Bean
  static BeanDefinitionRegistryPostProcessor jdbcRegistryOnlyWithDatabase() {
    return new BeanDefinitionRegistryPostProcessor() {
      @Override
      public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) {
        if (registry.containsBeanDefinition("dataSource")) {
          return;
        }
        JDBC_REGISTRY_BEANS.stream()
            .filter(registry::containsBeanDefinition)
            .forEach(registry::removeBeanDefinition);
        registry.registerBeanDefinition(
            "inMemoryEventPublicationRepository",
            BeanDefinitionBuilder.genericBeanDefinition(
                    EventPublicationRepository.class, InMemoryEventPublicationRepository::new)
                .getBeanDefinition());
      }

      @Override
      public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        // Nothing to do: the registry step above is the whole job.
      }
    };
  }
}
