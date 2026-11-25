package io.github.rey5137.topolith.spring.boot;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

import io.github.rey5137.topolith.core.Diagnostic;
import io.github.rey5137.topolith.core.TopolithCode;
import io.github.rey5137.topolith.core.TopolithException;
import io.github.rey5137.topolith.spring.boot.Fixtures.RecordingLoader;
import io.github.rey5137.topolith.spring.boot.fixture.catalog.CatalogRepository;
import io.github.rey5137.topolith.spring.boot.fixture.host.Hosts.Host;
import io.github.rey5137.topolith.spring.boot.fixture.mart.MartApplication;
import io.github.rey5137.topolith.spring.boot.fixture.mart.MartRepository;
import io.github.rey5137.topolith.spring.boot.fixture.quiet.QuietHost;
import io.github.rey5137.topolith.spring.boot.fixture.quiet.WideHost;
import io.github.rey5137.topolith.spring.boot.fixture.shelf.MappedRepository;
import io.github.rey5137.topolith.spring.boot.fixture.shelf.ProfiledRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactoryBean;

import static io.github.rey5137.topolith.spring.boot.Fixtures.BASE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/** D-36 acceptance tests: a repository interface no registration covers fails with TL0024 (AC-MODE-30). */
class UnregisteredRepositoryTest {
    private static final String CATALOG = BASE + ".catalog";
    /** Topolith's JPA registration off, so the catalog's repository has no registration of any store. */
    private static final String NO_JPA = "spring.data.jpa.repositories.enabled=false";

    @TempDir
    Path temp;

    private Path catalogRoot;
    private Path ledgerRoot;
    private Path martRoot;
    private Path shelfRoot;

    @BeforeEach
    void fixtures() throws Exception {
        catalogRoot = Fixtures.dir(temp, "catalog");
        Fixtures.module(catalogRoot, "catalog", CATALOG, List.of(CATALOG + ".api"));
        martRoot = Fixtures.dir(temp, "mart");
        Fixtures.module(martRoot, "mart", BASE + ".mart", List.of(BASE + ".mart.api"));
        shelfRoot = Fixtures.dir(temp, "shelf");
        Fixtures.module(shelfRoot, "shelf", BASE + ".shelf", List.of(BASE + ".shelf.api"));
        ledgerRoot = Fixtures.dir(temp, "ledger");
        Fixtures.module(ledgerRoot, "ledger", Fixtures.LEDGER, List.of(Fixtures.LEDGER + ".api"));
    }

    private void run(Class<?> host, List<Path> roots, Consumer<BeanDefinitionRegistry> extra, List<String> properties,
            Consumer<AssertableApplicationContext> assertions) throws Exception {
        try (RecordingLoader loader = new RecordingLoader(roots.toArray(Path[]::new))) {
            new ApplicationContextRunner().withClassLoader(loader).withUserConfiguration(host)
                    .withInitializer(context -> extra.accept((BeanDefinitionRegistry) context))
                    .withPropertyValues("spring.liquibase.enabled=false")
                    .withPropertyValues(properties.toArray(String[]::new)).run(assertions::accept);
        }
    }

