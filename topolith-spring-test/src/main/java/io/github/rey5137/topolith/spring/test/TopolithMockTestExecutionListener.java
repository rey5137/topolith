package io.github.rey5137.topolith.spring.test;

import io.github.rey5137.topolith.core.Incubating;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.TestContextAnnotationUtils;
import org.springframework.test.context.support.AbstractTestExecutionListener;
import org.springframework.util.ReflectionUtils;

/** Injects mock server fields after the test context has been loaded. */
@Incubating
public final class TopolithMockTestExecutionListener extends AbstractTestExecutionListener {
    @Override
    public void prepareTestInstance(TestContext context) {
        Object instance = context.getTestInstance();
        while (instance != null) {
            injectFields(context, instance);
            instance = enclosingInstance(instance);
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
                        TopolithContextCustomizerFactory.beanName(annotation.value()), TopolithMockServer.class);
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
