package io.github.rey5137.topolith.spring.test;

import java.math.BigDecimal;
import java.util.List;
import java.util.function.Consumer;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import fixture.charge.api.ChargeOrder;
import fixture.charge.api.Charges;
import fixture.charge.api.Receipt;
import io.github.rey5137.topolith.core.ModuleDescriptor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.junit.jupiter.api.Assertions.*;

/** The mode matrix's negative fixtures (AC-TEST-15): contracts that only work in-process fail the parity run. */
class MutablePaymentFixtureTest {
    private static ClassLoader original;
    private static java.net.URLClassLoader fixtures;

    @BeforeAll
    static void installFixtures() {
        original = Thread.currentThread().getContextClassLoader();
        fixtures = FixtureLoader.create("mutable-descriptors/");
        Thread.currentThread().setContextClassLoader(fixtures);
    }

    @AfterAll
    static void removeFixtures() throws java.io.IOException {
        Thread.currentThread().setContextClassLoader(original);
        fixtures.close();
    }

    private static void internal(boolean byValue, Consumer<AssertableApplicationContext> test) {
        TopolithTestModes.Builder charge = TopolithTestModes.module("charge").internal();
        if (byValue) { charge.byValue(); }
        charge.applyTo(new ApplicationContextRunner().withClassLoader(fixtures)
                .withUserConfiguration(TopolithTestModesTest.Host.class)).run(context -> {
                    assertNull(context.getStartupFailure());
                    test.accept(context);
                });
    }

    private static ChargeOrder order() { return new ChargeOrder("order-1", new BigDecimal("12.50")); }

    @Test
    void ac_test_15_without_the_proxy_the_mutable_provider_changes_the_callers_objects_unseen() {
        internal(false, context -> {
            Charges charges = context.getBean(Charges.class);
            ChargeOrder order = order();
            charges.charge(order);
            assertEquals("CHARGED", order.status);
            Receipt receipt = charges.authorize(order());
            charges.capture(receipt.id);
            assertEquals("CAPTURED", receipt.status);
        });
    }

    @Test
    void ac_test_15_provider_changing_its_argument_fails_the_by_value_run() {
        internal(true, context -> {
            ChargeOrder order = order();
            AssertionError error = assertThrows(AssertionError.class,
                    () -> context.getBean(Charges.class).charge(order));
            assertTrue(error.getMessage().contains("module 'charge'"), error.getMessage());
            assertTrue(error.getMessage().contains("Charges.charge, argument 0"), error.getMessage());
            assertTrue(error.getMessage().contains("$.status"), error.getMessage());
            assertNull(order.status);
        });
    }

    @Test
    void ac_test_15_provider_changing_a_result_it_returned_fails_the_by_value_run() {
        internal(true, context -> {
            Charges charges = context.getBean(Charges.class);
            Receipt receipt = charges.authorize(order());
            charges.capture(receipt.id);
            assertEquals("AUTHORIZED", receipt.status);
            AssertionError error = assertThrows(AssertionError.class, () -> charges.authorize(order()));
            assertTrue(error.getMessage().contains("module 'charge'"), error.getMessage());
            assertTrue(error.getMessage().contains("Charges.authorize, result"), error.getMessage());
            assertTrue(error.getMessage().contains("$.status"), error.getMessage());
            assertEquals(1, context.getBean(ByValueRecorder.class).release().size());
        });
    }

    @Test
    void ac_test_15_page_contract_fails_the_by_value_round_trip() {
        internal(true, context -> {
            AssertionError error = assertThrows(AssertionError.class, () -> context.getBean(Charges.class).history());
            assertTrue(error.getMessage().contains("Charges.history, result"), error.getMessage());
            assertTrue(error.getMessage().contains("Page"), error.getMessage());
        });
    }

    @Test
    void ac_test_15_page_contract_fails_the_architecture_check() {
        List<ModuleDescriptor> modules = List.of(ModuleDescriptor.builder("charge").packageName("fixture.charge")
                .apiPackages(List.of("fixture.charge.api")).apiVersion("1.0.0").build());
        JavaClasses classes = new ClassFileImporter().importPackages("fixture.charge");
        AssertionError error = assertThrows(AssertionError.class,
                () -> TopolithArchitecture.verify(modules, List.of(), classes));
        assertTrue(error.getMessage().contains(TopolithArchitecture.Rule.CONTRACT_TYPES.description()),
                error.getMessage());
        assertTrue(error.getMessage().contains("module 'charge'"), error.getMessage());
        assertTrue(error.getMessage().contains("Charges"), error.getMessage());
        assertTrue(error.getMessage().contains("org.springframework.data.domain.Page"), error.getMessage());
    }
}