    private static TopolithException failure(AssertableApplicationContext context) {
        Throwable failure = context.getStartupFailure();
        assertNotNull(failure, "the context should have failed to start");
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof TopolithException topolith) {
                return topolith;
            }
        }
        return fail("no TopolithException in the cause chain of " + failure);
    }

    private static List<TopolithCode> codes(AssertableApplicationContext context) {
        return failure(context).diagnostics().stream().map(Diagnostic::code).toList();
    }

    @Test
    void ac_mode_30_an_internal_modules_unregistered_repository_fails_with_tl0024() throws Exception {
        run(QuietHost.class, List.of(catalogRoot), registry -> { }, List.of("topolith.modules.catalog.mode=internal",
                NO_JPA), context -> {
            assertEquals(List.of(TopolithCode.TL0024), codes(context));
            String message = failure(context).getMessage();
            assertTrue(message.contains(CatalogRepository.class.getName()) && message.contains("module 'catalog'"),
                    message);
        });
    }

    @Test
    void ac_mode_30_a_hosts_unregistered_repository_fails_with_tl0024_when_a_module_is_present() throws Exception {
        run(Host.class, List.of(catalogRoot), registry -> { }, List.of("topolith.modules.catalog.mode=none", NO_JPA),
                context -> {
                    assertEquals(List.of(TopolithCode.TL0024), codes(context));
                    String message = failure(context).getMessage();
                    assertTrue(message.contains("the host") && message.contains("HostRepository"), message);
                });
    }

    @Test
    void ac_mode_30_the_standalone_modules_package_is_labelled_with_the_module() throws Exception {
        run(MartApplication.class, List.of(martRoot), registry -> { }, List.of("topolith.standalone=mart", NO_JPA),
                context -> {
            assertEquals(List.of(TopolithCode.TL0024), codes(context));
            String message = failure(context).getMessage();
            assertTrue(message.contains(MartRepository.class.getName() + " in module 'mart'"), message);
        });
    }

    @Test
    void ac_mode_30_a_repository_of_a_non_internal_module_is_not_scanned() throws Exception {
        for (String mode : List.of("none", "external")) {
            run(QuietHost.class, List.of(catalogRoot), registry -> { }, List.of("topolith.modules.catalog.mode=" + mode,
                    "topolith.modules.catalog.remote.url=http://localhost", NO_JPA), context -> {
                assertNull(context.getStartupFailure());
            });
        }
    }

    @Test
    void ac_mode_30_a_registered_jpa_repository_is_covered() throws Exception {
        run(Host.class, List.of(ledgerRoot), registry -> { }, List.of("topolith.modules.ledger.mode=internal"),
                context -> {
                    assertNull(context.getStartupFailure());
                    assertEquals(1, context.getBeanNamesForType(
                            io.github.rey5137.topolith.spring.boot.fixture.ledger.LedgerRepository.class).length);
                });
    }

    @Test
    void ac_mode_30_a_registration_recorded_as_a_generic_argument_attribute_or_name_is_covered() throws Exception {
        String name = CatalogRepository.class.getName();
        List<Consumer<BeanDefinitionRegistry>> shapes = List.of(
                registry -> registry.registerBeanDefinition("byClass", lazy(def -> def.getConstructorArgumentValues()
                        .addGenericArgumentValue(CatalogRepository.class))),
                registry -> registry.registerBeanDefinition("byName", lazy(def -> def.getConstructorArgumentValues()
                        .addGenericArgumentValue(name))),
                registry -> registry.registerBeanDefinition("byIndex", lazy(def -> def.getConstructorArgumentValues()
                        .addIndexedArgumentValue(0, name))),
                registry -> registry.registerBeanDefinition("byAttribute", lazy(def -> def.setAttribute(
                        FactoryBean.OBJECT_TYPE_ATTRIBUTE, CatalogRepository.class))));
        for (Consumer<BeanDefinitionRegistry> shape : shapes) {
            run(QuietHost.class, List.of(catalogRoot), shape, List.of("topolith.modules.catalog.mode=internal",
                    NO_JPA), context -> assertNull(context.getStartupFailure()));
        }
    }

    /** A lazy Spring Data factory bean definition shaped the way a store's scanner records its interface. */
    private static RootBeanDefinition lazy(Consumer<RootBeanDefinition> shape) {
        RootBeanDefinition definition = new RootBeanDefinition(JpaRepositoryFactoryBean.class);
        definition.setLazyInit(true);
        shape.accept(definition);
        return definition;
    }

    private static final List<String> SHELF = List.of("topolith.modules.shelf.mode=internal", NO_JPA);

    /** A hand-built factory bean that takes its interface as a property, the way a MapperFactoryBean does. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static class HandBuilt extends JpaRepositoryFactoryBean {
        public HandBuilt() {
            super(MappedRepository.class);
        }

        public void setMapperInterface(Class<?> type) {
        }

        public void setRepositoryInterface(String name) {
        }
    }

    private static RootBeanDefinition handBuilt(String property, Object value) {
        RootBeanDefinition definition = new RootBeanDefinition(HandBuilt.class);
        definition.setLazyInit(true);
        definition.getPropertyValues().add(property, value);
        return definition;
    }

    private static Consumer<BeanDefinitionRegistry> mapped() {
        return registry -> registry.registerBeanDefinition("mapped", handBuilt("mapperInterface",
                MappedRepository.class));
    }

    @Test
    void ac_mode_30_a_profile_off_interface_is_not_a_repository() throws Exception {
        run(QuietHost.class, List.of(shelfRoot), mapped(), withProfile("prod"), context -> assertNull(
                context.getStartupFailure()));
        run(QuietHost.class, List.of(shelfRoot), mapped(), SHELF, context -> {
            assertEquals(List.of(TopolithCode.TL0024), codes(context));
            assertTrue(failure(context).getMessage().contains(ProfiledRepository.class.getName()));
        });
    }

    private static List<String> withProfile(String profile) {
        return List.of(SHELF.get(0), NO_JPA, "spring.profiles.active=" + profile);
    }

    @Test
    void ac_mode_30_nested_opted_out_and_property_registered_interfaces_are_not_reported() throws Exception {
        // the prod profile hides ProfiledRepository; the nested, @NoRepositoryBean and mapperInterface cases remain
        run(QuietHost.class, List.of(shelfRoot), mapped(), withProfile("prod"), context -> assertNull(
                context.getStartupFailure()));
        // without the mapperInterface property the same interface is reported, and only it (plus the profiled one)
        run(QuietHost.class, List.of(shelfRoot), registry -> { }, withProfile("prod"), context -> {
            assertEquals(List.of(TopolithCode.TL0024), codes(context));
            String message = failure(context).getMessage();
            assertTrue(message.contains(MappedRepository.class.getName()), message);
            assertFalse(message.contains("Nested") || message.contains("OptedOut"), message);
        });
        // a property given as a name
        run(QuietHost.class, List.of(shelfRoot), registry -> registry.registerBeanDefinition("named",
                handBuilt("repositoryInterface", MappedRepository.class.getName())),
                withProfile("prod"), context -> assertNull(context.getStartupFailure()));
    }

    @Test
    void ac_mode_30_a_standalone_module_with_mode_none_is_not_reported_as_the_host() throws Exception {
        run(MartApplication.class, List.of(martRoot), registry -> { }, List.of("topolith.standalone=mart",
                "topolith.modules.mart.mode=none", NO_JPA), context -> {
                    assertEquals(List.of(TopolithCode.TL0007), codes(context));
                    assertTrue(failure(context).getMessage().contains(MartApplication.class.getName()));
                    assertFalse(failure(context).getMessage().contains("TL0024"));
                });
    }

    @Test
    void ac_mode_30_an_interface_is_labelled_with_the_most_specific_package() throws Exception {
        run(WideHost.class, List.of(shelfRoot), mapped(), List.of("topolith.standalone=shelf", NO_JPA), context -> {
            String message = failure(context).getMessage();
            assertTrue(message.contains(ProfiledRepository.class.getName() + " in module 'shelf'"), message);
            assertTrue(message.contains(CatalogRepository.class.getName() + " in the host"), message);
        });
    }
}
