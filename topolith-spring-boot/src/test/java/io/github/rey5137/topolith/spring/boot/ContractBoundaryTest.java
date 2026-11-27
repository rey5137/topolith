package io.github.rey5137.topolith.spring.boot;

import java.io.IOException;
import java.nio.file.Path;
import java.util.function.Consumer;

import io.github.rey5137.topolith.spring.boot.errorfixture.boundary.BoundaryClient;
import io.github.rey5137.topolith.spring.boot.errorfixture.boundary.BoundaryService;
import io.github.rey5137.topolith.spring.boot.errorfixture.boundary.FinalBoundaryProvider;
import io.github.rey5137.topolith.spring.boot.errorfixture.boundary.FinalPlainProvider;
import io.github.rey5137.topolith.core.TopolithCode;
import io.github.rey5137.topolith.core.TopolithException;
import io.github.rey5137.topolith.spring.boot.errorfixture.boundary.api.BoundaryContract;
import io.github.rey5137.topolith.spring.boot.errorfixture.host.ErrorHost;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.aop.support.AopUtils;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.TransactionSuspensionNotSupportedException;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** M2.4: the D-2 transaction boundary of an internal contract call (R-EXT-07, D-47). */
class ContractBoundaryTest {
    @TempDir
    Path temp;
    Path root;

    @BeforeEach
    void roots() throws IOException {
        root = Fixtures.dir(temp, "roots");
        ContractExceptionsTest.contracts(root, "boundary", BoundaryContract.class);
    }

    private void run(String mode, ClassLoader parent, Consumer<AssertableApplicationContext> check) throws Exception {
        run(mode, parent, check, ErrorHost.class);
    }

    private void run(String mode, ClassLoader parent, Consumer<AssertableApplicationContext> check,
            Class<?>... hosts) throws Exception {
        try (Fixtures.RecordingLoader loader = parent == null ? new Fixtures.RecordingLoader(root)
                : new Fixtures.RecordingLoader(parent, root)) {
            new ApplicationContextRunner().withClassLoader(loader).withUserConfiguration(hosts)
                    .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                    .withPropertyValues("spring.liquibase.enabled=false", "topolith.modules.boundary.mode=" + mode)
                    .run(context -> {
                        assertNull(context.getStartupFailure());
                        check.accept(context);
                    });
        }
    }

    /** Runs {@code call} inside a caller transaction named {@code caller}. */
    private static String inCaller(AssertableApplicationContext context, java.util.function.Supplier<String> call) {
        TransactionTemplate template = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
        template.setName("caller");
        return template.execute(status -> BoundaryService.state() + "|" + call.get() + "|" + BoundaryService.state());
    }

    private static double calls(AssertableApplicationContext context) {
        return context.getBean(MeterRegistry.class).find(ContractTransactionBoundary.METRIC)
                .tags("module", "boundary", "contract", BoundaryContract.class.getName()).counters().stream()
                .mapToDouble(counter -> counter.count()).sum();
    }

    @Test
    void ac_ext_16_a_caller_transaction_is_suspended_and_resumes() throws Exception {
        run("internal", null, context -> {
            BoundaryContract contract = context.getBean(BoundaryContract.class);
            assertTrue(AopUtils.isAopProxy(contract));
            String result = inCaller(context, contract::plain);
            // the callee ran outside the caller's transaction, which is active again afterwards
            assertEquals("active:caller|inactive:null|active:caller", result);
            // the module's own code goes through the contract too
            String own = inCaller(context, context.getBean(BoundaryClient.class)::callPlain);
            assertEquals("active:caller|inactive:null|active:caller", own);
            // outside any transaction the call is untouched
            assertEquals("inactive:null", contract.plain());
        });
    }

    @Test
    void ac_ext_17_the_callee_own_transaction_policy_applies_and_a_caller_transaction_is_counted()
            throws Exception {
        run("internal", null, context -> {
            BoundaryContract contract = context.getBean(BoundaryContract.class);
            assertEquals(0, calls(context));
            // the callee's @Transactional starts its own transaction: it is not the caller's
            String result = inCaller(context, contract::own);
            assertEquals("active:caller|active:" + BoundaryService.class.getName() + ".own|active:caller", result);
            assertEquals(1, calls(context));
            inCaller(context, contract::plain);
            assertEquals(2, calls(context));
            // no caller transaction, no warning
            contract.plain();
            contract.own();
            assertEquals(2, calls(context));
            // a method that is not part of the contract is not a boundary
            String helper = inCaller(context, ((BoundaryService) contract)::helper);
            assertEquals("active:caller|active:caller|active:caller", helper);
            assertEquals(2, calls(context));
        });
    }

    @Test
    void ac_ext_17_without_micrometer_the_boundary_still_applies() throws Exception {
        run("internal", new FilteredClassLoader("io.micrometer"), context -> {
            assertEquals("active:caller|inactive:null|active:caller",
                    inCaller(context, context.getBean(BoundaryContract.class)::plain));
        });
    }

    /** A second manager, named to sort after Boot's, that fails to suspend or to resume on demand. */
    static final class FlakyManager implements PlatformTransactionManager {
        volatile boolean failSuspend;
        volatile boolean failResume;

        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            if (failSuspend) {
                throw new TransactionSuspensionNotSupportedException("cannot suspend");
            }
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
            if (failResume) {
                throw new IllegalStateException("cannot resume (commit)");
            }
        }

