package io.github.rey5137.topolith.spring.boot;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import io.github.rey5137.topolith.core.TopolithCode;
import io.github.rey5137.topolith.spring.boot.errorfixture.clock.api.ClockContract;
import io.github.rey5137.topolith.spring.boot.errorfixture.host.ErrorHost;
import io.github.rey5137.topolith.spring.boot.errorfixture.unmapped.api.UnmappedContract;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.aop.support.AopUtils;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.server.servlet.context.AnnotationConfigServletWebServerApplicationContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** M8.3: a contract a module declaration lists in {@code provides}, provided by a {@code @Bean} method (D-110). */
class ListedContractsTest {
    private static final String CLOCK = ClockContract.class.getName();

    @TempDir
    Path temp;
    Path root;

    @BeforeEach
    void roots() throws IOException {
        root = Fixtures.dir(temp, "roots");
        ContractExceptionsTest.contracts(root, "clock", ClockContract.class);
    }

    private void run(CallObservationTest.Recorder recorder, Consumer<AssertableApplicationContext> check,
            String... properties) throws Exception {
        try (Fixtures.RecordingLoader loader = new Fixtures.RecordingLoader(root)) {
            new ApplicationContextRunner().withClassLoader(loader).withUserConfiguration(ErrorHost.class)
                    .withBean(ObservationRegistry.class, recorder::registry)
                    .withPropertyValues("spring.liquibase.enabled=false", "topolith.modules.clock.mode=internal")
                    .withPropertyValues(properties).run(check::accept);
        }
    }

    private static String inCaller(AssertableApplicationContext context, ClockContract clock) {
        TransactionTemplate template = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
        template.setName("caller");
        return template.execute(status -> clock.now());
    }

    @Test
    void ac_desc_15_the_declaration_provides_are_read_from_the_package_info_class() throws Exception {
        run(new CallObservationTest.Recorder(), context -> {
            assertNull(context.getStartupFailure());
            TopologyResolver.Resolution resolution = context.getBean(TopologyResolver.RESOLUTION_BEAN,
                    TopologyResolver.Resolution.class);
            assertEquals(Set.of(CLOCK), resolution.listed().get("clock"));
        });
    }

    @Test
    void ac_desc_15_a_listed_lambda_contract_runs_under_the_boundary_and_is_observed() throws Exception {
        for (String provider : List.of("lambda", "outside", "proxy")) {
            CallObservationTest.Recorder recorder = new CallObservationTest.Recorder();
            run(recorder, context -> {
                assertNull(context.getStartupFailure(), provider);
                ClockContract clock = context.getBean(ClockContract.class);
                assertTrue(AopUtils.isAopProxy(clock), provider);
                // the caller's transaction is suspended around the call (R-EXT-07)
                assertEquals("inactive:null", inCaller(context, clock), provider);
                assertEquals(1, recorder.calls().size(), provider);
                assertEquals(CLOCK, recorder.calls().get(0).getLowCardinalityKeyValue("contract").getValue());
                assertEquals("clock", recorder.calls().get(0).getLowCardinalityKeyValue("module.id").getValue());
            }, "test.clock.provider=" + provider);
        }
    }

    @Test
    void ac_desc_16_an_internal_module_without_a_bean_of_a_listed_contract_fails_with_tl0044() throws Exception {
        run(new CallObservationTest.Recorder(), context -> LoopbackTest.fails(context.getStartupFailure(),
                TopolithCode.TL0044, "module 'clock' lists " + CLOCK + " in provides", "Declare one @Bean"),
                "test.clock.provider=missing");
    }

    @Test
    void ac_desc_16_two_beans_of_a_listed_contract_fail_with_tl0005() throws Exception {
        run(new CallObservationTest.Recorder(), context -> LoopbackTest.fails(context.getStartupFailure(),
                TopolithCode.TL0005, "module 'clock' contributes 2 beans of the listed contract " + CLOCK,
                "firstClock", "secondClock"), "test.clock.provider=two");
    }

