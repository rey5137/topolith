package io.github.rey5137.topolith.spring.test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import fixture.invoice.InvoicesImpl;
import fixture.invoice.api.Invoice;
import fixture.invoice.api.InvoiceException;
import fixture.invoice.api.Invoices;
import fixture.invoice.api.Line;
import fixture.invoice.api.Lossy;
import fixture.payment.api.Payment;
import io.github.rey5137.topolith.contract.ModuleUnavailableException;
import io.github.rey5137.topolith.core.Mode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.Advised;
import org.springframework.test.util.AopTestUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.context.TestContextManager;
import org.springframework.test.util.AopTestUtils;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.*;

class TopolithByValueTest {
    private static ClassLoader original;
    private static java.net.URLClassLoader fixtures;

    @BeforeAll
    static void installFixtures() {
        original = Thread.currentThread().getContextClassLoader();
        fixtures = FixtureLoader.create("by-value-descriptors/");
        Thread.currentThread().setContextClassLoader(fixtures);
    }

    @AfterAll
    static void removeFixtures() throws java.io.IOException {
        Thread.currentThread().setContextClassLoader(original);
        fixtures.close();
    }

    private static ApplicationContextRunner runner() {
        return new ApplicationContextRunner().withClassLoader(fixtures)
                .withUserConfiguration(TopolithTestModesTest.Host.class);
    }

    private static void byValue(Consumer<AssertableApplicationContext> test) {
        TopolithTestModes.module("invoice").internal().byValue().applyTo(runner()).run(context -> {
            assertNull(context.getStartupFailure());
            test.accept(context);
        });
    }

    private static InvoicesImpl provider(AssertableApplicationContext context) {
        return AopTestUtils.getUltimateTargetObject(context.getBean(Invoices.class));
    }

    private static Invoice invoice() { return new Invoice("first", new Line("a", 2), new Line("b", 3)); }

    @Test
    void ac_test_08_provider_receives_a_copy_and_caller_a_copy_of_the_result() {
        byValue(context -> {
            Invoices invoices = context.getBean(Invoices.class);
            Invoice argument = invoice();
            assertEquals(5, invoices.total(argument));
            InvoicesImpl provider = provider(context);
            assertNotSame(argument, provider.lastArgument);
            assertEquals("first", provider.lastArgument.name);
            Invoice held = invoices.held();
            assertNotSame(provider.held, held);
            assertEquals("held", held.name);
            assertEquals(1, held.lines.get(0).qty);
        });
    }

    @Test
    void ac_test_08_generic_types_are_read_back_by_the_declared_type() {
        byValue(context -> {
            List<Line> in = List.of(new Line("a", 1), new Line("b", 2));
            List<Line> out = context.getBean(Invoices.class).echo(in);
            assertEquals(2, out.size());
            assertInstanceOf(Line.class, out.get(1));
            assertNotSame(in.get(1), out.get(1));
            assertEquals("b", out.get(1).sku);
        });
    }

    @Test
    void ac_test_08_without_the_proxy_the_provider_receives_the_callers_instance() {
        TopolithTestModes.module("invoice").internal().applyTo(runner()).run(context -> {
            assertNull(context.getStartupFailure());
            Invoice argument = invoice();
            context.getBean(Invoices.class).total(argument);
            // the provider bean is class-proxied by the call observation advice (D-86): read its target
            assertSame(argument, ((InvoicesImpl) AopTestUtils.getUltimateTargetObject(
                    context.getBean(InvoicesImpl.class))).lastArgument);
            assertTrue(context.getBeansOfType(ByValueRecorder.class).isEmpty());
        });
    }

    @Test
    void ac_test_09_provider_changing_its_argument_fails_the_call() {
        byValue(context -> {
            provider(context).mutateArgument = true;
            AssertionError error = assertThrows(AssertionError.class,
                    () -> context.getBean(Invoices.class).total(invoice()));
            assertTrue(error.getMessage().contains("module 'invoice'"), error.getMessage());
            assertTrue(error.getMessage().contains("Invoices.total"), error.getMessage());
            assertTrue(error.getMessage().contains("argument 0"), error.getMessage());
            assertTrue(error.getMessage().contains("$.lines[0].qty"), error.getMessage());
        });
    }

