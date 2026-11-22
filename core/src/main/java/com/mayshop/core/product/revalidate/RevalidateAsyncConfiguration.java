package com.mayshop.core.product.revalidate;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Runs the {@code @ApplicationModuleListener} of {@link RevalidateWebhook} on an executor instead
 * of the thread that committed, so a slow web app never delays the internal update request.
 */
@Configuration(proxyBeanMethods = false)
@EnableAsync
@ConditionalOnExpression("!'${product.revalidate.url:}'.isBlank()")
class RevalidateAsyncConfiguration {}
