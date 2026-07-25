package com.company.platform.shared.auth;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.PathVariable;

@Aspect
@Component
public class ResourceAuthorizationAspect {

  private final ResourceAuthorizationService resourceAuthorizationService;
  private final UserContext userContext;

  public ResourceAuthorizationAspect(
      ResourceAuthorizationService resourceAuthorizationService, UserContext userContext) {
    this.resourceAuthorizationService = resourceAuthorizationService;
    this.userContext = userContext;
  }

  @Around("@annotation(requiresAccess)")
  public Object checkAccess(ProceedingJoinPoint joinPoint, RequiresAccess requiresAccess)
      throws Throwable {
    String resourceId = extractResourceId(joinPoint);
    boolean allowed =
        resourceAuthorizationService.canAccess(
            userContext, requiresAccess.type(), resourceId, requiresAccess.action());
    if (!allowed) {
      throw new AccessDeniedException(requiresAccess.type(), resourceId);
    }
    return joinPoint.proceed();
  }

  private String extractResourceId(ProceedingJoinPoint joinPoint) {
    MethodSignature signature = (MethodSignature) joinPoint.getSignature();
    Method method = signature.getMethod();
    Annotation[][] parameterAnnotations = method.getParameterAnnotations();
    Object[] args = joinPoint.getArgs();
    for (int i = 0; i < parameterAnnotations.length; i++) {
      for (Annotation annotation : parameterAnnotations[i]) {
        if (annotation instanceof PathVariable) {
          return String.valueOf(args[i]);
        }
      }
    }
    throw new IllegalStateException(
        "@RequiresAccess yêu cầu method có tham số @PathVariable làm resourceId: " + method);
  }
}