    @Test
    void ac_test_09_caller_changing_its_own_argument_after_the_call_is_not_a_failure() {
        byValue(context -> {
            Invoices invoices = context.getBean(Invoices.class);
            Invoice argument = invoice();
            invoices.total(argument);
            argument.lines.get(0).qty = 7;
            Invoice held = invoices.held();
            held.name = "mine";
            assertEquals(10, invoices.total(argument));
            invoices.nothing();
            assertTrue(context.getBean(ByValueRecorder.class).finish().isEmpty());
        });
    }

    @Test
    void ac_test_10_provider_changing_a_kept_argument_fails_the_next_call() {
        byValue(context -> {
            Invoices invoices = context.getBean(Invoices.class);
            invoices.keep(invoice());
            invoices.touchKept();
            AssertionError error = assertThrows(AssertionError.class, invoices::nothing);
            assertTrue(error.getMessage().contains("Invoices.keep, argument 0"), error.getMessage());
            assertTrue(error.getMessage().contains("after the call returned"), error.getMessage());
            assertTrue(error.getMessage().contains("$.name"), error.getMessage());
            assertEquals(1, context.getBean(ByValueRecorder.class).release().size());
        });
    }

    @Test
    void ac_test_10_provider_changing_a_returned_object_fails_the_next_call() {
        byValue(context -> {
            Invoices invoices = context.getBean(Invoices.class);
            invoices.held();
            invoices.touchHeld();
            AssertionError error = assertThrows(AssertionError.class, invoices::nothing);
            assertTrue(error.getMessage().contains("Invoices.held, result"), error.getMessage());
            assertTrue(error.getMessage().contains("$.lines[0].qty"), error.getMessage());
        });
    }

    @Test
    void ac_test_10_end_of_method_check_finds_a_change_and_releases_everything() {
        byValue(context -> {
            Invoices invoices = context.getBean(Invoices.class);
            invoices.keep(invoice());
            invoices.touchKept();
            var recorder = context.getBean(ByValueRecorder.class);
            assertEquals(1, recorder.finish().size());
            invoices.nothing();
            assertTrue(recorder.finish().isEmpty());
        });
    }

    @TopolithModuleMode(id = "invoice", mode = Mode.INTERNAL)
    @TopolithByValue("invoice")
    @SpringBootTest(classes = TopolithTestModesTest.Host.class)
    static class Scenario {
        @Autowired Invoices invoices;

        void catchesTheError() {
            invoices.keep(new Invoice("kept"));
            invoices.touchKept();
            try {
                invoices.nothing();
            } catch (AssertionError caught) {
                // the listener still fails the method
            }
        }

        void clean() { invoices.nothing(); }
    }

    private static void run(TestContextManager manager, Object instance, String name) throws Exception {
        Method method = Scenario.class.getDeclaredMethod(name);
        manager.beforeTestMethod(instance, method);
        Throwable failure = null;
        try {
            method.invoke(instance);
        } catch (Throwable e) {
            failure = e;
        }
        manager.afterTestMethod(instance, method, failure);
    }

    @Test
    void ac_test_10_listener_fails_the_method_even_when_the_error_was_caught_and_releases_after() throws Exception {
        var manager = new TestContextManager(Scenario.class);
        Object instance = new Scenario();
        manager.prepareTestInstance(instance);
        AssertionError error = assertThrows(AssertionError.class, () -> run(manager, instance, "catchesTheError"));
        assertTrue(error.getMessage().contains("after the call returned"), error.getMessage());
        run(manager, instance, "clean");
    }

    @Test
    void ac_test_11_page_does_not_survive_the_round_trip() {
        byValue(context -> {
            AssertionError error = assertThrows(AssertionError.class, () -> context.getBean(Invoices.class).page());
            assertTrue(error.getMessage().contains("Page"), error.getMessage());
            assertTrue(error.getMessage().contains("result"), error.getMessage());
        });
    }

    @Test
    void ac_test_11_type_without_a_creator_does_not_survive_the_round_trip() {
        byValue(context -> {
            AssertionError error = assertThrows(AssertionError.class, () -> context.getBean(Invoices.class).shape());
            assertTrue(error.getMessage().contains("Shape"), error.getMessage());
        });
    }

