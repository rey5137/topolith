package io.github.rey5137.topolith.spring.boot;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import io.github.rey5137.topolith.core.TopolithCode;
import io.github.rey5137.topolith.core.TopolithException;
import io.github.rey5137.topolith.spring.boot.errorfixture.account.api.AccountContract;
import io.github.rey5137.topolith.spring.boot.errorfixture.host.ErrorHost;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.LazyInitializationBeanFactoryPostProcessor;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.HandlerTypePredicate;
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.server.servlet.context.AnnotationConfigServletWebServerApplicationContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.web.util.matcher.RequestMatcher;

import static io.github.rey5137.topolith.spring.boot.ProviderErrorsTest.get;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** M2.4 exposure: the fixed {@code /<id>} prefix, route removal, the security hook and the version endpoint (D-44). */
class ExposureTest {
    @TempDir
    Path temp;
    Path root;

    @BeforeEach
    void roots() throws IOException {
        root = Fixtures.dir(temp, "roots");
        ContractExceptionsTest.contracts(root, "account", AccountContract.class);
        ContractExceptionsTest.contracts(root, "payment");
    }

    /** A web host on a random port; the account module is internal, with {@code properties} on top. */
    private void serve(Consumer<AssertableWebApplicationContext> check, String... properties) throws Exception {
        serve(null, check, properties);
    }

    private void serve(ClassLoader parent, Consumer<AssertableWebApplicationContext> check, String... properties)
            throws Exception {
        serve(parent, List.of(), check, properties);
    }

    private void serve(ClassLoader parent, List<Class<?>> more, Consumer<AssertableWebApplicationContext> check,
            String... properties) throws Exception {
        try (Fixtures.RecordingLoader loader = parent == null ? new Fixtures.RecordingLoader(root)
                : new Fixtures.RecordingLoader(parent, root)) {
            new WebApplicationContextRunner(AnnotationConfigServletWebServerApplicationContext::new)
                    .withClassLoader(loader).withUserConfiguration(ErrorHost.class)
                    .withUserConfiguration(more.toArray(Class<?>[]::new))
                    .withPropertyValues("server.port=0", "spring.liquibase.enabled=false",
                            "topolith.modules.account.mode=internal", "topolith.modules.payment.mode=none")
                    .withPropertyValues(properties).run(context -> {
                        assertNull(context.getStartupFailure());
                        check.accept(context);
                    });
        }
    }

    private void fail(TopolithCode code, String... properties) throws Exception {
        try (Fixtures.RecordingLoader loader = new Fixtures.RecordingLoader(root)) {
            new ApplicationContextRunner().withClassLoader(loader).withUserConfiguration(ErrorHost.class)
                    .withPropertyValues("spring.liquibase.enabled=false", "topolith.modules.payment.mode=none")
                    .withPropertyValues(properties)
                    .run(context -> assertFailure(context.getStartupFailure(), code, properties));
        }
    }

    private static void assertFailure(Throwable startupFailure, TopolithCode code, String... properties) {
        Throwable cause = startupFailure;
        while (cause != null && !(cause instanceof TopolithException)) {
            cause = cause.getCause();
        }
        assertNotNull(cause, Arrays.toString(properties));
        assertEquals(List.of(code), ((TopolithException) cause).diagnostics().stream().map(d -> d.code()).toList());
    }

    @Test
    void ac_ext_02_a_contract_implementation_is_served_by_a_thin_controller() throws Exception {
        serve(context -> {
            // AccountController implements AccountContract and holds no mapping of its own: the contract's
            // @GetExchange paths map it, under the module prefix
            ProviderErrorsTest.Answer answer = get(context, "/account/find?case=ok");
            assertEquals(200, answer.status(), answer.body());
            assertEquals("found", answer.body());
        }, "topolith.modules.account.expose.enabled=true");
    }

    @Test
    void ac_ext_11_an_exposed_module_uses_its_id_prefix_in_each_topology() throws Exception {
        Consumer<AssertableWebApplicationContext> prefixed = context -> {
            assertEquals(200, get(context, "/account/find?case=ok").status());
            assertEquals(404, get(context, "/find?case=ok").status());
            assertEquals(404, get(context, "/account/account/find?case=ok").status());
            assertEquals(Set.of("/account"), context.getBean(ExposedEndpoints.class).prefixes());
        };
        serve(prefixed, "topolith.modules.account.expose.enabled=true");
        // a standalone provider, the other topology of the same module
        serve(prefixed, "topolith.modules.account.expose.enabled=true", "topolith.standalone=account");
    }

