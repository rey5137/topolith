package io.github.rey5137.topolith.spring.boot;

import java.util.Map;

import io.github.rey5137.topolith.core.TopolithCode;
import io.github.rey5137.topolith.core.TopolithException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(OutputCaptureExtension.class)
class ContextCarrierTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(ContextPropagationConfiguration.class);

    static final class Carrier implements ContextCarrier {
        private final String name;
        private String value;

        Carrier(String name) {
            this.name = name;
        }

        public String name() { return name; }
        public String current() { return value; }
        public Scope open(String next) {
            String old = value;
            value = next;
            return () -> value = old;
        }
    }

    @Test
    void ac_ext_77_headers_and_route_restore_carrier_including_absent_and_malformed() {
        Carrier carrier = new Carrier("locale");
        runner.withBean(ContextCarrier.class, () -> carrier).run(context -> {
            ContextPropagation propagation = context.getBean(ContextPropagation.class);
            carrier.value = "café";
            assertEquals(Map.of("Topolith-Context-locale", "caf%C3%A9"), propagation.headers());
            ContextRoute route = new ContextRoute(propagation);
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.addHeader("topolith-context-LOCALE", "hello%20world");
            assertTrue(route.open(request, new MockHttpServletResponse(), "account"));
            assertEquals("hello world", carrier.current());
            route.close(request);
            assertEquals("café", carrier.current());
            request = new MockHttpServletRequest();
            assertTrue(route.open(request, new MockHttpServletResponse(), "account"));
            assertNull(carrier.current());
            route.close(request);
            assertEquals("café", carrier.current());
            request = new MockHttpServletRequest();
            request.addHeader("Topolith-Context-locale", "%0A");
            MockHttpServletResponse response = new MockHttpServletResponse();
            assertFalse(route.open(request, response, "account"));
            assertEquals(400, response.getStatus());
        });
    }

    @Test
    void ac_ext_77_null_carrier_scope_is_safe_to_close() {
        ContextCarrier carrier = new ContextCarrier() {
            public String name() { return "optional"; }
            public String current() { return null; }
            public Scope open(String value) { return null; }
        };
        runner.withBean(ContextCarrier.class, () -> carrier).run(context -> {
            ContextRoute route = new ContextRoute(context.getBean(ContextPropagation.class));
            MockHttpServletRequest request = new MockHttpServletRequest();
            assertTrue(route.open(request, new MockHttpServletResponse(), "account"));
            assertDoesNotThrow(() -> route.close(request));
        });
    }

    @Test
    void ac_ext_78_bad_values_are_dropped_without_logging_value(CapturedOutput output) {
        Carrier carrier = new Carrier("locale");
        runner.withBean(ContextCarrier.class, () -> carrier).run(context -> {
            ContextPropagation propagation = context.getBean(ContextPropagation.class);
            carrier.value = "secret" + "x".repeat(1024);
            assertTrue(propagation.headers().isEmpty());
            carrier.value = "secret\n";
            assertTrue(propagation.headers().isEmpty());
        });
        assertTrue(output.getOut().contains("TL0035"));
        assertTrue(output.getOut().contains("locale"));
        assertFalse(output.getOut().contains("secret"));
    }

    @Test
    void ac_ext_78_invalid_duplicate_and_combined_limit_fail_with_tl0034() {
        assertFailure(runner.withBean("one", ContextCarrier.class, () -> new Carrier("!")));
        assertFailure(runner.withBean("one", ContextCarrier.class, () -> new Carrier("Name"))
                .withBean("two", ContextCarrier.class, () -> new Carrier("name")));
        assertFailure(runner.withBean("one", ContextCarrier.class, () -> new Carrier("name"))
                .withPropertyValues("topolith.context.mdc-keys=a,b,c,d,e,f,g,h,i,j,k,l,m,n,o,p"));
    }

    private static void assertFailure(ApplicationContextRunner runner) {
        runner.run(context -> {
            Throwable cause = context.getStartupFailure();
            while (cause != null && !(cause instanceof TopolithException)) {
                cause = cause.getCause();
            }
            assertNotNull(cause);
            assertEquals(TopolithCode.TL0034, ((TopolithException) cause).diagnostics().get(0).code());
        });
    }
}