    @Test
    void ac_test_11_field_lost_on_the_way_fails_with_the_type_and_path() {
        byValue(context -> {
            AssertionError error = assertThrows(AssertionError.class,
                    () -> context.getBean(Invoices.class).lossy(new Lossy("abc")));
            assertTrue(error.getMessage().contains("Lossy"), error.getMessage());
            assertTrue(error.getMessage().contains("argument 0"), error.getMessage());
            assertTrue(error.getMessage().contains("$.code"), error.getMessage());
            assertFalse(context.getBean(ByValueRecorder.class).release().isEmpty());
        });
    }

    @Test
    void ac_test_12_declared_exception_is_rebuilt_with_message_and_original_suppressed() {
        byValue(context -> {
            InvoiceException error = assertThrows(InvoiceException.class,
                    () -> context.getBean(Invoices.class).fail("declared"));
            assertEquals("declared failure", error.getMessage());
            assertNotSame(provider(context).thrown, error);
            assertSame(provider(context).thrown, error.getSuppressed()[0]);
        });
    }

    @Test
    void ac_test_12_unavailable_and_undeclared_exceptions_pass_as_thrown() {
        byValue(context -> {
            Invoices invoices = context.getBean(Invoices.class);
            var unavailable = assertThrows(ModuleUnavailableException.class, () -> invoices.fail("unavailable"));
            assertSame(provider(context).thrown, unavailable);
            var undeclared = assertThrows(IllegalStateException.class, () -> invoices.fail("undeclared"));
            assertSame(provider(context).thrown, undeclared);
        });
    }

    @Test
    void ac_test_13_unknown_module_id_fails_context_load() {
        TopolithTestModes.module("invoice").internal().applyTo(runner())
                .withInitializer(context -> ByValue.install(context, Set.of("ghost"))).run(context -> {
                    Throwable failure = context.getStartupFailure();
                    assertInstanceOf(IllegalStateException.class, failure);
                    assertTrue(failure.getMessage().contains("ghost"), failure.getMessage());
                });
    }

    @Test
    void ac_test_13_context_without_a_json_converter_fails_context_load() {
        TopolithTestModes.module("invoice").internal().byValue().applyTo(runner())
                .withBean(RestClient.Builder.class,
                        () -> RestClient.builder().configureMessageConverters(c -> c.disableDefaults()))
                .run(context -> {
                    Throwable failure = context.getStartupFailure();
                    assertInstanceOf(IllegalStateException.class, failure);
                    assertTrue(failure.getMessage().contains("JSON"), failure.getMessage());
                });
    }

    @Test
    void ac_test_13_none_module_gets_no_proxy_and_leaves_no_trace() {
        TopolithTestModes.module("invoice").none().byValue().applyTo(runner()).run(context -> {
            assertNull(context.getStartupFailure());
            assertTrue(context.getBeansOfType(Invoices.class).isEmpty());
            assertTrue(context.getBeansOfType(InvoicesImpl.class).isEmpty());
        });
    }

    @Test
    void ac_test_13_external_module_gets_no_proxy() throws Exception {
        try (var payments = FixtureLoader.create(); var server = new TopolithMockServer("payment")) {
            server.stub("GET", "/ping", 200, "pong");
            var host = new ApplicationContextRunner().withClassLoader(payments)
                    .withUserConfiguration(TopolithTestModesTest.Host.class);
            TopolithTestModes.module("payment").external(server).byValue().applyTo(host).run(context -> {
                assertNull(context.getStartupFailure());
                Payment payment = context.getBean(Payment.class);
                assertEquals("pong", payment.ping());
                if (payment instanceof Advised advised) {
                    for (var advisor : advised.getAdvisors()) {
                        assertFalse(advisor.getAdvice() instanceof ByValue.Advice);
                    }
                }
            });
        }
    }

    @Test
    void ac_test_13_a_mock_named_bean_is_not_wrapped() {
        byValue(context -> {
            var processor = new ByValue.Processor(Set.of());
            processor.setBeanFactory(context.getBeanFactory());
            var mock = new fixture.invoice.InvoicesFake$MockitoMock$1();
            assertSame(mock, processor.postProcessAfterInitialization(mock, "mock"));
            var real = new InvoicesImpl();
            assertNotSame(real, processor.postProcessAfterInitialization(real, "real"));
            assertInstanceOf(DefaultListableBeanFactory.class, context.getBeanFactory());
        });
    }