    /** A host that prefixes every {@code @RestController}, ahead of Topolith's configurer in bean order. */
    @Configuration(proxyBeanMethods = false)
    @Order(-100)
    static class HostPrefix implements WebMvcConfigurer {
        @Override
        public void configurePathMatch(PathMatchConfigurer configurer) {
            configurer.addPathPrefix("/api", HandlerTypePredicate.forAnnotation(RestController.class));
        }
    }

    @Test
    void ac_ext_11_a_host_path_prefix_does_not_take_an_exposed_module_from_its_id() throws Exception {
        serve(null, List.of(HostPrefix.class), context -> {
            assertEquals(200, get(context, "/account/find?case=ok").status());
            assertEquals(404, get(context, "/api/find?case=ok").status());
            // the host's own controllers still get the host prefix
            assertEquals(418, get(context, "/api/host/fail").status());
        }, "topolith.modules.account.expose.enabled=true");
    }

    @Test
    void ac_val_06_a_module_wildcard_route_does_not_shadow_the_version_endpoint() throws Exception {
        serve(context -> {
            assertEquals("wildcard", get(context, "/account/other/route").body());
            ProviderErrorsTest.Answer answer = get(context, "/account/.topolith/api-version");
            assertEquals("1.0.0", answer.body());
        }, "topolith.modules.account.expose.enabled=true", "test.wildcard=true");
    }

    @Test
    void ac_ext_12_exposure_is_off_by_default_and_disabling_removes_the_routes() throws Exception {
        Consumer<AssertableWebApplicationContext> off = context -> {
            assertEquals(404, get(context, "/account/find?case=ok").status());
            assertEquals(404, get(context, "/find?case=ok").status());
            assertEquals(404, get(context, "/account/.topolith/api-version").status());
            // a host route is untouched: its advice answers the exception
            assertEquals(418, get(context, "/host/fail").status());
            assertTrue(context.getBean(ExposedEndpoints.class).isEmpty());
        };
        serve(off);
        serve(off, "topolith.modules.account.expose.enabled=false");
    }

    @Test
    void ac_ext_12_exposure_of_a_module_that_is_not_internal_fails() throws Exception {
        for (String mode : List.of("none", "external")) {
            fail(TopolithCode.TL0003, "topolith.modules.account.mode=" + mode,
                    "topolith.modules.account.remote.url=http://localhost:1",
                    "topolith.modules.account.expose.enabled=true");
        }
        fail(TopolithCode.TL0003, "topolith.modules.account.mode=internal",
                "topolith.modules.account.expose.enabled=yes please");
    }

    @Test
    void ac_ext_11_exposure_on_a_reactive_host_fails_with_tl0028() throws Exception {
        try (Fixtures.RecordingLoader loader = new Fixtures.RecordingLoader(root)) {
            new ReactiveWebApplicationContextRunner().withClassLoader(loader).withUserConfiguration(ErrorHost.class)
                    .withPropertyValues("spring.liquibase.enabled=false", "topolith.modules.account.mode=internal",
                            "topolith.modules.payment.mode=none", "topolith.modules.account.expose.enabled=true")
                    .run(context -> assertFailure(context.getStartupFailure(), TopolithCode.TL0028));
            new ReactiveWebApplicationContextRunner().withClassLoader(loader).withUserConfiguration(ErrorHost.class)
                    .withPropertyValues("spring.liquibase.enabled=false", "topolith.modules.account.mode=internal",
                            "topolith.modules.payment.mode=none")
                    .run(context -> assertNull(context.getStartupFailure()));
            // lazy initialization must not let the exposure through
            new ReactiveWebApplicationContextRunner().withClassLoader(loader).withUserConfiguration(ErrorHost.class)
                    .withInitializer(context -> context.addBeanFactoryPostProcessor(
                            new LazyInitializationBeanFactoryPostProcessor()))
                    .withPropertyValues("spring.liquibase.enabled=false", "topolith.modules.account.mode=internal",
                            "topolith.modules.payment.mode=none", "topolith.modules.account.expose.enabled=true")
                    .run(context -> assertFailure(context.getStartupFailure(), TopolithCode.TL0028));
        }
    }

