package io.github.rey5137.topolith.spring.cloud;

import java.io.IOException;
import java.io.Writer;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import io.github.rey5137.topolith.contract.ModuleUnavailableException;
import io.github.rey5137.topolith.core.ApiDescriptor;
import io.github.rey5137.topolith.core.Descriptors;
import io.github.rey5137.topolith.core.ModuleDescriptor;
import io.github.rey5137.topolith.spring.cloud.ResilienceTest.Reply;
import io.github.rey5137.topolith.spring.cloud.ResilienceTest.Stub;
import io.github.rey5137.topolith.spring.cloud.fixture.host.Host;
import io.github.rey5137.topolith.spring.cloud.fixture.orders.api.OrderContract;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.cloud.context.environment.EnvironmentChangeEvent;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.web.client.RestClientResponseException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** M7.4: a refresh swaps an external module's retry policy and circuit breaker (AC-EXT-68, R-EXT-08, D-104). */
@ExtendWith(OutputCaptureExtension.class)
class ResilienceRefreshTest {
    private static final String PACKAGE = "io.github.rey5137.topolith.spring.cloud.fixture";
    private static final String CONTRACT = OrderContract.class.getName();
    private static final String R = "topolith.modules.orders.remote.";
    private static final String[] TRIPPING = {R + "circuit-breaker.enabled=true",
            R + "circuit-breaker.sliding-window-size=4", R + "circuit-breaker.minimum-number-of-calls=4",
            R + "circuit-breaker.wait-duration-in-open-state=45s"};

    @TempDir
    Path root;

    @BeforeEach
    void descriptors() throws IOException {
        String api = PACKAGE + ".orders.api";
        List<String> idempotent = List.of(CONTRACT + "#missing", CONTRACT + "#read", CONTRACT + "#reject",
                CONTRACT + "#replace");
        write("apis", "orders", out -> Descriptors.write(ApiDescriptor.builder("orders").apiPackages(List.of(api))
                .apiVersion("1.0.0").provides(List.of(CONTRACT)).idempotent(idempotent).build(), out));
        write("modules", "orders", out -> Descriptors.write(ModuleDescriptor.builder("orders")
                .packageName(PACKAGE + ".orders").apiPackages(List.of(api)).apiVersion("1.0.0")
                .provides(List.of(CONTRACT)).idempotent(idempotent).build(), out));
        write("apis", "payment", out -> Descriptors.write(ApiDescriptor.builder("payment")
                .apiPackages(List.of(PACKAGE + ".payment.api")).apiVersion("1.0.0").provides(List.of()).build(), out));
        write("modules", "payment", out -> Descriptors.write(ModuleDescriptor.builder("payment")
                .packageName(PACKAGE + ".payment").apiPackages(List.of(PACKAGE + ".payment.api"))
                .apiVersion("1.0.0").provides(List.of()).build(), out));
    }

    private interface Check {
        void accept(AssertableApplicationContext context) throws Exception;
    }

    private interface Body {
        void write(Writer out) throws IOException;
    }

    private void write(String kind, String id, Body body) throws IOException {
        Path file = root.resolve("META-INF/topolith/" + kind + "/" + id + ".properties");
        Files.createDirectories(file.getParent());
        try (Writer out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            body.write(out);
        }
    }

    private void run(String url, Check check, Class<?> extra, String... more)
            throws Exception {
        try (URLClassLoader loader = new URLClassLoader(new URL[] {root.toUri().toURL()},
                ResilienceRefreshTest.class.getClassLoader())) {
            ApplicationContextRunner runner = new ApplicationContextRunner().withClassLoader(loader)
                    .withUserConfiguration(Host.class).withPropertyValues(
                            "management.endpoints.web.exposure.include=topolith",
                            "topolith.modules.orders.mode=external", "topolith.modules.payment.mode=none",
                            R + "url=" + url, R + "read-timeout=500ms", R + "retry.initial-backoff=1ms",
                            R + "retry.max-backoff=5ms").withPropertyValues(more);
            if (extra != null) {
                runner = runner.withUserConfiguration(extra);
            }
            runner.run(context -> {
                assertNull(context.getStartupFailure());
                check.accept(context);
            });
        }
    }

