package io.github.rey5137.topolith.spring.boot;

import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodReturnValueHandler;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.mvc.method.annotation.AsyncTaskMethodReturnValueHandler;
import org.springframework.web.servlet.mvc.method.annotation.CallableMethodReturnValueHandler;
import org.springframework.web.servlet.mvc.method.annotation.DeferredResultMethodReturnValueHandler;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitterReturnValueHandler;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBodyReturnValueHandler;
import org.springframework.web.servlet.mvc.method.annotation.RequestResponseBodyMethodProcessor;

/** Uses Spring's configured response-body processor for registered contract methods (D-125). */
final class ListedRouteReturnValues implements BeanPostProcessor {
    private final ListedContractRoutes routes;

    ListedRouteReturnValues(ListedContractRoutes routes) {
        this.routes = routes;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (bean instanceof RequestMappingHandlerAdapter adapter) {
            List<HandlerMethodReturnValueHandler> handlers = new ArrayList<>(adapter.getReturnValueHandlers());
            HandlerMethodReturnValueHandler responseBody = handlers.stream()
                    .filter(RequestResponseBodyMethodProcessor.class::isInstance).findFirst()
                    .orElseGet(() -> new RequestResponseBodyMethodProcessor(adapter.getMessageConverters()));
            List<HandlerMethodReturnValueHandler> asyncHandlers = handlers.stream()
                    .filter(handler -> handler instanceof CallableMethodReturnValueHandler
                            || handler instanceof DeferredResultMethodReturnValueHandler
                            || handler instanceof AsyncTaskMethodReturnValueHandler
                            || handler instanceof ResponseBodyEmitterReturnValueHandler
                            || handler instanceof StreamingResponseBodyReturnValueHandler)
                    .toList();
            handlers.add(0, new HandlerMethodReturnValueHandler() {
                @Override
                public boolean supportsReturnType(MethodParameter returnType) {
                    return routes.contains(returnType.getMethod())
                            && !HttpEntity.class.isAssignableFrom(returnType.getParameterType())
                            && !HttpHeaders.class.isAssignableFrom(returnType.getParameterType())
                            && asyncHandlers.stream().noneMatch(handler -> handler.supportsReturnType(returnType));
                }

                @Override
                public void handleReturnValue(Object value, MethodParameter returnType,
                        ModelAndViewContainer container, NativeWebRequest request) throws Exception {
                    responseBody.handleReturnValue(value, returnType, container, request);
                }
            });
            adapter.setReturnValueHandlers(handlers);
        }
        return bean;
    }
}
