package com.mayshop.cart.security;

import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/** Lets controllers declare a {@link CallerIdentity} parameter. */
class CallerIdentityResolver implements HandlerMethodArgumentResolver {

  @Override
  public boolean supportsParameter(MethodParameter parameter) {
    return CallerIdentity.class.isAssignableFrom(parameter.getParameterType());
  }

  @Override
  public Object resolveArgument(
      MethodParameter parameter,
      ModelAndViewContainer mavContainer,
      NativeWebRequest request,
      WebDataBinderFactory binderFactory) {
    Object identity =
        request.getAttribute(CallerIdentityFilter.ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
    if (identity == null) {
      throw new IllegalStateException("No caller identity: the request did not pass the filter");
    }
    return identity;
  }
}
