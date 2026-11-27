package io.github.rey5137.topolith.spring.boot.errorfixture.listed;

import java.lang.reflect.Proxy;

import io.github.rey5137.topolith.spring.boot.errorfixture.listed.api.ListedContract;
import io.github.rey5137.topolith.spring.boot.errorfixture.listed.api.ListedFailure;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class ListedConfiguration {
    @Bean
    ListedContract listedProvider(@Value("${test.listed.proxy:false}") boolean proxy) {
        ListedContract provider = input -> {
            if ("fail".equals(input)) {
                throw new ListedFailure("declared failure");
            }
            return "value:" + input;
        };
        return proxy ? (ListedContract) Proxy.newProxyInstance(ListedContract.class.getClassLoader(),
                new Class<?>[] {ListedContract.class}, (instance, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return switch (method.getName()) {
                            case "hashCode" -> System.identityHashCode(instance);
                            case "equals" -> instance == args[0];
                            default -> "listedProvider";
                        };
                    }
                    try {
                        return method.invoke(provider, args);
                    } catch (java.lang.reflect.InvocationTargetException e) {
                        throw e.getCause();
                    }
                }) : provider;
    }
}