    private void run(String url, Check check, String... more) throws Exception {
        run(url, check, null, more);
    }

    private static void refresh(AssertableApplicationContext context, String... pairs) {
        ConfigurableApplicationContext source = context
                .getSourceApplicationContext(ConfigurableApplicationContext.class);
        if (source.getEnvironment().getPropertySources().get("refreshed") == null) {
            source.getEnvironment().getPropertySources().addFirst(new MapPropertySource("refreshed", new HashMap<>()));
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> values = (Map<String, Object>) source.getEnvironment().getPropertySources()
                .get("refreshed").getSource();
        Set<String> keys = new HashSet<>();
        for (int i = 0; i < pairs.length; i += 2) {
            values.put(pairs[i], pairs[i + 1]);
            keys.add(pairs[i]);
        }
        source.publishEvent(new EnvironmentChangeEvent(source, keys));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> module(AssertableApplicationContext context, String id) throws Exception {
        return (Map<String, Object>) ((Map<String, Object>) report(context).get("modules")).get(id);
    }

    private static CircuitBreaker breaker(AssertableApplicationContext context) {
        return context.getBean(CircuitBreakerRegistry.class).find("orders").orElseThrow();
    }

    private static void trip(OrderContract orders) {
        for (int i = 0; i < 4; i++) {
            assertThrows(RestClientResponseException.class, orders::read);
        }
    }

    @Test
    void ac_ext_68_retry_turns_on_and_off_without_a_restart() throws Exception {
        try (Stub stub = new Stub()) {
            stub.on("/read", Reply.status(502), Reply.status(502), Reply.ok());
            run(stub.url(), context -> {
                OrderContract orders = context.getBean(OrderContract.class);
                assertThrows(ModuleUnavailableException.class, orders::read);
                assertEquals(1, stub.count("/read"));
                refresh(context, R + "retry.max-attempts", "3");
                assertEquals("ok", orders.read());
                assertEquals(3, stub.count("/read"));
                refresh(context, R + "retry.max-attempts", "1");
                stub.on("/read", Reply.status(502));
                int before = stub.count("/read");
                assertThrows(ModuleUnavailableException.class, orders::read);
                assertEquals(before + 1, stub.count("/read"));
            }, "spring.cloud.circuitbreaker.http-services.enabled=false");
        }
    }

    @Test
    void ac_ext_68_a_retry_tuning_change_applies_and_leaves_an_open_circuit_open() throws Exception {
        try (Stub stub = new Stub()) {
            stub.on("/read", Reply.status(500));
            run(stub.url(), context -> {
                OrderContract orders = context.getBean(OrderContract.class);
                trip(orders);
                CircuitBreaker running = breaker(context);
                assertEquals(CircuitBreaker.State.OPEN, running.getState());
                refresh(context, R + "retry.max-attempts", "3");
                refresh(context, R + "retry.multiplier", "3.0");
                assertSame(running, breaker(context));
                assertEquals(CircuitBreaker.State.OPEN, running.getState());
                assertThrows(ModuleUnavailableException.class, orders::read);
                assertEquals(4, stub.count("/read"));
            }, TRIPPING);
        }
    }

    @Test
    void ac_ext_68_a_breaker_threshold_change_rebuilds_it_closed_under_the_new_thresholds() throws Exception {
        try (Stub stub = new Stub()) {
            stub.on("/read", Reply.status(500));
            run(stub.url(), context -> {
                OrderContract orders = context.getBean(OrderContract.class);
                trip(orders);
                CircuitBreaker old = breaker(context);
                assertEquals(CircuitBreaker.State.OPEN, old.getState());
                refresh(context, R + "circuit-breaker.sliding-window-size", "6",
                        R + "circuit-breaker.minimum-number-of-calls", "6");
                CircuitBreaker rebuilt = breaker(context);
                assertFalse(old == rebuilt);
                assertEquals(CircuitBreaker.State.CLOSED, rebuilt.getState());
                assertEquals(6, rebuilt.getCircuitBreakerConfig().getSlidingWindowSize());
                // the new breaker takes calls again and stays closed under its larger window
                for (int i = 0; i < 5; i++) {
                    assertThrows(RestClientResponseException.class, orders::read);
                }
                assertEquals(CircuitBreaker.State.CLOSED, rebuilt.getState());
                assertEquals(9, stub.count("/read"));
            }, TRIPPING);
        }
    }

    @Test
    void ac_ext_68_disabling_the_breaker_removes_it_and_enabling_builds_a_new_one() throws Exception {
        try (Stub stub = new Stub()) {
            stub.on("/read", Reply.status(500));
            run(stub.url(), context -> {
                OrderContract orders = context.getBean(OrderContract.class);
                trip(orders);
                refresh(context, R + "circuit-breaker.enabled", "false");
                assertTrue(context.getBean(CircuitBreakerRegistry.class).find("orders").isEmpty());
                for (int i = 0; i < 6; i++) {
                    assertThrows(RestClientResponseException.class, orders::read);
                }
                assertEquals(10, stub.count("/read"));
                refresh(context, R + "circuit-breaker.enabled", "true");
                assertEquals(CircuitBreaker.State.CLOSED, breaker(context).getState());
                trip(orders);
                assertEquals(CircuitBreaker.State.OPEN, breaker(context).getState());
            }, TRIPPING);
        }
    }

    @Test
    void ac_ext_68_the_breaker_can_be_turned_on_by_a_refresh_when_it_started_off() throws Exception {
        try (Stub stub = new Stub()) {
            stub.on("/read", Reply.status(500));
            run(stub.url(), context -> {
                OrderContract orders = context.getBean(OrderContract.class);
                trip(orders);
                refresh(context, R + "circuit-breaker.enabled", "true");
                trip(orders);
                assertEquals(CircuitBreaker.State.OPEN, breaker(context).getState());
            }, "spring.cloud.circuitbreaker.http-services.enabled=false", R + "circuit-breaker.sliding-window-size=4",
                    R + "circuit-breaker.minimum-number-of-calls=4");
        }
    }

    @Test
    void ac_ext_68_a_url_and_timeout_change_leaves_the_breaker_and_its_state() throws Exception {
        try (Stub first = new Stub(); Stub second = new Stub()) {
            first.on("/read", Reply.status(500));
            second.on("/read", Reply.ok());
            run(first.url(), context -> {
                OrderContract orders = context.getBean(OrderContract.class);
                trip(orders);
                CircuitBreaker running = breaker(context);
                refresh(context, R + "url", second.url(), R + "read-timeout", "700ms");
                assertSame(running, breaker(context));
                assertEquals(CircuitBreaker.State.OPEN, running.getState());
                // the open circuit rejects without calling the new URL
                assertThrows(ModuleUnavailableException.class, orders::read);
                assertEquals(0, second.count("/read"));
            }, TRIPPING);
        }
    }

    /** A call on its own thread, held at the provider on its first attempt until the test releases it. */
    private static final class HeldCall {
        final CountDownLatch arrived = new CountDownLatch(1);
        final CountDownLatch released = new CountDownLatch(1);
        CompletableFuture<Throwable> call;

        void hold(Stub stub) {
            stub.onArrival("/read", n -> {
                if (n == 0) {
                    arrived.countDown();
                    try {
                        released.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            });
        }

        void start(OrderContract orders) throws InterruptedException {
            call = CompletableFuture.supplyAsync(() -> {
                try {
                    orders.read();
                    return null;
                } catch (RuntimeException e) {
                    return e;
                }
            });
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!arrived.await(20, TimeUnit.MILLISECONDS)) {
                assertFalse(call.isDone(), "the call ended before reaching the provider: " + call.getNow(null));
                assertTrue(System.nanoTime() < deadline, "the call never reached the provider");
            }
        }

        Throwable finish() {
            released.countDown();
            return call.orTimeout(10, TimeUnit.SECONDS).join();
        }
    }

    @Test
    void ac_ext_68_a_call_in_flight_finishes_on_the_policy_it_started_with() throws Exception {
        try (Stub stub = new Stub()) {
            stub.on("/read", Reply.status(502));
            HeldCall held = new HeldCall();
            held.hold(stub);
            run(stub.url(), context -> {
                OrderContract orders = context.getBean(OrderContract.class);
                held.start(orders);
                refresh(context, R + "retry.max-attempts", "1");
                assertTrue(held.finish() instanceof ModuleUnavailableException);
                assertEquals(3, stub.count("/read"));
                assertThrows(ModuleUnavailableException.class, orders::read);
                assertEquals(4, stub.count("/read"));
            }, "spring.cloud.circuitbreaker.http-services.enabled=false", R + "retry.max-attempts=3");
        }
    }

    @Test
    void ac_ext_68_invalid_retry_settings_keep_the_policy_log_tl0011_and_show_on_the_endpoint(CapturedOutput output)
            throws Exception {
        try (Stub stub = new Stub()) {
            stub.on("/read", Reply.status(502), Reply.status(502), Reply.ok());
            run(stub.url(), context -> {
                refresh(context, R + "retry.max-attempts", "99");
                assertTrue(output.getAll().contains("TL0011"), output.getAll());
                @SuppressWarnings("unchecked")
                Map<String, Object> failure = (Map<String, Object>) module(context, "orders").get("refresh");
                assertEquals("failed", failure.get("status"));
                assertEquals("TL0011", failure.get("code"));
                assertTrue(failure.get("message").toString().contains("TL0003"), failure.toString());
                assertEquals("ok", context.getBean(OrderContract.class).read());
                assertEquals(3, stub.count("/read"));
                refresh(context, R + "retry.max-attempts", "2");
                assertFalse(module(context, "orders").containsKey("refresh"));
            }, R + "retry.max-attempts=3");
        }
    }

    @Test
    void ac_ext_68_invalid_breaker_settings_keep_the_running_breaker_and_log_tl0011(CapturedOutput output)
            throws Exception {
        try (Stub stub = new Stub()) {
            stub.on("/read", Reply.status(500));
            run(stub.url(), context -> {
                OrderContract orders = context.getBean(OrderContract.class);
                trip(orders);
                CircuitBreaker running = breaker(context);
                refresh(context, R + "circuit-breaker.failure-rate-threshold", "150");
                assertTrue(output.getAll().contains("TL0011"), output.getAll());
                assertEquals("failed", ((Map<?, ?>) module(context, "orders").get("refresh")).get("status"));
                assertSame(running, breaker(context));
                assertEquals(CircuitBreaker.State.OPEN, running.getState());
            }, TRIPPING);
        }
    }

    @Test
    void ac_ext_68_an_unsupported_factory_when_turning_the_breaker_on_keeps_the_group_without_one(
            CapturedOutput output) throws Exception {
        try (Stub stub = new Stub()) {
            stub.on("/read", Reply.status(500));
            run(stub.url(), context -> {
                OrderContract orders = context.getBean(OrderContract.class);
                refresh(context, R + "circuit-breaker.enabled", "true");
                assertTrue(output.getAll().contains("TL0011"), output.getAll());
                assertTrue(((Map<?, ?>) module(context, "orders").get("refresh")).get("message").toString()
                        .contains("TL0041"));
                for (int i = 0; i < 6; i++) {
                    assertThrows(RestClientResponseException.class, orders::read);
                }
                assertEquals(6, stub.count("/read"));
            }, ResilienceTest.SecondFactory.class, "spring.cloud.circuitbreaker.resilience4j.enabled=false",
                    "spring.cloud.circuitbreaker.http-services.enabled=false");
        }
    }

    @Test
    void ac_ext_68_a_none_module_refresh_changes_nothing_and_logs_nothing(CapturedOutput output) throws Exception {
        try (Stub stub = new Stub()) {
            run(stub.url(), context -> {
                refresh(context, "topolith.modules.payment.remote.retry.max-attempts", "3");
                assertFalse(output.getAll().contains("TL0011"), output.getAll());
                assertFalse(module(context, "orders").containsKey("refresh"));
            });
        }
    }

    @Test
    void ac_ext_68_turning_retry_on_while_spring_clouds_global_breaker_is_applied_needs_a_restart(
            CapturedOutput output) throws Exception {
        try (Stub stub = new Stub()) {
            stub.on("/read", Reply.status(502), Reply.status(502), Reply.ok());
            // every module off and the key unset: Spring Cloud's global breaker is applied to the group at startup
            run(stub.url(), context -> {
                OrderContract orders = context.getBean(OrderContract.class);
                refresh(context, R + "retry.max-attempts", "3");
                assertTrue(output.getAll().contains("TL0010") && output.getAll().contains(R + "retry.max-attempts"),
                        output.getAll());
                assertFalse(output.getAll().contains("TL0011"), output.getAll());
                assertEquals(true, report(context).get("restartPending"));
                assertEquals(List.of("wiring"), module(context, "orders").get("restartPending"));
                assertThrows(ModuleUnavailableException.class, orders::read);
                assertEquals(1, stub.count("/read"), "one attempt: retry stays off");
                refresh(context, R + "circuit-breaker.enabled", "true");
                assertEquals(1, output.getAll().split("circuit-breaker.enabled applies only", -1).length - 1);
                refresh(context, R + "circuit-breaker.enabled", "false");
                assertEquals(true, report(context).get("restartPending"), "retry is still on");
                refresh(context, R + "retry.max-attempts", "1");
                assertEquals(false, report(context).get("restartPending"));
            });
        }
    }

    @Test
    void ac_ext_68_a_call_in_flight_records_every_attempt_into_the_breaker_it_started_with() throws Exception {
        try (Stub stub = new Stub()) {
            // attempt 1 is a retryable 502, held while the breaker is rebuilt; attempt 2 is a plain 500
            stub.on("/read", Reply.status(502), Reply.status(500));
            HeldCall held = new HeldCall();
            held.hold(stub);
            run(stub.url(), context -> {
                OrderContract orders = context.getBean(OrderContract.class);
                CircuitBreaker old = breaker(context);
                held.start(orders);
                refresh(context, R + "circuit-breaker.failure-rate-threshold", "30");
                CircuitBreaker rebuilt = breaker(context);
                assertFalse(old == rebuilt);
                assertTrue(held.finish() instanceof RestClientResponseException);
                assertEquals(2, stub.count("/read"));
                assertEquals(2, old.getMetrics().getNumberOfBufferedCalls(), "both attempts recorded into the old one");
                assertEquals(0, rebuilt.getMetrics().getNumberOfBufferedCalls(), "the rebuilt window is empty");
            }, withRetry(TRIPPING));
        }
    }

    @Test
    void ac_ext_68_disabling_during_a_call_then_enabling_leaves_no_orphan_and_no_tl0011(CapturedOutput output)
            throws Exception {
        try (Stub stub = new Stub()) {
            stub.on("/read", Reply.status(502), Reply.status(500));
            HeldCall held = new HeldCall();
            held.hold(stub);
            run(stub.url(), context -> {
                OrderContract orders = context.getBean(OrderContract.class);
                CircuitBreaker old = breaker(context);
                held.start(orders);
                refresh(context, R + "circuit-breaker.enabled", "false");
                assertTrue(held.finish() instanceof RestClientResponseException);
                assertEquals(2, old.getMetrics().getNumberOfBufferedCalls(), "the call finished on its own breaker");
                assertTrue(context.getBean(CircuitBreakerRegistry.class).find("orders").isEmpty(),
                        "the finished call did not re-create the removed breaker");
                refresh(context, R + "circuit-breaker.enabled", "true");
                assertFalse(output.getAll().contains("TL0011"), output.getAll());
                assertEquals(CircuitBreaker.State.CLOSED, breaker(context).getState());
            }, withRetry(TRIPPING));
        }
    }

    private static String[] withRetry(String[] more) {
        String[] all = java.util.Arrays.copyOf(more, more.length + 1);
        all[more.length] = R + "retry.max-attempts=2";
        return all;
    }

    @Test
    void ac_ext_68_the_global_breaker_key_is_not_tested_on_refresh() throws Exception {
        try (Stub stub = new Stub()) {
            stub.on("/read", Reply.status(502), Reply.status(502), Reply.ok());
            run(stub.url(), context -> {
                refresh(context, "spring.cloud.circuitbreaker.http-services.enabled", "true",
                        R + "retry.max-attempts", "3");
                assertFalse(module(context, "orders").containsKey("refresh"));
                assertEquals("ok", context.getBean(OrderContract.class).read());
                assertEquals(3, stub.count("/read"));
            }, "spring.cloud.circuitbreaker.http-services.enabled=false");
        }
    }

    @Test
    void ac_ext_68_a_hosts_replacement_of_the_breaker_is_a_conflict_not_overwritten(CapturedOutput output)
            throws Exception {
        try (Stub stub = new Stub()) {
            run(stub.url(), context -> {
                CircuitBreakerRegistry registry = context.getBean(CircuitBreakerRegistry.class);
                registry.remove("orders");
                CircuitBreaker host = registry.circuitBreaker("orders");
                refresh(context, R + "circuit-breaker.failure-rate-threshold", "30");
                assertTrue(output.getAll().contains("TL0011"), output.getAll());
                assertSame(host, breaker(context));
            }, TRIPPING);
        }
    }

    @Test
    void ac_ext_68_a_failed_breaker_build_leaves_the_retry_policy_unchanged(CapturedOutput output) throws Exception {
        try (Stub stub = new Stub()) {
            stub.on("/read", Reply.status(502));
            run(stub.url(), context -> {
                OrderContract orders = context.getBean(OrderContract.class);
                refresh(context, R + "retry.max-attempts", "3", R + "circuit-breaker.enabled", "true");
                assertTrue(output.getAll().contains("TL0011"), output.getAll());
                assertThrows(ModuleUnavailableException.class, orders::read);
                assertEquals(1, stub.count("/read"), "the new retry policy was not published");
            }, ResilienceTest.SecondFactory.class, "spring.cloud.circuitbreaker.resilience4j.enabled=false",
                    "spring.cloud.circuitbreaker.http-services.enabled=false");
        }
    }

    @Test
    void ac_ext_68_a_hosts_own_breaker_of_the_same_name_is_never_removed() throws Exception {
        try (Stub stub = new Stub()) {
            run(stub.url(), context -> {
                CircuitBreaker host = breaker(context);
                refresh(context, R + "circuit-breaker.failure-rate-threshold", "30");
                refresh(context, R + "circuit-breaker.enabled", "false");
                assertSame(host, breaker(context));
            }, ResilienceTest.RegistersOrders.class, "spring.cloud.circuitbreaker.http-services.enabled=false");
        }
    }

    @Test
    void ac_ext_68_an_endpoint_failure_and_a_resilience_failure_do_not_clear_each_other() throws Exception {
        try (Stub stub = new Stub()) {
            run(stub.url(), context -> {
                refresh(context, R + "read-timeout", "bogus");
                assertEquals("failed", ((Map<?, ?>) module(context, "orders").get("refresh")).get("status"));
                refresh(context, R + "retry.max-attempts", "2");
                assertTrue(module(context, "orders").containsKey("refresh"),
                        "a valid retry refresh keeps the unapplied endpoint failure");
                refresh(context, R + "read-timeout", "600ms");
                assertFalse(module(context, "orders").containsKey("refresh"));
                refresh(context, R + "retry.max-attempts", "99");
                assertTrue(module(context, "orders").containsKey("refresh"));
                refresh(context, R + "read-timeout", "700ms");
                assertTrue(module(context, "orders").containsKey("refresh"),
                        "a valid endpoint refresh keeps the unapplied resilience failure");
                refresh(context, R + "retry.max-attempts", "2");
                assertFalse(module(context, "orders").containsKey("refresh"));
            }, "spring.cloud.circuitbreaker.http-services.enabled=false");
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> report(AssertableApplicationContext context) throws Exception {
        Object endpoint = context.getBean("topolithEndpoint");
        Method modules = endpoint.getClass().getDeclaredMethod("modules");
        modules.setAccessible(true);
        return (Map<String, Object>) modules.invoke(endpoint);
    }
}