    @TopolithByValue("invoice")
    static class Selected { }

    static class SelectedChild extends Selected { }

    static class Unselected { }

    @TopolithByValue
    static class AllModules { }

    @TopolithByValue("invoice")
    @Nested
    class Outer {
        @Nested
        class Inner { }
    }

    @Test
    void ac_test_13_annotation_is_found_on_superclass_and_enclosing_class_and_absent_otherwise() {
        var factory = new TopolithContextCustomizerFactory();
        var selected = factory.createContextCustomizer(Selected.class, List.of());
        assertNotNull(selected);
        assertEquals(selected, factory.createContextCustomizer(SelectedChild.class, List.of()));
        assertEquals(selected, factory.createContextCustomizer(Outer.Inner.class, List.of()));
        assertNotEquals(selected, factory.createContextCustomizer(AllModules.class, List.of()));
        assertNull(factory.createContextCustomizer(Unselected.class, List.of()));
    }

    @Test
    void ac_test_13_builder_by_value_needs_the_runner_and_is_reset_by_a_new_mode() {
        var builder = TopolithTestModes.module("invoice").internal().byValue();
        assertThrows(IllegalStateException.class, () -> builder.applyTo((key, value) -> { }));
        TopolithTestModes.module("invoice").internal().byValue().none().applyTo((key, value) -> { });
    }

    @Test
    void ac_test_13_builder_by_value_before_a_mode_fails() {
        var e = assertThrows(IllegalStateException.class,
                () -> TopolithTestModes.module("invoice").byValue());
        assertTrue(e.getMessage().contains("invoice"), e.getMessage());
    }

    @Test
    void ac_test_13_context_without_a_rest_client_builder_fails_context_load() {
        TopolithTestModes.module("invoice").internal().byValue().applyTo(runner())
                .withPropertyValues("spring.autoconfigure.exclude="
                        + "org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration")
                .run(context -> {
                    Throwable failure = context.getStartupFailure();
                    assertInstanceOf(IllegalStateException.class, failure);
                    assertTrue(failure.getMessage().contains("RestClient.Builder"), failure.getMessage());
                });
    }

    @Test
    void ac_test_13_two_rest_client_builders_fail_context_load() {
        TopolithTestModes.module("invoice").internal().byValue().applyTo(runner())
                .withBean("second", RestClient.Builder.class, RestClient::builder)
                .withBean("third", RestClient.Builder.class, RestClient::builder).run(context -> {
                    Throwable failure = context.getStartupFailure();
                    assertInstanceOf(IllegalStateException.class, failure);
                    assertTrue(failure.getMessage().contains("one RestClient.Builder"), failure.getMessage());
                });
    }

    @Test
    void ac_test_08_hosts_interceptors_and_shared_builder_are_left_alone() {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var shared = RestClient.builder().requestInterceptor((request, body, execution) -> {
            calls.incrementAndGet();
            return execution.execute(request, body);
        });
        TopolithTestModes.module("invoice").internal().byValue().applyTo(runner())
                .withBean("host", RestClient.Builder.class, () -> shared).run(context -> {
                    assertNull(context.getStartupFailure());
                    context.getBean(Invoices.class).total(invoice());
                    context.getBean(Invoices.class).held();
                    assertEquals(0, calls.get());
                    // the shared builder still has its own factory: it was copied, not switched to the echo
                    var seen = new java.util.concurrent.atomic.AtomicReference<Object>();
                    shared.requestFactory((uri, method) -> {
                        seen.set(uri);
                        throw new java.io.IOException("stop");
                    });
                    RestClient own = shared.build();
                    assertThrows(RuntimeException.class,
                            () -> own.get().uri("http://example.test/").retrieve().toBodilessEntity());
                    assertEquals(1, calls.get());
                    assertNotNull(seen.get());
                });
    }

    @Test
    void ac_test_08_generic_contract_types_are_resolved_against_the_contract() {
        byValue(context -> {
            Invoices invoices = context.getBean(Invoices.class);
            Invoice argument = invoice();
            invoices.put(argument);
            Invoice stored = provider(context).last();
            assertInstanceOf(Invoice.class, stored);
            assertNotSame(argument, stored);
            Invoice back = invoices.last();
            assertInstanceOf(Invoice.class, back);
            assertEquals("first", back.name);
        });
    }

