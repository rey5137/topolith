package io.github.rey5137.topolith.spring.test;

import java.util.List;
import java.util.Map;

import fixture.loopcheck.billing.InternalTests;
import fixture.loopcheck.cart.ExternalTests;
import fixture.loopcheck.shop.InsideTests;
import fixture.loopcheck.shop.deep.DeepTests;
import fixture.loopcheck.shopper.SiblingTests;
import io.github.rey5137.topolith.core.ModuleDescriptor;
import io.github.rey5137.topolith.core.TopolithCode;
import io.github.rey5137.topolith.core.TopolithException;
import io.github.rey5137.topolith.core.Topology;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.test.context.TestContext;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** AC-TEST-20: a test class in a loopback module's package fails with TL0032 (D-85); other locations are untouched. */
class TopolithLoopbackPackageTest {
    private static ModuleDescriptor module(String id) {
        return ModuleDescriptor.builder(id).packageName("fixture.loopcheck." + id)
                .apiPackages(List.of("fixture.loopcheck." + id + ".api")).apiVersion("1.0.0")
                .source("jar:/" + id + ".jar!/" + id + ".properties").build();
    }

    /** Runs the listener's check for {@code testClass} against shop (loopback), billing (internal), cart (external). */
    private static void prepare(Class<?> testClass) {
        Topology topology = Topology.resolve(List.of(module("shop"), module("billing"), module("cart")), List.of(),
                Map.of("shop", "loopback", "billing", "internal", "cart", "external"), null);
        try (GenericApplicationContext beans = new GenericApplicationContext()) {
            beans.registerBean(Topology.class, () -> topology);
            beans.refresh();
            TestContext context = mock(TestContext.class);
            when(context.getApplicationContext()).thenReturn(beans);
            doReturn(testClass).when(context).getTestClass();
            new TopolithMockTestExecutionListener().prepareTestInstance(context);
        }
    }

    private static void fails(Class<?> testClass) {
        TopolithException failure = assertThrows(TopolithException.class, () -> prepare(testClass));
        assertEquals(TopolithCode.TL0032, failure.code());
        assertTrue(failure.getMessage().contains(testClass.getName()), failure.getMessage());
        assertTrue(failure.getMessage().contains("'shop'"), failure.getMessage());
        assertTrue(failure.getMessage().contains("Move the test class out"), failure.getMessage());
    }

    @Test
    void ac_test_20_a_test_class_in_a_loopback_module_package_fails_with_tl0032() { fails(InsideTests.class); }

    @Test
    void ac_test_20_a_subpackage_of_a_loopback_module_package_fails_with_tl0032() { fails(DeepTests.class); }

    @Test
    void ac_test_20_a_static_nested_test_class_in_the_loopback_package_fails_with_tl0032() {
        fails(InsideTests.Nested.class);
    }

    @Test
    void ac_test_20_a_test_class_outside_every_module_package_starts() {
        assertDoesNotThrow(() -> prepare(TopolithLoopbackPackageTest.class));
    }

    @Test
    void ac_test_20_a_sibling_package_sharing_the_modules_name_prefix_starts() {
        assertDoesNotThrow(() -> prepare(SiblingTests.class));
    }

    @Test
    void ac_test_20_a_test_class_in_an_internal_or_external_module_package_starts() {
        assertDoesNotThrow(() -> prepare(InternalTests.class));
        assertDoesNotThrow(() -> prepare(ExternalTests.class));
    }

    @Test
    void ac_test_20_a_context_without_a_topology_is_left_alone() {
        try (GenericApplicationContext beans = new GenericApplicationContext()) {
            beans.refresh();
            TestContext context = mock(TestContext.class);
            when(context.getApplicationContext()).thenReturn(beans);
            doReturn(InsideTests.class).when(context).getTestClass();
            assertDoesNotThrow(() -> new TopolithMockTestExecutionListener().prepareTestInstance(context));
        }
    }
}