    @Test
    void ac_ext_13_the_host_gets_a_matcher_over_the_exposed_prefixes_and_no_filter_chain() throws Exception {
        serve(context -> {
            ExposedEndpoints endpoints = context.getBean(ExposedEndpoints.class);
            assertEquals(Set.of("/account/**"), endpoints.patterns());
            RequestMatcher matcher = context.getBean(ExposedRequestMatcher.class);
            assertTrue(matcher.matches(request("/account/find")));
            assertTrue(matcher.matches(request("/account/.topolith/api-version")));
            assertFalse(matcher.matches(request("/host/fail")));
            assertFalse(matcher.matches(request("/accounting/find")));
            // Topolith registers no chain of its own (INV-4): the host builds it with securityMatcher(...)
            assertTrue(context.getBeansOfType(RequestMatcher.class).size() == 1);
            assertEquals(0, context.getBeanNamesForType(
                    org.springframework.security.web.SecurityFilterChain.class).length);
        }, "topolith.modules.account.expose.enabled=true");
        // without Spring Security the prefixes are still there and no matcher bean is
        serve(new FilteredClassLoader("org.springframework.security"), context -> {
            assertEquals(Set.of("/account"), context.getBean(ExposedEndpoints.class).prefixes());
            assertEquals(0, context.getBeanNamesForType(ExposedRequestMatcher.class).length);
        }, "topolith.modules.account.expose.enabled=true");
        // an unexposed module leaves the matcher empty
        serve(context -> assertFalse(context.getBean(ExposedRequestMatcher.class)
                .matches(request("/account/find"))));
    }

    @Test
    void ac_ext_14_exposing_two_modules_adds_no_discovery_registration() throws Exception {
        ContractExceptionsTest.contracts(root, "payment");
        serve(context -> {
            assertEquals(Set.of("/account", "/payment"), context.getBean(ExposedEndpoints.class).prefixes());
            // Topolith contributes one prefix per module and no registration of its own: no bean is a
            // registration, and none of its bean types lives in a discovery or service-registry package
            for (String name : context.getBeanDefinitionNames()) {
                Class<?> type = context.getType(name);
                if (type != null && type.getName().startsWith("io.github.rey5137.topolith")) {
                    assertFalse(type.getName().contains("Registration") || type.getName().contains("Registry"),
                            type.getName());
                }
            }
            assertFalse(context.getBeansOfType(Object.class).values().stream().anyMatch(bean -> bean.getClass()
                    .getName().startsWith("org.springframework.cloud.client.serviceregistry")));
        }, "topolith.modules.payment.mode=internal", "topolith.modules.account.expose.enabled=true",
                "topolith.modules.payment.expose.enabled=true");
    }

    @Test
    void ac_val_06_the_provider_serves_its_api_version() throws Exception {
        serve(context -> {
            ProviderErrorsTest.Answer answer = get(context, "/account/.topolith/api-version");
            assertEquals(200, answer.status());
            assertTrue(answer.contentType().startsWith("text/plain"), answer.contentType());
            assertEquals("1.0.0", answer.body());
        }, "topolith.modules.account.expose.enabled=true");
    }

    @Test
    void inv_2_a_none_module_contributes_no_route_matcher_entry_or_advice() throws Exception {
        serve(context -> {
            assertEquals(Set.of("/account"), context.getBean(ExposedEndpoints.class).prefixes());
            assertEquals(404, get(context, "/payment/.topolith/api-version").status());
            assertFalse(context.getBean(ExposedRequestMatcher.class).matches(request("/payment/anything")));
        }, "topolith.modules.account.expose.enabled=true");
        // exposure keys on a none module fail startup instead of activating anything
        fail(TopolithCode.TL0003, "topolith.modules.account.mode=internal",
                "topolith.modules.payment.expose.enabled=true");
    }

    private static MockHttpServletRequest request(String path) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setServletPath(path);
        return request;
    }
}