        @Override
        public void rollback(TransactionStatus status) {
            if (failResume) {
                throw new IllegalStateException("cannot resume (rollback)");
            }
        }
    }

    /** Defines the flaky manager after auto-configuration, so Boot's own manager does not back off. */
    @Configuration(proxyBeanMethods = false)
    static class FlakyConfiguration {
        @Bean
        static BeanDefinitionRegistryPostProcessor flakyManager() {
            return new BeanDefinitionRegistryPostProcessor() {
                @Override
                public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) {
                    registry.registerBeanDefinition("zzFlakyManager",
                            new RootBeanDefinition(FlakyManager.class));
                }

                @Override
                public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
                }
            };
        }
    }

    @Test
    void ac_ext_16_a_final_provider_fails_startup_with_tl0029() throws Exception {
        try (Fixtures.RecordingLoader loader = new Fixtures.RecordingLoader(root)) {
            new ApplicationContextRunner().withClassLoader(loader)
                    .withUserConfiguration(ErrorHost.class, FinalProviderConfiguration.class)
                    .withPropertyValues("spring.liquibase.enabled=false", "topolith.modules.boundary.mode=internal")
                    .run(context -> {
                        Throwable cause = context.getStartupFailure();
                        while (cause != null && !(cause instanceof TopolithException)) {
                            cause = cause.getCause();
                        }
                        assertTrue(cause instanceof TopolithException);
                        var diagnostics = ((TopolithException) cause).diagnostics();
                        assertEquals(TopolithCode.TL0029, diagnostics.get(0).code());
                        assertTrue(diagnostics.get(0).description().contains(FinalBoundaryProvider.class.getName()));
                        assertTrue(diagnostics.get(0).action().contains("non-final"));
                        assertTrue(diagnostics.get(0).description().contains("final handler"));
                    });
        }
        // with spring-tx reported absent (so no boundary), the final handler still fails: the observation needs the
        // same proxy (D-89, AC-EXT-46)
        try (Fixtures.RecordingLoader loader = new Fixtures.RecordingLoader(
                new FilteredClassLoader("org.springframework.transaction"), root)) {
            new ApplicationContextRunner().withClassLoader(loader)
                    .withUserConfiguration(ErrorHost.class, FinalProviderConfiguration.class)
                    .withPropertyValues("spring.liquibase.enabled=false", "topolith.modules.boundary.mode=internal")
                    .run(context -> assertTrue(String.valueOf(context.getStartupFailure()).contains("TL0029")));
        }
    }

    @Test
    void ac_ext_16_a_final_provider_that_is_not_a_handler_takes_the_boundary() throws Exception {
        try (Fixtures.RecordingLoader loader = new Fixtures.RecordingLoader(root)) {
            new ApplicationContextRunner().withClassLoader(loader)
                    .withUserConfiguration(ErrorHost.class, FinalPlainConfiguration.class)
                    .withPropertyValues("spring.liquibase.enabled=false", "topolith.modules.boundary.mode=internal")
                    .run(context -> {
                        assertNull(context.getStartupFailure());
                        BoundaryContract provider = (BoundaryContract) context.getBean("finalPlainProvider");
                        assertTrue(AopUtils.isAopProxy(provider));
                        assertEquals("active:caller|inactive:null|active:caller", inCaller(context, provider::plain));
                    });
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class FinalPlainConfiguration {
        // not an injection candidate, so the module's own client keeps its one provider
        @Bean(autowireCandidate = false)
        FinalPlainProvider finalPlainProvider() {
            return new FinalPlainProvider();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class FinalProviderConfiguration {
        @Bean
        FinalBoundaryProvider finalBoundaryProvider() {
            return new FinalBoundaryProvider();
        }
    }

    @Test
    void ac_ext_16_a_manager_that_cannot_suspend_leaves_the_suspended_ones_resumed() throws Exception {
        run("internal", null, context -> {
            FlakyManager flaky = context.getBean(FlakyManager.class);
            flaky.failSuspend = true;
            TransactionTemplate template = new TransactionTemplate(context.getBean("transactionManager",
                    PlatformTransactionManager.class));
            template.setName("caller");
            String after = template.execute(status -> {
                assertThrows(TransactionSuspensionNotSupportedException.class,
                        () -> context.getBean(BoundaryContract.class).plain());
                return BoundaryService.state();
            });
            // the first manager had suspended the caller's transaction: it is back, with its name
            assertEquals("active:caller", after);
        }, ErrorHost.class, FlakyConfiguration.class);
    }

    @Test
    void ac_ext_16_a_failing_resume_keeps_the_call_failure() throws Exception {
        run("internal", null, context -> {
            FlakyManager flaky = context.getBean(FlakyManager.class);
            flaky.failResume = true;
            TransactionTemplate template = new TransactionTemplate(context.getBean("transactionManager",
                    PlatformTransactionManager.class));
            template.setName("caller");
            template.executeWithoutResult(status -> {
                // the callee's failure is the one reported, the resume failure rides along
                IllegalStateException failure = assertThrows(IllegalStateException.class,
                        () -> context.getBean(BoundaryContract.class).fail());
                assertEquals("callee failed", failure.getMessage());
                assertEquals(1, failure.getSuppressed().length);
                assertEquals("active:caller", BoundaryService.state());
            });
        }, ErrorHost.class, FlakyConfiguration.class);
    }

    @Test
    void inv_2_a_none_module_gets_no_boundary() throws Exception {
        run("none", null, context -> {
            assertTrue(context.getBeansOfType(BoundaryContract.class).isEmpty());
            assertFalse(context.containsBean("topolithContractBoundary"));
        });
    }
}
