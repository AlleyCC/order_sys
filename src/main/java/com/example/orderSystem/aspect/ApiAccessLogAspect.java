package com.example.orderSystem.aspect;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Set;
import java.util.StringJoiner;

@Aspect
@Component
@Slf4j
public class ApiAccessLogAspect {

    private static final int MAX_ARG_LENGTH = 500;
    private static final Set<String> SENSITIVE_PARAMS = Set.of(
            "password", "token", "secret", "authorization", "refreshtoken"
    );

    @Around("within(com.example.orderSystem.controller..*)")
    public Object logApiAccess(ProceedingJoinPoint joinPoint) throws Throwable {
        long start = System.currentTimeMillis();

        String method = "UNKNOWN";
        String uri = "UNKNOWN";

        ServletRequestAttributes attrs =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs != null) {
            HttpServletRequest request = attrs.getRequest();
            method = request.getMethod();
            uri = request.getRequestURI();
        }

        String userId = resolveUserId();
        String args = formatArgs(joinPoint);

        try {
            Object result = joinPoint.proceed();
            long elapsed = System.currentTimeMillis() - start;
            log.info("[API] {} {} | userId={} | args={} | {}ms",
                    method, uri, userId, args, elapsed);
            return result;
        } catch (Throwable ex) {
            long elapsed = System.currentTimeMillis() - start;
            log.warn("[API-ERROR] {} {} | userId={} | args={} | exception={}:{} | {}ms",
                    method, uri, userId, args,
                    ex.getClass().getSimpleName(), ex.getMessage(), elapsed);
            throw ex;
        }
    }

    private String resolveUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated()
                && !"anonymousUser".equals(auth.getPrincipal())) {
            return auth.getName();
        }
        return "anonymous";
    }

    private String formatArgs(ProceedingJoinPoint joinPoint) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        String[] paramNames = signature.getParameterNames();
        Object[] argValues = joinPoint.getArgs();

        if (paramNames == null || paramNames.length == 0) {
            return "[]";
        }

        StringJoiner joiner = new StringJoiner(", ", "[", "]");
        for (int i = 0; i < paramNames.length; i++) {
            String name = paramNames[i];
            // Skip HttpServletRequest/HttpServletResponse — not useful in logs
            if (argValues[i] instanceof jakarta.servlet.ServletRequest
                    || argValues[i] instanceof jakarta.servlet.ServletResponse) {
                continue;
            }

            String value;
            if (isSensitive(name) || isSensitiveObject(argValues[i])) {
                value = "[MASKED]";
            } else {
                value = truncate(String.valueOf(argValues[i]));
            }
            joiner.add(name + "=" + value);
        }
        return joiner.toString();
    }

    private boolean isSensitive(String paramName) {
        return SENSITIVE_PARAMS.contains(paramName.toLowerCase());
    }

    private boolean isSensitiveObject(Object arg) {
        if (arg == null) return false;
        String str = String.valueOf(arg);
        String lower = str.toLowerCase();
        return lower.contains("password") && lower.contains("=");
    }

    private String truncate(String value) {
        if (value.length() > MAX_ARG_LENGTH) {
            return value.substring(0, MAX_ARG_LENGTH) + "...";
        }
        return value;
    }
}