    @Test
    void ac_test_09_provider_changing_a_mutable_number_argument_fails_the_call() {
        byValue(context -> {
            var counter = new java.util.concurrent.atomic.AtomicInteger();
            AssertionError error = assertThrows(AssertionError.class, () -> context.getBean(Invoices.class)
                    .add(counter));
            assertTrue(error.getMessage().contains("argument 0"), error.getMessage());
            assertEquals(0, counter.get());
        });
    }

    @Test
    void ac_test_13_non_internal_and_unselected_modules_are_not_wrapped() {
        TopolithTestModes.module("invoice").none().applyTo(runner()).run(context -> {
            assertNull(context.getStartupFailure());
            var processor = new ByValue.Processor(Set.of());
            processor.setBeanFactory(context.getBeanFactory());
            var bean = new InvoicesImpl();
            assertSame(bean, processor.postProcessAfterInitialization(bean, "invoices"));
        });
        TopolithTestModes.module("invoice").internal().applyTo(runner()).run(context -> {
            assertNull(context.getStartupFailure());
            var other = new ByValue.Processor(Set.of("other"));
            other.setBeanFactory(context.getBeanFactory());
            var bean = new InvoicesImpl();
            assertSame(bean, other.postProcessAfterInitialization(bean, "invoices"));
            var all = new ByValue.Processor(Set.of());
            all.setBeanFactory(context.getBeanFactory());
            assertNotSame(bean, all.postProcessAfterInitialization(bean, "invoices"));
        });
    }

    @TopolithModuleMode(id = "invoice", mode = Mode.INTERNAL)
    @TopolithByValue("invoice")
    @SpringBootTest(classes = TopolithTestModesTest.Host.class)
    static class SpyScenario {
        @org.springframework.test.context.bean.override.mockito.MockitoSpyBean Invoices invoices;
    }

    @TopolithModuleMode(id = "invoice", mode = Mode.INTERNAL)
    @TopolithByValue("invoice")
    @SpringBootTest(classes = TopolithTestModesTest.Host.class)
    static class MockScenario {
        @org.springframework.test.context.bean.override.mockito.MockitoBean Invoices invoices;
    }

    @Test
    void ac_test_13_a_real_spy_bean_is_not_wrapped() throws Exception {
        var manager = new TestContextManager(SpyScenario.class);
        var instance = new SpyScenario();
        manager.prepareTestInstance(instance);
        Invoice argument = invoice();
        instance.invoices.total(argument);
        org.mockito.Mockito.verify(instance.invoices).total(org.mockito.ArgumentMatchers.same(argument));
        // the observation advice may proxy the spy (D-86), but the by-value advice must not
        assertFalse(instance.invoices instanceof Advised advised && Arrays.stream(advised.getAdvisors())
                .anyMatch(advisor -> advisor.getAdvice() instanceof ByValue.Advice));
    }

    @Test
    void ac_test_13_a_real_mock_bean_is_not_wrapped() throws Exception {
        var manager = new TestContextManager(MockScenario.class);
        var instance = new MockScenario();
        manager.prepareTestInstance(instance);
        Invoice argument = invoice();
        org.mockito.Mockito.when(instance.invoices.total(org.mockito.ArgumentMatchers.same(argument))).thenReturn(7);
        assertEquals(7, instance.invoices.total(argument));
        assertFalse(instance.invoices instanceof Advised);
    }

    @Test
    void ac_test_10_violation_recorded_before_a_test_method_fails_that_method() throws Exception {
        var manager = new TestContextManager(Scenario.class);
        var instance = new Scenario();
        manager.prepareTestInstance(instance);
        InvoicesImpl provider = AopTestUtils.getUltimateTargetObject(instance.invoices);
        provider.mutateArgument = true;
        try {
            assertThrows(AssertionError.class, () -> instance.invoices.total(invoice()));
        } finally {
            provider.mutateArgument = false;
        }
        AssertionError error = assertThrows(AssertionError.class, () -> run(manager, instance, "clean"));
        assertTrue(error.getMessage().contains("argument 0"), error.getMessage());
        run(manager, instance, "clean");
    }
}