    @Test
    void ac_ext_80_a_loopback_or_exposed_module_registers_a_listed_contract_route() throws Exception {
        for (String[] properties : List.of(new String[] {"topolith.modules.clock.mode=loopback"},
                new String[] {"topolith.modules.clock.mode=internal", "topolith.modules.clock.expose.enabled=true"})) {
            try (Fixtures.RecordingLoader loader = new Fixtures.RecordingLoader(root)) {
                new WebApplicationContextRunner(AnnotationConfigServletWebServerApplicationContext::new)
                        .withClassLoader(loader).withUserConfiguration(ErrorHost.class)
                        .withPropertyValues("server.port=0", "spring.liquibase.enabled=false")
                        .withPropertyValues(properties)
                        .run(context -> {
                            assertNull(context.getStartupFailure());
                            var mapping = context.getBean("requestMappingHandlerMapping",
                                    RequestMappingHandlerMapping.class);
                            assertTrue(mapping.getHandlerMethods().keySet().stream()
                                    .anyMatch(info -> info.getPatternValues().contains("/clock/now")));
                        });
            }
        }
    }

    @Test
    void ac_ext_81_an_unannotated_listed_method_fails_with_tl0026() throws Exception {
        ContractExceptionsTest.contracts(root, "unmapped", UnmappedContract.class);
        try (Fixtures.RecordingLoader loader = new Fixtures.RecordingLoader(root)) {
            new WebApplicationContextRunner(AnnotationConfigServletWebServerApplicationContext::new)
                    .withClassLoader(loader).withUserConfiguration(ErrorHost.class)
                    .withPropertyValues("server.port=0", "spring.liquibase.enabled=false",
                            "topolith.modules.clock.mode=none", "topolith.modules.unmapped.mode=internal",
                            "topolith.modules.unmapped.expose.enabled=true")
                    .run(context -> LoopbackTest.fails(context.getStartupFailure(), TopolithCode.TL0026,
                            "UnmappedContract.missing", "@HttpExchange"));
        }
    }

    @Test
    void ac_ext_81_a_clashing_listed_route_fails_with_tl0005() throws Exception {
        try (Fixtures.RecordingLoader loader = new Fixtures.RecordingLoader(root)) {
            new WebApplicationContextRunner(AnnotationConfigServletWebServerApplicationContext::new)
                    .withClassLoader(loader).withUserConfiguration(ErrorHost.class, ClashController.class)
                    .withPropertyValues("server.port=0", "spring.liquibase.enabled=false",
                            "topolith.modules.clock.mode=internal", "topolith.modules.clock.expose.enabled=true")
                    .run(context -> LoopbackTest.fails(context.getStartupFailure(), TopolithCode.TL0005,
                            "ClockContract.now", "ClashController", "clash"));
        }
    }

    @RestController
    static class ClashController {
        @GetMapping("/clock/now")
        String clash() {
            return "clash";
        }
    }

    @Test
    void ac_desc_16_an_unreadable_module_declaration_fails_with_tl0013() throws Exception {
        ContractExceptionsTest.contracts(root, "broken", "com.example.broken", "com.example.broken.api");
        Fixtures.resource(root, "com/example/broken/package-info.class", "not a class file");
        run(new CallObservationTest.Recorder(), context -> LoopbackTest.fails(context.getStartupFailure(),
                TopolithCode.TL0013, "The declaration of module 'broken'", "package-info.class",
                "Rebuild the module artifact"), "topolith.modules.broken.mode=internal");
    }

    @Test
    void inv_2_a_none_module_is_not_checked_and_its_declaration_not_read() throws Exception {
        run(new CallObservationTest.Recorder(), context -> {
            assertNull(context.getStartupFailure());
            TopologyResolver.Resolution resolution = context.getBean(TopologyResolver.RESOLUTION_BEAN,
                    TopologyResolver.Resolution.class);
            assertTrue(resolution.listed().isEmpty());
            assertFalse(context.containsBean("topolithListedContractsCheck"));
            assertTrue(context.getBeansOfType(ClockContract.class).isEmpty());
        }, "topolith.modules.clock.mode=none", "test.clock.provider=missing");
    }
}
