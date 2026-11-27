package io.github.rey5137.topolith.spring.boot;

import java.lang.reflect.Method;
import java.util.Arrays;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.aop.support.AopUtils;
import org.springframework.util.ClassUtils;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.method.HandlerMethod;

/**
 * Marks a request to a module route with its handler when route handling starts, so the call observation does not count
 * the container's invocation of an exposed internal module's handler (the advised provider bean) as a contract call
 * (D-86). The first matching invocation consumes the mark, so a call the handler then makes is observed as usual.
 */
final class RouteMark {
    private static final String ATTRIBUTE = RouteMark.class.getName() + ".handler";

    private RouteMark() {
    }

    static void mark(HttpServletRequest request, HandlerMethod handler) {
        request.setAttribute(ATTRIBUTE, handler);
    }

    /** Whether this invocation is the marked handler's own, consuming the mark when it is. */
    static boolean consume(Object target, Method method) {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes == null || !(attributes.getAttribute(ATTRIBUTE, RequestAttributes.SCOPE_REQUEST)
                instanceof HandlerMethod handler)) {
            return false;
        }
        Method marked = handler.getMethod();
        if (ClassUtils.getUserClass(handler.getBeanType()) == AopUtils.getTargetClass(target)
                && marked.getName().equals(method.getName())
                && Arrays.equals(marked.getParameterTypes(), method.getParameterTypes())) {
            attributes.removeAttribute(ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
            return true;
        }
        return false;
    }
}
