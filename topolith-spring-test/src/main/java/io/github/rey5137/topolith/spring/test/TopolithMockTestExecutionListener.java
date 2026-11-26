package io.github.rey5137.topolith.spring.test;

import org.springframework.test.context.TestContext;
import org.springframework.test.context.TestContextAnnotationUtils;
import org.springframework.test.context.support.AbstractTestExecutionListener;
import org.springframework.util.ReflectionUtils;

/** Injects mock server fields after the test context has been loaded. */
final class TopolithMockTestExecutionListener extends AbstractTestExecutionListener {
    @Override
    public void prepareTestInstance(TestContext context) {
        Object instance = context.getTestInstance();
        while (instance != null) {
            injectFields(context, instance);
            instance = enclosingInstance(instance);
        }
    }

    /** Clears what the context recorded before the test, such as the startup version handshake. */
    @Override
    public void beforeTestMethod(TestContext context) { resetServers(context); }

    /** Resets context-owned servers after each method, as Spring Boot's {@code MockReset} does for mock beans. */
    @Override
    public void afterTestMethod(TestContext context) { resetServers(context); }

    /** Only the servers the customizer registered, found without eager init so no lazy bean is created. */
    private static void resetServers(TestContext context) {
        if (!context.hasApplicationContext()) { return; }
        var beans = context.getApplicationContext();
        String prefix = TopolithContextCustomizerFactory.beanName("");
        for (String name : beans.getBeanNamesForType(TopolithMockServer.class, false, false)) {
            if (name.startsWith(prefix)) { beans.getBean(name, TopolithMockServer.class).reset(); }
        }
    }

    private void injectFields(TestContext context, Object instance) {
        ReflectionUtils.doWithFields(instance.getClass(), field -> {
            TopolithMock annotation = field.getAnnotation(TopolithMock.class);
            if (annotation != null) {
                if (field.getType() != TopolithMockServer.class) {
                    throw new IllegalStateException("@TopolithMock field must be a TopolithMockServer: " + field);
                }
                ReflectionUtils.makeAccessible(field);
                Object server = context.getApplicationContext().getBean(
                        TopolithContextCustomizerFactory.beanName(annotation.id()), TopolithMockServer.class);
                ReflectionUtils.setField(field, instance, server);
            }
        });
    }

    private Object enclosingInstance(Object instance) {
        Class<?> type = instance.getClass();
        if (!TestContextAnnotationUtils.searchEnclosingClass(type)) { return null; }
        Class<?> enclosing = type.getEnclosingClass();
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (var field : current.getDeclaredFields()) {
                if (field.isSynthetic() && field.getType() == enclosing) {
                    ReflectionUtils.makeAccessible(field);
                    return ReflectionUtils.getField(field, instance);
                }
            }
        }
        return null;
    }
}
