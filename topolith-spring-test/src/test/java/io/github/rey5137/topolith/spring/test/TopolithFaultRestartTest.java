package io.github.rey5137.topolith.spring.test;

import java.util.concurrent.atomic.AtomicInteger;

import io.github.rey5137.topolith.spring.boot.internal.FaultSequences;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.test.context.TestContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** M5.4: the listener restarts every loopback module's fault sequence before each test method (R-EXT-10). */
class TopolithFaultRestartTest {
    @Test
    void ac_ext_38_the_listener_restarts_the_fault_sequences_before_each_method() {
        AtomicInteger restarts = new AtomicInteger();
        try (GenericApplicationContext beans = new GenericApplicationContext()) {
            beans.registerBean("sequences", FaultSequences.class, () -> restarts::incrementAndGet);
            beans.refresh();
            TestContext context = mock(TestContext.class);
            when(context.hasApplicationContext()).thenReturn(true);
            when(context.getApplicationContext()).thenReturn(beans);
            TopolithMockTestExecutionListener listener = new TopolithMockTestExecutionListener();
            listener.beforeTestMethod(context);
            listener.beforeTestMethod(context);
            assertEquals(2, restarts.get());
        }
    }

    @Test
    void ac_ext_38_a_context_without_fault_sequences_is_left_alone() {
        try (GenericApplicationContext beans = new GenericApplicationContext()) {
            beans.refresh();
            TestContext context = mock(TestContext.class);
            when(context.hasApplicationContext()).thenReturn(true);
            when(context.getApplicationContext()).thenReturn(beans);
            new TopolithMockTestExecutionListener().beforeTestMethod(context);
        }
    }
}
