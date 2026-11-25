package io.github.rey5137.topolith.spring.boot;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import javax.sql.DataSource;

import io.github.rey5137.topolith.core.Diagnostic;
import io.github.rey5137.topolith.core.ModuleDescriptor;
import io.github.rey5137.topolith.core.TopolithCode;
import io.github.rey5137.topolith.core.TopolithException;
import io.github.rey5137.topolith.core.Topology;
import io.github.rey5137.topolith.spring.boot.Fixtures.RecordingLoader;
import io.github.rey5137.topolith.spring.boot.fixture.audit.AuditEntry;
import io.github.rey5137.topolith.spring.boot.fixture.audit.AuditRepository;
import io.github.rey5137.topolith.spring.boot.fixture.billing.BillingEntry;
import io.github.rey5137.topolith.spring.boot.fixture.billing.BillingRepository;
import io.github.rey5137.topolith.spring.boot.fixture.host.HostEntity;
import io.github.rey5137.topolith.spring.boot.fixture.host.HostRepository;
import io.github.rey5137.topolith.spring.boot.fixture.depot.DepotComponent;
import io.github.rey5137.topolith.spring.boot.fixture.depot.DepotEntity;
import io.github.rey5137.topolith.spring.boot.fixture.depot.DepotRepository;
import io.github.rey5137.topolith.spring.boot.fixture.depot.app.DepotAppComponent;
import io.github.rey5137.topolith.spring.boot.fixture.depot.app.DepotAppRepository;
import io.github.rey5137.topolith.spring.boot.fixture.depot.app.DepotApplication;
import io.github.rey5137.topolith.spring.boot.fixture.host.Hosts.CustomFlywayHost;
import io.github.rey5137.topolith.spring.boot.fixture.host.Hosts.EntityScanHost;
import io.github.rey5137.topolith.spring.boot.fixture.host.Hosts.Host;
import io.github.rey5137.topolith.spring.boot.fixture.host.Hosts.LedgerEntityScanHost;
import io.github.rey5137.topolith.spring.boot.fixture.host.Hosts.LedgerManagedTypesHost;
import io.github.rey5137.topolith.spring.boot.fixture.host.Hosts.LedgerRepositoriesHost;
import io.github.rey5137.topolith.spring.boot.fixture.host.Hosts.ManagedTypesHost;
import io.github.rey5137.topolith.spring.boot.fixture.host.Hosts.SecondFactoryHost;
import io.github.rey5137.topolith.spring.boot.fixture.host.Hosts.TwoDataSourcesHost;
import io.github.rey5137.topolith.spring.boot.fixture.inventory.InventoryConfiguration;
import io.github.rey5137.topolith.spring.boot.fixture.inventory.InventoryItem;
import io.github.rey5137.topolith.spring.boot.fixture.inventory.InventoryRepository;
import io.github.rey5137.topolith.spring.boot.fixture.ledger.LedgerEntry;
import io.github.rey5137.topolith.spring.boot.fixture.ledger.LedgerRepository;
import io.github.rey5137.topolith.spring.boot.fixture.rogue.RogueConfiguration;
import io.github.rey5137.topolith.spring.boot.fixture.service.ServiceHost;
import io.github.rey5137.topolith.spring.boot.fixture.shop.ShopApplication;
import io.github.rey5137.topolith.spring.boot.fixture.shop.ShopComponent;
import io.github.rey5137.topolith.spring.boot.fixture.shop.ShopEntity;
import io.github.rey5137.topolith.spring.boot.fixture.shop.ShopRepository;
import io.github.rey5137.topolith.spring.boot.fixture.user.UserComponent;

import jakarta.persistence.EntityManagerFactory;
import liquibase.integration.spring.SpringLiquibase;
import jakarta.persistence.metamodel.EntityType;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationInitializer;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes;
import org.springframework.transaction.TransactionManager;

import static io.github.rey5137.topolith.spring.boot.Fixtures.AUDIT;
import static io.github.rey5137.topolith.spring.boot.Fixtures.BILLING;
import static io.github.rey5137.topolith.spring.boot.Fixtures.INVENTORY;
import static io.github.rey5137.topolith.spring.boot.Fixtures.LEDGER;
import static io.github.rey5137.topolith.spring.boot.Fixtures.ROGUE;
import static io.github.rey5137.topolith.spring.boot.Fixtures.USER;
import static io.github.rey5137.topolith.spring.boot.Fixtures.USER_API;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/** M1.4 acceptance tests: per-module persistence and migrations on a real Boot context with H2 (D-32). */
class ModulePersistenceTest {
    /** A host whose auto-configuration package is above the fixture module packages (TL0018). */
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class OverlappingHost {
    }

    private interface Assertions {
        void check(AssertableApplicationContext context) throws Exception;
    }

    private static final String HOST_V1 = "create table host_entity (id bigint generated by default as identity "
            + "primary key, name varchar(255));";
    private static final String LEDGER_V1 = "create table ledger_entry (id bigint generated by default as identity "
            + "primary key, memo varchar(255));";
    private static final String BILLING_V1 = "create table billing_entry (id bigint generated by default as "
            + "identity primary key, memo varchar(255));";
    /**
     * The host's own Liquibase changelog. liquibase-core is on the test class path, so Boot's Liquibase would stop
     * every host without one; {@code run} switches it off unless a test turns it on with {@link #HOST_LIQUIBASE}.
     */
    private static final String HOST_CHANGELOG = """
            databaseChangeLog:
              - changeSet:
                  id: host-1
                  author: host
                  changes:
                    - createTable:
                        tableName: host_marker
                        columns:
                          - column: {name: id, type: bigint}
            """;
    /** Boot's Liquibase on the host as well as its Flyway: Flyway then finds a non-empty schema and baselines at 0. */
    private static final List<String> HOST_LIQUIBASE = List.of("spring.liquibase.enabled=true",
            "spring.flyway.baseline-on-migrate=true", "spring.flyway.baseline-version=0");
    private static final String AUDIT_CHANGELOG = """
            databaseChangeLog:
              - changeSet:
                  id: audit-1
                  author: audit
                  changes:
                    - createTable:
                        tableName: audit_entry
                        columns:
                          - column: {name: id, type: bigint, autoIncrement: true,
                              constraints: {primaryKey: true, nullable: false}}
                          - column: {name: memo, type: varchar(255)}
              - include:
                  file: changes/002-note.yaml
                  relativeToChangelogFile: true
            """;
    private static final String AUDIT_NOTE = """
            databaseChangeLog:
              - changeSet:
                  id: audit-2
                  author: audit
                  changes:
                    - addColumn:
                        tableName: audit_entry
                        columns:
                          - column: {name: note, type: varchar(255)}
            """;
    private static final String HOST_CHANGELOG_PATH = "db/changelog/db.changelog-master.yaml";
    private static final List<String> ALL = List.of("topolith.modules.ledger.mode=internal",
            "topolith.modules.billing.mode=internal", "topolith.modules.audit.mode=internal");
    private static final String VALIDATE = "spring.jpa.hibernate.ddl-auto=validate";
    private static final List<String> BOTH = List.of("topolith.modules.ledger.mode=internal",
            "topolith.modules.billing.mode=internal");

    @TempDir
    Path temp;
    Path ledgerRoot;
    Path billingRoot;
    Path inventoryRoot;
    Path auditRoot;
    Path hostRoot;

    @BeforeEach
    void roots() throws IOException {
        ledgerRoot = Fixtures.dir(temp, "ledger");
        Fixtures.module(ledgerRoot, "ledger", LEDGER, List.of(LEDGER + ".api"));
        Fixtures.resource(ledgerRoot, "db/modules/ledger/V1__init.sql", LEDGER_V1);
        billingRoot = Fixtures.dir(temp, "billing");
        Fixtures.module(billingRoot, "billing", BILLING, List.of(BILLING + ".api"));
        Fixtures.resource(billingRoot, "db/modules/billing/V1__init.sql", BILLING_V1);
        auditRoot = Fixtures.dir(temp, "audit");
        Fixtures.module(auditRoot, "audit", AUDIT, List.of(AUDIT + ".api"), "ledger");
        Fixtures.resource(auditRoot, "db/modules/audit/db.changelog-master.yaml", AUDIT_CHANGELOG);
        Fixtures.resource(auditRoot, "db/modules/audit/changes/002-note.yaml", AUDIT_NOTE);
        inventoryRoot = Fixtures.dir(temp, "inventory");
        Fixtures.module(inventoryRoot, "inventory", INVENTORY, List.of(INVENTORY + ".api"));
        hostRoot = Fixtures.dir(temp, "host");
        Fixtures.resource(hostRoot, "db/migration/V1__host.sql", HOST_V1);
    }

    private static void run(Class<?> host, ClassLoader parent, List<Path> roots, List<String> properties,
            Assertions assertions) throws Exception {
        try (RecordingLoader loader = new RecordingLoader(parent, roots.toArray(Path[]::new))) {
            new ApplicationContextRunner().withClassLoader(loader).withUserConfiguration(host)
                    .withPropertyValues("spring.liquibase.enabled=false")
                    .withPropertyValues(properties.toArray(String[]::new)).run(context -> {
                        try {
                            assertions.check(context);
                        } catch (RuntimeException | Error e) {
                            throw e;
                        } catch (Exception e) {
                            throw new AssertionError(e);
                        }
                    });
        }
    }

    private static void run(Class<?> host, List<Path> roots, List<String> properties, Assertions assertions)
            throws Exception {
        run(host, Fixtures.class.getClassLoader(), roots, properties, assertions);
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

    private static Set<String> entities(EntityManagerFactory factory) {
        Set<String> names = new TreeSet<>();
        for (EntityType<?> type : factory.getMetamodel().getEntities()) {
            names.add(type.getJavaType().getSimpleName());
        }
        return names;
    }

    private static JdbcTemplate jdbc(AssertableApplicationContext context) {
        return new JdbcTemplate(context.getBean(DataSource.class));
    }

    private static int tables(JdbcTemplate jdbc, String like) {
        return jdbc.queryForObject("select count(*) from information_schema.tables where lower(table_name) like ?",
                Integer.class, like.toLowerCase());
    }

    private static List<String> scripts(JdbcTemplate jdbc, String table) {
        return jdbc.queryForList("select \"script\" from \"" + table + "\" where \"type\" = 'SQL' "
                + "order by \"installed_rank\"",
                String.class);
    }

    private static List<String> changesets(JdbcTemplate jdbc, String table) {
        return jdbc.queryForList("select id from " + table + " order by orderexecuted", String.class);
    }

    private static List<String> plus(List<String> base, String... more) {
        List<String> all = new java.util.ArrayList<>(base);
        all.addAll(List.of(more));
        return all;
    }

    /** Gives the host a Liquibase changelog with {@code body} as its content. */
    private void hostChangelog(String body) throws IOException {
        Fixtures.resource(hostRoot, HOST_CHANGELOG_PATH, body);
    }

    private static List<String> withValidate(List<String> properties) {
        List<String> all = new java.util.ArrayList<>(properties);
        all.add(VALIDATE);
        return all;
    }

    private static String url(DataSource dataSource) throws Exception {
        try (var connection = dataSource.getConnection()) {
            return connection.getMetaData().getURL();
        }
    }

    // ---- AC-MODE-10 -------------------------------------------------------------------------------------------

    @Test
    void ac_mode_10_the_host_factory_manages_the_host_and_internal_module_entities() throws Exception {
        run(Host.class, List.of(ledgerRoot, billingRoot, hostRoot), withValidate(BOTH), context -> {
            assertEquals(Set.of("BillingEntry", "HostEntity", "LedgerEntry"),
                    entities(context.getBean(EntityManagerFactory.class)));
            List<String> managed = context.getBean(PersistenceManagedTypes.class).getManagedClassNames();
            assertTrue(managed.contains(LedgerEntry.class.getName()) && managed.contains(BillingEntry.class.getName())
                    && managed.contains(HostEntity.class.getName()), managed.toString());
        });
    }

    @Test
    void ac_mode_10_module_repositories_have_fully_qualified_names_and_use_the_host_factory() throws Exception {
        run(Host.class, List.of(ledgerRoot, billingRoot, hostRoot), withValidate(BOTH), context -> {
            assertTrue(context.containsBean(LedgerRepository.class.getName()));
            assertTrue(context.containsBean(BillingRepository.class.getName()));
            assertEquals(1, context.getBeansOfType(LedgerRepository.class).size());
            assertEquals(1, context.getBeansOfType(HostRepository.class).size(), "the host keeps its repository");
            LedgerEntry entry = new LedgerEntry();
            entry.setMemo("first");
            context.getBean(LedgerRepository.class).save(entry);
            assertEquals(1, context.getBean(LedgerRepository.class).count());
            assertEquals(1, jdbc(context).queryForObject("select count(*) from ledger_entry", Integer.class));
        });
    }

    @Test
    void ac_mode_10_each_module_runs_its_migrations_with_its_own_history_table() throws Exception {
        run(Host.class, List.of(ledgerRoot, billingRoot, hostRoot), withValidate(BOTH), context -> {
            JdbcTemplate jdbc = jdbc(context);
            assertEquals(List.of("V1__init.sql"), scripts(jdbc, "flyway_schema_history_ledger"));
            assertEquals(List.of("V1__init.sql"), scripts(jdbc, "flyway_schema_history_billing"));
            assertEquals(List.of("V1__host.sql"), scripts(jdbc, "flyway_schema_history"),
                    "the host history holds only host scripts");
            assertEquals(1, context.getBeansOfType(LedgerRepository.class).size());
        });
    }

    @Test
    void ac_mode_10_a_module_runs_after_the_host_and_after_the_modules_it_requires() throws Exception {
        Fixtures.module(billingRoot, "billing", BILLING, List.of(BILLING + ".api"), "ledger");
        run(Host.class, List.of(ledgerRoot, billingRoot, hostRoot), withValidate(BOTH), context -> {
            BeanDefinition billing = context.getBeanFactory().getBeanDefinition("topolith.flyway.billing");
            assertTrue(List.of(billing.getDependsOn()).containsAll(List.of("flywayInitializer",
                    "topolith.flyway.ledger")), List.of(billing.getDependsOn()).toString());
            List<String> ledger = List.of(context.getBeanFactory().getBeanDefinition("topolith.flyway.ledger")
                    .getDependsOn());
            assertTrue(ledger.contains("flywayInitializer") && !ledger.contains("topolith.flyway.billing"),
                    ledger.toString());
            assertFalse(((org.springframework.beans.factory.support.AbstractBeanDefinition) billing)
                    .isDefaultCandidate(), "a module's initializer is not a default candidate");
        });
    }

    @Test
    void ac_mode_10_a_host_entity_scan_is_honoured_and_module_entities_are_still_added() throws Exception {
        run(EntityScanHost.class, List.of(ledgerRoot, billingRoot, hostRoot), withValidate(BOTH), context -> {
            assertEquals(Set.of("BillingEntry", "HostEntity", "LedgerEntry"),
                    entities(context.getBean(EntityManagerFactory.class)));
        });
    }

    @Test
    void ac_mode_10_without_flyway_the_modules_still_register_entities_and_repositories() throws Exception {
        List<String> properties = List.of("topolith.modules.ledger.mode=internal",
                "topolith.modules.billing.mode=internal", "spring.jpa.hibernate.ddl-auto=create-drop");
        org.springframework.util.FileSystemUtils.deleteRecursively(ledgerRoot.resolve("db"));
        org.springframework.util.FileSystemUtils.deleteRecursively(billingRoot.resolve("db"));
        run(Host.class, new FilteredClassLoader("org.flywaydb"), List.of(ledgerRoot, billingRoot, hostRoot),
                properties, context -> {
                    assertEquals(Set.of("BillingEntry", "HostEntity", "LedgerEntry"),
                            entities(context.getBean(EntityManagerFactory.class)));
                    assertTrue(context.containsBean(LedgerRepository.class.getName()));
                    assertEquals(1, tables(jdbc(context), "ledger_entry"));
                    assertEquals(0, tables(jdbc(context), "flyway%"));
                });
    }

    // ---- AC-MODE-11 -------------------------------------------------------------------------------------------

    private void nonInternalLeavesNothing(String mode, boolean flyway) throws Exception {
        ClassLoader parent = flyway ? Fixtures.class.getClassLoader() : new FilteredClassLoader("org.flywaydb");
        List<String> properties = List.of("topolith.modules.ledger.mode=" + mode,
                flyway ? VALIDATE : "spring.jpa.hibernate.ddl-auto=create-drop");
        run(Host.class, parent, List.of(ledgerRoot, hostRoot), properties, context -> {
            assertEquals(Set.of("HostEntity"), entities(context.getBean(EntityManagerFactory.class)));
            assertFalse(context.getBean(PersistenceManagedTypes.class).getManagedClassNames()
                    .contains(LedgerEntry.class.getName()));
            assertEquals(0, context.getBeanNamesForType(LedgerRepository.class).length);
            assertEquals(1, context.getBeansOfType(HostRepository.class).size());
            JdbcTemplate jdbc = jdbc(context);
            assertEquals(0, tables(jdbc, "%ledger%"), "no ledger table, no ledger history table");
            assertEquals(flyway ? 1 : 0, tables(jdbc, "flyway_schema_history"));
            if (flyway) {
                assertEquals(List.of("V1__host.sql"), scripts(jdbc, "flyway_schema_history"));
            }
            assertFalse(context.containsBean("topolith.flyway.ledger"));
        });
    }

    @Test
    void ac_mode_11_a_none_module_leaves_no_entity_repository_migration_or_table() throws Exception {
        nonInternalLeavesNothing("none", true);
        nonInternalLeavesNothing("none", false);
    }

    @Test
    void ac_mode_11_an_external_module_leaves_no_entity_repository_migration_or_table() throws Exception {
        nonInternalLeavesNothing("external", true);
        nonInternalLeavesNothing("external", false);
    }

    @Test
    void ac_mode_11_a_host_repository_registration_of_a_none_modules_repository_fails_with_tl0007() throws Exception {
        run(LedgerRepositoriesHost.class, List.of(ledgerRoot, hostRoot), List.of("topolith.modules.ledger.mode=none"),
                context -> {
                    assertEquals(List.of(TopolithCode.TL0007), codes(context));
                    assertTrue(failure(context).getMessage().contains(LedgerRepository.class.getName()));
                });
    }

    @Test
    void ac_mode_11_a_host_entity_manager_that_manages_a_none_modules_entity_fails_with_tl0007() throws Exception {
        run(LedgerManagedTypesHost.class, List.of(ledgerRoot, hostRoot),
                List.of("topolith.modules.ledger.mode=none", "spring.flyway.enabled=false",
                        "spring.jpa.hibernate.ddl-auto=create-drop"), context -> {
                    assertEquals(List.of(TopolithCode.TL0007), codes(context));
                    assertTrue(failure(context).getMessage().contains(LedgerEntry.class.getName()));
                });
    }

    // ---- AC-MODE-12 -------------------------------------------------------------------------------------------

    @Test
    void ac_mode_12_a_modules_own_data_source_and_entity_manager_work_beside_the_hosts() throws Exception {
        run(Host.class, List.of(inventoryRoot, hostRoot), List.of("topolith.modules.inventory.mode=internal",
                VALIDATE), context -> {
            assertNotEquals(InventoryConfiguration.URL, url(context.getBean(DataSource.class)),
                    "the default DataSource is Boot's");
            assertEquals(2, context.getBeansOfType(DataSource.class).size());
            assertEquals(Set.of("HostEntity"), entities(context.getBean(EntityManagerFactory.class)));
            assertEquals(Set.of("InventoryItem"), entities(context.getBean("inventoryEntityManagerFactory",
                    EntityManagerFactory.class)));
            assertEquals(1, context.getBeansOfType(HostRepository.class).size(), "the host keeps its repository");
            TransactionManager defaultManager = context.getBean(TransactionManager.class);
            assertEquals(context.getBean(EntityManagerFactory.class),
                    ((JpaTransactionManager) defaultManager).getEntityManagerFactory());
            InventoryItem item = new InventoryItem();
            item.setName("bolt");
            context.getBean(InventoryRepository.class).save(item);
            DataSource own = context.getBean("inventoryDataSource", DataSource.class);
            assertEquals(1, new JdbcTemplate(own).queryForObject("select count(*) from InventoryItem",
                    Integer.class));
            assertEquals(0, tables(jdbc(context), "inventory%"), "nothing lands in the host's database");
            assertEquals(1, context.getBeanNamesForType(org.springframework.boot.flyway.autoconfigure
                    .FlywayMigrationInitializer.class).length, "only the host's: Topolith runs none for it");
        });
    }

    @Test
    void ac_mode_12_the_standalone_module_may_replace_the_hosts_persistence() throws Exception {
        Path rogueRoot = Fixtures.dir(temp, "rogue");
        Fixtures.module(rogueRoot, "rogue", ROGUE, List.of(ROGUE + ".api"));
        run(ServiceHost.class, List.of(rogueRoot), List.of("topolith.standalone=rogue"), context -> {
            assertNotNull(context.getBean(RogueConfiguration.class));
            assertEquals(1, context.getBeansOfType(DataSource.class).size(), "Boot's DataSource backs off");
            assertTrue(url(context.getBean(DataSource.class)).startsWith("jdbc:h2:mem:rogue"));
            assertEquals(Set.of("RogueItem"), entities(context.getBean(EntityManagerFactory.class)));
        });
    }

    @Test
    void ac_mode_10_a_host_that_declares_its_own_flyway_still_runs_the_module_migrations() throws Exception {
        run(CustomFlywayHost.class, List.of(ledgerRoot, hostRoot), List.of("topolith.modules.ledger.mode=internal",
                "spring.jpa.hibernate.ddl-auto=none"), context -> {
            assertFalse(context.containsBean("flywayInitializer"), "Boot registers no initializer for it");
            assertEquals(List.of("V1__init.sql"), scripts(jdbc(context), "flyway_schema_history_ledger"));
            assertEquals(1, tables(jdbc(context), "ledger_entry"));
        });
    }

    @Test
    void ac_mode_10_a_host_target_version_does_not_hide_a_module_script() throws Exception {
        Fixtures.resource(ledgerRoot, "db/modules/ledger/V2__more.sql",
                "alter table ledger_entry add column note varchar(255);");
        run(Host.class, List.of(ledgerRoot, hostRoot), List.of("topolith.modules.ledger.mode=internal",
                "spring.flyway.target=1", VALIDATE), context -> assertEquals(List.of("V1__init.sql", "V2__more.sql"),
                scripts(jdbc(context), "flyway_schema_history_ledger")));
    }

    @Test
    void ac_mode_10_module_and_restored_host_repositories_honour_the_hosts_bootstrap_mode() throws Exception {
        run(Host.class, List.of(ledgerRoot, hostRoot), List.of("topolith.modules.ledger.mode=internal",
                "spring.data.jpa.repositories.bootstrap-mode=deferred"), context -> {
            assertTrue(context.getBeanFactory().getBeanDefinition("hostRepository").isLazyInit(), "Boot's own");
            assertTrue(context.getBeanFactory().getBeanDefinition(LedgerRepository.class.getName()).isLazyInit());
        });
        run(Host.class, List.of(inventoryRoot, hostRoot), List.of("topolith.modules.inventory.mode=internal",
                "spring.data.jpa.repositories.bootstrap-mode=deferred"), context -> assertTrue(
                context.getBeanFactory().getBeanDefinition("hostRepository").isLazyInit(), "the restored host scan"));
    }

    @Test
    void ac_mode_25_the_standalone_module_may_hold_the_hosts_application_class() throws Exception {
        for (String id : List.of("shop", "depot")) {
            Path root = Fixtures.dir(temp, id);
            String module = Fixtures.BASE + "." + id;
            Fixtures.module(root, id, module, List.of(module + ".api"));
            Fixtures.resource(root, "db/modules/" + id + "/V1__init.sql", "create table " + id
                    + "_entity (id bigint generated by default as identity primary key, name varchar(255));");
            Class<?> host = id.equals("shop") ? ShopApplication.class : DepotApplication.class;
            run(host, List.of(root), List.of("topolith.standalone=" + id, VALIDATE), context -> {
                Class<?> component = id.equals("shop") ? ShopComponent.class : DepotComponent.class;
                Class<?> repository = id.equals("shop") ? ShopRepository.class : DepotRepository.class;
                assertEquals(1, context.getBeanNamesForType(component).length, "each component once");
                assertEquals(1, context.getBeanNamesForType(repository).length, "each repository once");
                assertEquals(Set.of(id.equals("shop") ? "ShopEntity" : "DepotEntity"),
                        entities(context.getBean(EntityManagerFactory.class)));
                String entity = id.equals("shop") ? ShopEntity.class.getName() : DepotEntity.class.getName();
                assertEquals(1, java.util.Collections.frequency(context.getBean(PersistenceManagedTypes.class)
                        .getManagedClassNames(), entity));
                assertEquals(List.of("V1__init.sql"), scripts(jdbc(context), "flyway_schema_history_" + id));
                if (id.equals("depot")) {
                    assertEquals(1, context.getBeanNamesForType(DepotAppComponent.class).length);
                    assertEquals(1, context.getBeanNamesForType(DepotAppRepository.class).length);
                }
            });
        }
    }

    @Test
    void ac_mode_25_the_standalone_exemption_does_not_cover_another_module() throws Exception {
        run(OverlappingHost.class, List.of(ledgerRoot, billingRoot, hostRoot), List.of("topolith.standalone=ledger",
                "topolith.modules.billing.mode=none"), context -> {
            assertEquals(List.of(TopolithCode.TL0018), codes(context));
            String message = failure(context).getMessage();
            assertTrue(message.contains("module 'billing'") && !message.contains("module 'ledger'"), message);
        });
    }

    // ---- AC-MODE-25 -------------------------------------------------------------------------------------------

    @Test
    void ac_mode_25_a_host_auto_configuration_package_above_a_module_fails_with_tl0018_in_every_mode()
            throws Exception {
        for (String mode : List.of("internal", "external", "none")) {
            run(OverlappingHost.class, List.of(ledgerRoot, hostRoot), List.of("topolith.modules.ledger.mode=" + mode),
                    context -> {
                        assertEquals(List.of(TopolithCode.TL0018), codes(context));
                        String message = failure(context).getMessage();
                        assertTrue(message.contains("module 'ledger'"), message);
                    });
        }
    }

    @Test
    void ac_mode_25_an_entity_scan_into_a_module_fails_with_tl0018_in_every_mode() throws Exception {
        for (String mode : List.of("internal", "external", "none")) {
            run(LedgerEntityScanHost.class, List.of(ledgerRoot, hostRoot),
                    List.of("topolith.modules.ledger.mode=" + mode), context -> {
                        assertEquals(List.of(TopolithCode.TL0018), codes(context));
                        assertTrue(failure(context).getMessage().contains("entity-scan"));
                    });
        }
    }

    @Test
    void ac_mode_25_a_host_package_inside_a_module_package_overlaps_too() {
        Topology topology = Topology.resolve(List.of(ModuleDescriptor.builder("ledger").packageName(LEDGER)
                .apiPackages(List.of(LEDGER + ".api")).apiVersion("1.0.0").build()), List.of(),
                Map.of("ledger", "none"), null);
        assertEquals(1, TopolithPersistenceCheck.overlaps("auto-configuration", List.of(LEDGER + ".sub"),
                topology, null).size());
        assertEquals(1, TopolithPersistenceCheck.overlaps("auto-configuration", List.of(LEDGER), topology,
                null).size());
        assertEquals(0, TopolithPersistenceCheck.overlaps("auto-configuration", List.of(LEDGER + ".sub"), topology,
                "ledger").size(), "the standalone module is exempt");
        assertEquals(0, TopolithPersistenceCheck.overlaps("auto-configuration", List.of(LEDGER + "x"),
                topology, null).size(), "a longer last segment is not nested");
    }

    // ---- AC-MODE-26 -------------------------------------------------------------------------------------------

    @Test
    void ac_mode_26_default_candidate_persistence_beans_of_an_internal_module_fail_with_tl0019() throws Exception {
        Path rogueRoot = Fixtures.dir(temp, "rogue");
        Fixtures.module(rogueRoot, "rogue", ROGUE, List.of(ROGUE + ".api"));
        run(Host.class, List.of(rogueRoot, hostRoot), List.of("topolith.modules.rogue.mode=internal"), context -> {
            assertEquals(List.of(TopolithCode.TL0019, TopolithCode.TL0019, TopolithCode.TL0019), codes(context));
            String message = failure(context).getMessage();
            assertTrue(message.contains("rogueDataSource") && message.contains("defaultCandidate = false"), message);
        });
    }

    @Test
    void ac_mode_26_the_standalone_module_is_exempt_from_tl0019() throws Exception {
        Path rogueRoot = Fixtures.dir(temp, "rogue");
        Fixtures.module(rogueRoot, "rogue", ROGUE, List.of(ROGUE + ".api"));
        run(ServiceHost.class, List.of(rogueRoot), List.of("topolith.standalone=rogue"),
                context -> assertNull(context.getStartupFailure()));
    }

    // ---- AC-MODE-27 -------------------------------------------------------------------------------------------

    @Test
    void ac_mode_27_a_legacy_db_migration_script_in_a_module_artifact_fails_with_tl0020() throws Exception {
        Fixtures.resource(ledgerRoot, "db/migration/V9__legacy.sql", "select 1;");
        for (String mode : List.of("none", "external", "internal")) {
            run(Host.class, List.of(ledgerRoot, hostRoot), List.of("topolith.modules.ledger.mode=" + mode),
                    context -> {
                        assertEquals(List.of(TopolithCode.TL0020), codes(context));
                        assertTrue(failure(context).getMessage().contains("module 'ledger'"));
                    });
        }
    }

    @Test
    void ac_mode_27_a_host_location_above_the_module_location_fails_with_tl0020() throws Exception {
        run(Host.class, List.of(ledgerRoot, hostRoot), List.of("topolith.modules.ledger.mode=none",
                "spring.flyway.locations=classpath:db"), context -> {
            assertEquals(List.of(TopolithCode.TL0020), codes(context));
            assertTrue(failure(context).getMessage().contains("db/modules/ledger"));
        });
    }

    @Test
    void ac_mode_27_an_internal_module_with_scripts_and_no_flyway_fails_with_tl0020() throws Exception {
        run(Host.class, new FilteredClassLoader("org.flywaydb"), List.of(ledgerRoot, hostRoot),
                List.of("topolith.modules.ledger.mode=internal"), context -> {
                    assertEquals(List.of(TopolithCode.TL0020), codes(context));
                    assertTrue(failure(context).getMessage().contains("Flyway is not on the class path"));
                });
        run(Host.class, new FilteredClassLoader("org.flywaydb"), List.of(ledgerRoot, hostRoot),
                List.of("topolith.modules.ledger.mode=internal", "spring.flyway.enabled=false"), context -> {
                    assertEquals(List.of(TopolithCode.TL0020), codes(context),
                            "the host's switch does not excuse a missing tool");
                    assertTrue(failure(context).getMessage().contains("Flyway is not on the class path"));
                });
    }

    @Test
    void ac_mode_27_a_module_with_its_own_data_source_and_scripts_but_no_flyway_fails_with_tl0020()
            throws Exception {
        Fixtures.resource(inventoryRoot, "db/modules/inventory/V1__init.sql", "select 1;");
        run(Host.class, List.of(inventoryRoot, hostRoot), List.of("topolith.modules.inventory.mode=internal"),
                context -> {
                    assertEquals(List.of(TopolithCode.TL0020), codes(context));
                    assertTrue(failure(context).getMessage().contains("its own DataSource"));
                });
    }

    @Test
    void ac_mode_27_a_history_table_name_over_63_characters_fails_with_tl0020() throws Exception {
        String id = "ledger-" + "x".repeat(40);
        java.nio.file.Files.delete(ledgerRoot.resolve("META-INF/topolith/modules/ledger.properties"));
        Fixtures.module(ledgerRoot, id, LEDGER, List.of(LEDGER + ".api"));
        Fixtures.resource(ledgerRoot, "db/modules/" + id + "/V1__init.sql", LEDGER_V1);
        run(Host.class, List.of(ledgerRoot, hostRoot), List.of("topolith.modules." + id + ".mode=internal"),
                context -> {
                    assertEquals(List.of(TopolithCode.TL0020), codes(context));
                    assertTrue(failure(context).getMessage().contains("63"));
                });
    }

    @Test
    void ac_mode_10_flyway_core_without_the_boot_initializer_class_still_runs_the_module() throws Exception {
        run(CustomFlywayHost.class, new FilteredClassLoader(FlywayMigrationInitializer.class),
                List.of(ledgerRoot, hostRoot), List.of("topolith.modules.ledger.mode=internal",
                        "spring.jpa.hibernate.ddl-auto=none"), context -> {
                    assertNull(context.getStartupFailure(), "a module run needs only flyway-core");
                    assertEquals(List.of("V1__init.sql"), scripts(jdbc(context), "flyway_schema_history_ledger"));
                });
    }

    // ---- AC-MODE-28 -------------------------------------------------------------------------------------------

    @Test
    void ac_mode_28_an_internal_entity_that_no_factory_manages_fails_with_tl0021() throws Exception {
        run(ManagedTypesHost.class, List.of(ledgerRoot, hostRoot), List.of("topolith.modules.ledger.mode=internal",
                "spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=create-drop"), context -> {
            assertEquals(List.of(TopolithCode.TL0021), codes(context));
            assertTrue(failure(context).getMessage().contains(LedgerEntry.class.getName() + " of module 'ledger'"));
        });
    }

    @Test
    void ac_mode_28_an_internal_entity_that_several_factories_manage_fails_with_tl0021() throws Exception {
        run(SecondFactoryHost.class, List.of(ledgerRoot, hostRoot), List.of("topolith.modules.ledger.mode=internal",
                "spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=create-drop"), context -> {
            assertEquals(List.of(TopolithCode.TL0021), codes(context));
            assertTrue(failure(context).getMessage().contains("2 entity-manager factories"));
        });
    }

    @Test
    void ac_mode_28_an_internal_entity_fails_with_tl0021_when_there_is_no_entity_manager_factory_at_all()
            throws Exception {
        run(Host.class, List.of(ledgerRoot, hostRoot), List.of("topolith.modules.ledger.mode=internal",
                "spring.flyway.enabled=false", "spring.autoconfigure.exclude="
                + "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration"), context -> {
            assertEquals(List.of(TopolithCode.TL0021), codes(context));
            assertTrue(failure(context).getMessage().contains("is managed by no entity-manager"));
        });
    }

    // ---- AC-MODE-29 -------------------------------------------------------------------------------------------

    private static final String LEDGER_HISTORY = "select \"version\", \"script\", \"checksum\", \"installed_on\" "
            + "from \"flyway_schema_history_ledger\" order by \"installed_rank\"";

    /** Runs ledger standalone and composed against one named database; the second run must change nothing. */
    private void parity(String database, boolean standaloneFirst) throws Exception {
        String url = "jdbc:h2:mem:" + database + ";DB_CLOSE_DELAY=-1";
        List<String> shared = List.of("spring.datasource.url=" + url, "spring.datasource.username=sa", VALIDATE);
        List<String> standalone = new java.util.ArrayList<>(shared);
        standalone.add("topolith.standalone=ledger");
        List<String> composed = new java.util.ArrayList<>(shared);
        composed.addAll(BOTH);
        List<Map<String, Object>>[] first = new List[1];
        Assertions standaloneRun = context -> check(first, context);
        Assertions composedRun = context -> {
            check(first, context);
            assertEquals(List.of("V1__host.sql"), scripts(jdbc(context), "flyway_schema_history"));
        };
        try {
            for (int round = 0; round < 2; round++) {
                boolean standaloneNow = standaloneFirst == (round == 0);
                if (standaloneNow) {
                    run(ServiceHost.class, List.of(ledgerRoot, hostRoot), standalone, standaloneRun);
                } else {
                    run(Host.class, List.of(ledgerRoot, billingRoot, hostRoot), composed, composedRun);
                }
                if (round == 0) {
                    assertEquals(1, first[0].stream().filter(row -> "V1__init.sql".equals(row.get("script")))
                            .count());
                }
            }
        } finally {
            try (var connection = java.sql.DriverManager.getConnection(url, "sa", "");
                    var statement = connection.createStatement()) {
                statement.execute("drop all objects");
            }
        }
    }

    /** The first run records the ledger history; the second must find the same rows: nothing pending, nothing added. */
    private static void check(List<Map<String, Object>>[] first, AssertableApplicationContext context) {
        List<Map<String, Object>> history = jdbc(context).queryForList(LEDGER_HISTORY);
        if (first[0] == null) {
            first[0] = history;
        } else {
            assertEquals(first[0], history, "the second run finds nothing pending");
        }
    }

    @Test
    void ac_mode_29_a_composed_host_after_a_standalone_run_finds_nothing_pending() throws Exception {
        parity("parity_a", true);
    }

    @Test
    void ac_mode_29_a_standalone_run_after_a_composed_host_finds_nothing_pending() throws Exception {
        parity("parity_b", false);
    }

    // ---- Liquibase (D-32): AC-MODE-10, 11, 12, 27 and 29 -----------------------------------------------------

    private static final List<String> HOST_BOTH = List.of("topolith.modules.ledger.mode=internal",
            "topolith.modules.billing.mode=internal", "topolith.modules.audit.mode=internal");

    @Test
    void ac_mode_10_a_liquibase_module_gets_its_own_tracking_tables_and_runs_after_every_host_runner_and_ledger()
            throws Exception {
        hostChangelog(HOST_CHANGELOG);
        run(Host.class, List.of(ledgerRoot, billingRoot, auditRoot, hostRoot), plus(withValidate(HOST_BOTH),
                HOST_LIQUIBASE.toArray(String[]::new)), context -> {
            JdbcTemplate jdbc = jdbc(context);
            assertEquals(1, tables(jdbc, "databasechangelog_audit"));
            assertEquals(1, tables(jdbc, "databasechangeloglock_audit"));
            assertEquals(List.of("audit-1", "audit-2"), changesets(jdbc, "databasechangelog_audit"));
            assertEquals(List.of("host-1"), changesets(jdbc, "databasechangelog"), "the host's table has no audit");
            assertEquals(List.of("V1__init.sql"), scripts(jdbc, "flyway_schema_history_ledger"));
            assertEquals(1, tables(jdbc, "audit_entry"));
            assertEquals(Set.of("AuditEntry", "BillingEntry", "HostEntity", "LedgerEntry"),
                    entities(context.getBean(EntityManagerFactory.class)));
            AuditEntry entry = new AuditEntry();
            entry.setMemo("first");
            context.getBean(AuditRepository.class).save(entry);
            assertEquals(1, jdbc.queryForObject("select count(*) from audit_entry", Integer.class));
            BeanDefinition audit = context.getBeanFactory().getBeanDefinition("topolith.liquibase.audit");
            assertTrue(List.of(audit.getDependsOn()).containsAll(List.of("liquibase", "flywayInitializer",
                    "topolith.flyway.ledger")), List.of(audit.getDependsOn()).toString());
            assertFalse(((org.springframework.beans.factory.support.AbstractBeanDefinition) audit)
                    .isDefaultCandidate(), "a module's Liquibase is not a default candidate");
            BeanDefinition ledger = context.getBeanFactory().getBeanDefinition("topolith.flyway.ledger");
            assertTrue(List.of(ledger.getDependsOn()).containsAll(List.of("liquibase", "flywayInitializer")),
                    "a Flyway module waits for the host's Liquibase and Flyway");
            assertEquals(Set.of("liquibase"), context.getBeansOfType(SpringLiquibase.class).keySet(),
                    "the module's runner is not a SpringLiquibase: Boot would run it before the host's Flyway");
        });
    }

    /** A Flyway module that sorts before the Liquibase module it requires, and reads the table it creates. */
    private Path abacusRoot() throws IOException {
        Path root = Fixtures.dir(temp, "abacus");
        Fixtures.module(root, "abacus", Fixtures.BASE + ".abacus", List.of(Fixtures.BASE + ".abacus.api"), "audit");
        Fixtures.resource(root, "db/modules/abacus/V1__seed.sql",
                "insert into audit_entry (memo, note) values ('from-abacus', 'n');");
        return root;
    }

    @Test
    void ac_mode_10_module_runs_follow_requires_order_across_tools_on_a_fresh_database() throws Exception {
        // audit (Liquibase) requires ledger (Flyway) and seeds ledger_entry; abacus (Flyway) requires audit and seeds
        // audit_entry. By id, audit sorts before ledger and abacus before audit, so only the edges make this start.
        Fixtures.resource(auditRoot, "db/modules/audit/db.changelog-master.yaml", AUDIT_CHANGELOG
                + "  - include:\n      file: changes/003-seed.yaml\n      relativeToChangelogFile: true\n");
        Fixtures.resource(auditRoot, "db/modules/audit/changes/003-seed.yaml", """
                databaseChangeLog:
                  - changeSet:
                      id: audit-3
                      author: audit
                      changes:
                        - sql:
                            sql: insert into ledger_entry (memo) values ('from-audit')
                """);
        hostChangelog(HOST_CHANGELOG);
        run(Host.class, List.of(ledgerRoot, auditRoot, abacusRoot(), hostRoot), plus(withValidate(HOST_LIQUIBASE),
                "topolith.modules.ledger.mode=internal", "topolith.modules.audit.mode=internal",
                "topolith.modules.abacus.mode=internal"), context -> {
            JdbcTemplate jdbc = jdbc(context);
            assertEquals(List.of("from-audit"), jdbc.queryForList("select memo from ledger_entry", String.class));
            assertEquals(List.of("from-abacus"), jdbc.queryForList("select memo from audit_entry", String.class));
            assertEquals(List.of("V1__host.sql"), scripts(jdbc, "flyway_schema_history"));
            assertEquals(List.of("V1__seed.sql"), scripts(jdbc, "flyway_schema_history_abacus"));
            List<String> factory = List.of(context.getBeanFactory().getBeanDefinition("entityManagerFactory")
                    .getDependsOn());
            assertTrue(factory.containsAll(List.of("topolith.flyway.ledger", "topolith.liquibase.audit",
                    "topolith.flyway.abacus")), "JPA waits for every module run: " + factory);
            assertEquals(List.of("topolith.liquibase.audit"), requiredRunners(context, "topolith.flyway.abacus"));
            assertEquals(List.of("topolith.flyway.ledger"), requiredRunners(context, "topolith.liquibase.audit"));
        });
    }

    /** The module runners a module run waits for explicitly. */
    private static List<String> requiredRunners(AssertableApplicationContext context, String runner) {
        return List.of(context.getBeanFactory().getBeanDefinition(runner).getDependsOn()).stream()
                .filter(name -> name.startsWith("topolith.")).sorted().toList();
    }

    @Test
    void ac_mode_10_a_host_drop_first_runs_before_the_modules_and_is_not_copied() throws Exception {
        hostChangelog(HOST_CHANGELOG);
        run(Host.class, List.of(ledgerRoot, auditRoot, hostRoot), plus(HOST_LIQUIBASE,
                "topolith.modules.ledger.mode=internal", "topolith.modules.audit.mode=internal",
                "spring.liquibase.drop-first=true", "spring.jpa.hibernate.ddl-auto=none"), context -> {
            JdbcTemplate jdbc = jdbc(context);
            assertEquals(1, tables(jdbc, "host_marker"), "a module's dropFirst would have dropped the host's table");
            assertEquals(1, tables(jdbc, "audit_entry"));
            assertEquals(1, tables(jdbc, "databasechangelog_audit"));
            assertEquals(1, tables(jdbc, "ledger_entry"));
        });
    }

    @Test
    void ac_mode_10_a_host_context_filter_is_copied_to_the_module() throws Exception {
        Fixtures.resource(auditRoot, "db/modules/audit/changes/002-note.yaml", AUDIT_NOTE.replace("author: audit",
                "author: audit\n      context: prod"));
        hostChangelog(HOST_CHANGELOG);
        run(Host.class, List.of(ledgerRoot, auditRoot, hostRoot), plus(HOST_LIQUIBASE,
                "topolith.modules.ledger.mode=internal", "topolith.modules.audit.mode=internal",
                "spring.liquibase.contexts=dev", "spring.jpa.hibernate.ddl-auto=none"), context ->
                assertEquals(List.of("audit-1"), changesets(jdbc(context), "databasechangelog_audit"),
                        "the host runs the dev context only, and so does the module"));
        run(Host.class, List.of(ledgerRoot, auditRoot, hostRoot), plus(HOST_LIQUIBASE,
                "topolith.modules.ledger.mode=internal", "topolith.modules.audit.mode=internal",
                "spring.liquibase.contexts=prod", "spring.jpa.hibernate.ddl-auto=none"), context ->
                assertEquals(List.of("audit-1", "audit-2"), changesets(jdbc(context), "databasechangelog_audit")));
    }

    @Test
    void ac_mode_10_spring_flyway_enabled_false_switches_off_only_the_hosts_flyway() throws Exception {
        hostChangelog(HOST_CHANGELOG);
        run(Host.class, List.of(ledgerRoot, auditRoot, hostRoot), plus(List.of("spring.liquibase.enabled=true",
                "spring.flyway.enabled=false", "topolith.modules.ledger.mode=internal",
                "topolith.modules.audit.mode=internal", "spring.jpa.hibernate.ddl-auto=none")), context -> {
            assertFalse(context.containsBean("flywayInitializer"), "the host's Flyway is off");
            assertEquals(0, tables(jdbc(context), "flyway_schema_history"), "no host history");
            assertEquals(0, tables(jdbc(context), "host_entity"), "the host's script did not run");
            assertEquals(List.of("V1__init.sql"), scripts(jdbc(context), "flyway_schema_history_ledger"));
            assertEquals(List.of("audit-1", "audit-2"), changesets(jdbc(context), "databasechangelog_audit"));
            assertEquals(List.of("host-1"), changesets(jdbc(context), "databasechangelog"));
        });
    }

    @Test
    void ac_mode_10_spring_liquibase_enabled_false_switches_off_only_the_hosts_liquibase() throws Exception {
        run(Host.class, List.of(ledgerRoot, auditRoot, hostRoot), List.of("topolith.modules.ledger.mode=internal",
                "topolith.modules.audit.mode=internal", "spring.liquibase.enabled=false", VALIDATE), context -> {
            assertFalse(context.containsBean("liquibase"), "the host's Liquibase is off");
            assertEquals(0, tables(jdbc(context), "databasechangelog"), "no host tracking table");
            assertEquals(List.of("audit-1", "audit-2"), changesets(jdbc(context), "databasechangelog_audit"));
            assertEquals(List.of("V1__host.sql"), scripts(jdbc(context), "flyway_schema_history"));
            assertEquals(0, context.getBeansOfType(SpringLiquibase.class).size());
        });
    }

    @Test
    void ac_mode_10_both_host_switches_off_still_migrates_every_module() throws Exception {
        run(Host.class, List.of(ledgerRoot, auditRoot, hostRoot), List.of("topolith.modules.ledger.mode=internal",
                "topolith.modules.audit.mode=internal", "spring.liquibase.enabled=false",
                "spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=none"), context -> {
            assertEquals(1, tables(jdbc(context), "ledger_entry"));
            assertEquals(1, tables(jdbc(context), "audit_entry"));
            assertEquals(0, tables(jdbc(context), "flyway_schema_history"));
            assertEquals(0, tables(jdbc(context), "databasechangelog"));
        });
    }

    @Test
    void ac_mode_11_a_none_or_external_liquibase_module_leaves_no_table_bean_or_tracking_table() throws Exception {
        hostChangelog(HOST_CHANGELOG);
        for (String mode : List.of("none", "external")) {
            run(Host.class, List.of(ledgerRoot, auditRoot, hostRoot), plus(HOST_LIQUIBASE,
                    "topolith.modules.ledger.mode=internal", "topolith.modules.audit.mode=" + mode, VALIDATE),
                    context -> {
                assertEquals(Set.of("HostEntity", "LedgerEntry"),
                        entities(context.getBean(EntityManagerFactory.class)));
                assertEquals(0, context.getBeanNamesForType(AuditRepository.class).length);
                JdbcTemplate jdbc = jdbc(context);
                assertEquals(0, tables(jdbc, "%audit%"), mode + ": no audit table, no tracking table");
                assertFalse(context.containsBean("topolith.liquibase.audit"));
                assertEquals(List.of("host-1"), changesets(jdbc, "databasechangelog"));
            });
        }
    }

    @Test
    void ac_mode_11_a_host_include_all_over_db_modules_fails_with_tl0020_in_every_mode() throws Exception {
        hostChangelog("""
                databaseChangeLog:
                  - includeAll:
                      path: db/modules/
                """);
        for (String mode : List.of("none", "external", "internal")) {
            run(Host.class, List.of(ledgerRoot, auditRoot, hostRoot), plus(HOST_LIQUIBASE,
                    "topolith.modules.ledger.mode=internal", "topolith.modules.audit.mode=" + mode), context -> {
                assertTrue(codes(context).stream().allMatch(TopolithCode.TL0020::equals), mode);
                assertTrue(failure(context).diagnostics().stream().anyMatch(
                        diagnostic -> diagnostic.description().contains("belongs to module 'audit'")), mode);
            });
        }
    }

    @Test
    void ac_mode_11_a_module_artifact_that_ships_the_hosts_changelog_path_fails_with_tl0020() throws Exception {
        Fixtures.resource(auditRoot, HOST_CHANGELOG_PATH, HOST_CHANGELOG);
        hostChangelog(HOST_CHANGELOG);
        for (String mode : List.of("none", "internal")) {
            run(Host.class, List.of(ledgerRoot, auditRoot, hostRoot), plus(HOST_LIQUIBASE,
                    "topolith.modules.ledger.mode=internal", "topolith.modules.audit.mode=" + mode), context -> {
                assertEquals(List.of(TopolithCode.TL0020), codes(context), "found twice: " + mode);
                assertTrue(failure(context).getMessage().contains("2 times"), failure(context).getMessage());
            });
        }
        Files.delete(hostRoot.resolve(HOST_CHANGELOG_PATH));
        run(Host.class, List.of(ledgerRoot, auditRoot, hostRoot), plus(HOST_LIQUIBASE,
                "topolith.modules.ledger.mode=internal", "topolith.modules.audit.mode=none"), context -> {
            assertEquals(List.of(TopolithCode.TL0020), codes(context));
            assertTrue(failure(context).getMessage().contains("artifact of module 'audit'"),
                    failure(context).getMessage());
        });
    }

    @Test
    void ac_mode_11_a_host_include_of_a_legacy_file_in_a_module_artifact_fails_with_tl0020() throws Exception {
        Fixtures.resource(auditRoot, "db/changelog/changes/legacy.yaml", AUDIT_NOTE.replace("audit-2", "legacy-1"));
        hostChangelog("""
                databaseChangeLog:
                  - include:
                      file: db/changelog/changes/legacy.yaml
                """);
        run(Host.class, List.of(ledgerRoot, auditRoot, hostRoot), plus(HOST_LIQUIBASE,
                "topolith.modules.ledger.mode=internal", "topolith.modules.audit.mode=none"), context -> {
            assertEquals(List.of(TopolithCode.TL0020), codes(context));
            assertTrue(failure(context).getMessage().contains("from the artifact of module 'audit'"),
                    failure(context).getMessage());
        });
    }

    @Test
    void ac_mode_12_a_pattern_a_module_runs_its_own_non_default_liquibase_on_its_own_database() throws Exception {
        Fixtures.resource(inventoryRoot, "db/modules/inventory/db.changelog-master.yaml", """
                databaseChangeLog:
                  - changeSet:
                      id: inventory-1
                      author: inventory
                      changes:
                        - createTable:
                            tableName: inventory_marker
                            columns:
                              - column: {name: id, type: bigint}
                """);
        try {
            run(Host.class, List.of(inventoryRoot, hostRoot), List.of("topolith.modules.inventory.mode=internal",
                    "inventory.liquibase=own"), context -> {
                DataSource own = context.getBean("inventoryDataSource", DataSource.class);
                assertEquals(1, tables(new JdbcTemplate(own), "inventory_marker"));
                assertEquals(1, tables(new JdbcTemplate(own), "databasechangelog"));
                assertEquals(0, tables(jdbc(context), "inventory%"), "nothing lands in the host's database");
                assertEquals(Set.of("inventoryLiquibase"), context.getBeansOfType(SpringLiquibase.class).keySet(),
                        "only the module's own bean exists (the host's is off)");
                assertFalse(context.containsBean("topolith.liquibase.inventory"), "Topolith runs none for it");
            });
        } finally {
            dropInventory();
        }
    }

    @Test
    void ac_mode_12_a_pattern_a_module_with_a_default_candidate_liquibase_fails_with_tl0019() throws Exception {
        Fixtures.resource(inventoryRoot, "db/modules/inventory/db.changelog-master.yaml", "databaseChangeLog: []\n");
        try {
            run(Host.class, List.of(inventoryRoot, hostRoot), List.of("topolith.modules.inventory.mode=internal",
                    "inventory.liquibase=candidate"), context -> {
                assertEquals(List.of(TopolithCode.TL0019), codes(context));
                assertTrue(failure(context).getMessage().contains("SpringLiquibase"), failure(context).getMessage());
            });
            run(ServiceHost.class, List.of(inventoryRoot), List.of("topolith.standalone=inventory",
                    "inventory.liquibase=candidate", "spring.liquibase.enabled=true"),
                    context -> assertNull(context.getStartupFailure(), "the standalone module is exempt"));
        } finally {
            dropInventory();
        }
    }

    @Test
    void ac_mode_27_a_pattern_a_module_with_a_changelog_and_no_liquibase_fails_with_tl0020() throws Exception {
        Fixtures.resource(inventoryRoot, "db/modules/inventory/db.changelog-master.yaml", "databaseChangeLog: []\n");
        run(Host.class, List.of(inventoryRoot, hostRoot), List.of("topolith.modules.inventory.mode=internal"),
                context -> {
                    assertEquals(List.of(TopolithCode.TL0020), codes(context));
                    assertTrue(failure(context).getMessage().contains("contributes no SpringLiquibase"),
                            failure(context).getMessage());
                });
    }

    @Test
    void ac_mode_11_a_host_changelog_without_a_prefix_is_read_from_the_class_path() throws Exception {
        Fixtures.resource(hostRoot, "db/changelog/host.yaml", """
                databaseChangeLog:
                  - includeAll:
                      path: db/modules/
                """);
        for (String prefix : List.of("", "classpath*:")) {
            run(Host.class, List.of(ledgerRoot, auditRoot, hostRoot), plus(HOST_LIQUIBASE,
                    "topolith.modules.ledger.mode=internal", "topolith.modules.audit.mode=none",
                    "spring.liquibase.change-log=" + prefix + "db/changelog/host.yaml"), context -> {
                assertTrue(codes(context).stream().allMatch(TopolithCode.TL0020::equals), prefix + codes(context));
                assertTrue(failure(context).diagnostics().stream().anyMatch(
                        diagnostic -> diagnostic.description().contains("belongs to module 'audit'")), prefix);
            });
        }
    }

    @Test
    void ac_mode_10_host_sql_script_initializers_run_before_the_module_runs() throws Exception {
        Fixtures.resource(hostRoot, "schema.sql", "drop table if exists ledger_entry;\n"
                + "drop table if exists flyway_schema_history_ledger;\ndrop table if exists audit_entry;\n"
                + "drop table if exists databasechangelog_audit;\n");
        run(Host.class, List.of(ledgerRoot, auditRoot, hostRoot), List.of("topolith.modules.ledger.mode=internal",
                "topolith.modules.audit.mode=internal", "spring.sql.init.mode=always",
                "spring.jpa.hibernate.ddl-auto=none"), context -> {
            JdbcTemplate jdbc = jdbc(context);
            assertEquals(1, tables(jdbc, "ledger_entry"), "the host's script ran first");
            assertEquals(1, tables(jdbc, "flyway_schema_history_ledger"));
            assertEquals(1, tables(jdbc, "audit_entry"));
            assertEquals(List.of("audit-1", "audit-2"), changesets(jdbc, "databasechangelog_audit"));
        });
    }

    @Test
    void ac_mode_27_several_data_sources_with_no_primary_and_no_host_runner_fail_with_tl0020() throws Exception {
        FilteredClassLoader noJpa = new FilteredClassLoader("jakarta.persistence", "org.hibernate",
                "org.springframework.orm", "org.springframework.data");
        for (String module : List.of("ledger", "audit")) {
            run(TwoDataSourcesHost.class, noJpa, List.of(ledgerRoot, auditRoot, hostRoot),
                    List.of("topolith.modules.ledger.mode=" + ("ledger".equals(module) ? "internal" : "external"),
                            "topolith.modules.audit.mode=" + ("audit".equals(module) ? "internal" : "none"),
                            "spring.flyway.enabled=false"), context -> {
                assertEquals(List.of(TopolithCode.TL0020), codes(context), module);
                assertTrue(failure(context).getMessage().contains("several DataSources"),
                        failure(context).getMessage());
            });
        }
    }

    @Test
    void ac_mode_27_a_pattern_a_module_with_a_bare_flyway_bean_fails_with_tl0020() throws Exception {
        Fixtures.resource(inventoryRoot, "db/modules/inventory/V1__marker.sql",
                "create table inventory_marker (id bigint);");
        try {
            run(Host.class, List.of(inventoryRoot, hostRoot), List.of("topolith.modules.inventory.mode=internal",
                    "inventory.flyway=bare"), context -> {
                assertEquals(List.of(TopolithCode.TL0020), codes(context));
                assertTrue(failure(context).getMessage().contains("contributes no Flyway"),
                        failure(context).getMessage());
            });
            for (String variant : List.of("initializer", "init-method")) {
                run(Host.class, List.of(inventoryRoot, hostRoot), List.of("topolith.modules.inventory.mode=internal",
                        "inventory.flyway=" + variant), context -> {
                    assertNull(context.getStartupFailure(), variant + ": " + context.getStartupFailure());
                    DataSource own = context.getBean("inventoryDataSource", DataSource.class);
                    assertEquals(1, tables(new JdbcTemplate(own), "inventory_marker"), variant);
                    assertFalse(context.containsBean("topolith.flyway.inventory"), "Topolith runs none for it");
                });
                dropInventory();
            }
        } finally {
            dropInventory();
        }
    }

    private static void dropInventory() throws Exception {
        try (var connection = java.sql.DriverManager.getConnection(InventoryConfiguration.URL, "sa", "");
                var statement = connection.createStatement()) {
            statement.execute("drop all objects");
        }
    }

    @Test
    void ac_mode_27_a_module_with_flyway_scripts_and_a_changelog_fails_with_tl0020() throws Exception {
        Fixtures.resource(ledgerRoot, "db/modules/ledger/db.changelog-master.yaml", "databaseChangeLog: []\n");
        run(Host.class, List.of(ledgerRoot, hostRoot), List.of("topolith.modules.ledger.mode=internal"), context -> {
            assertEquals(List.of(TopolithCode.TL0020), codes(context));
            assertTrue(failure(context).getMessage().contains("both Flyway scripts and a Liquibase changelog"));
        });
    }

    @Test
    void ac_mode_27_a_liquibase_included_sql_file_with_a_flyway_name_counts_as_flyway() throws Exception {
        Fixtures.resource(auditRoot, "db/modules/audit/changes/V3__x.sql", "select 1;");
        run(Host.class, List.of(ledgerRoot, auditRoot, hostRoot), List.of("topolith.modules.ledger.mode=internal",
                "topolith.modules.audit.mode=internal"), context -> {
            assertEquals(List.of(TopolithCode.TL0020), codes(context));
            assertTrue(failure(context).getMessage().contains("rename it"), failure(context).getMessage());
        });
    }

    @Test
    void ac_mode_27_two_master_changelogs_in_one_module_fail_with_tl0020() throws Exception {
        Fixtures.resource(auditRoot, "db/modules/audit/db.changelog-master.xml", "<databaseChangeLog/>");
        run(Host.class, List.of(ledgerRoot, auditRoot, hostRoot), List.of("topolith.modules.ledger.mode=internal",
                "topolith.modules.audit.mode=internal"), context -> {
            assertEquals(List.of(TopolithCode.TL0020), codes(context));
            assertTrue(failure(context).getMessage().contains("2 master changelogs"), failure(context).getMessage());
        });
    }

    @Test
    void ac_mode_27_liquibase_hidden_while_a_changelog_ships_fails_with_tl0020_whatever_the_host_switch()
            throws Exception {
        for (String switchOff : List.of("spring.liquibase.enabled=true", "spring.liquibase.enabled=false")) {
            run(Host.class, new FilteredClassLoader("liquibase."), List.of(ledgerRoot, auditRoot, hostRoot),
                    List.of("topolith.modules.ledger.mode=internal", "topolith.modules.audit.mode=internal",
                            switchOff), context -> {
                assertEquals(List.of(TopolithCode.TL0020), codes(context));
                assertTrue(failure(context).getMessage().contains("Liquibase is not on the class path"),
                        failure(context).getMessage());
            });
        }
    }

    @Test
    void ac_mode_27_a_liquibase_table_name_over_63_characters_fails_with_tl0020() throws Exception {
        String id = "audit-" + "x".repeat(40);
        Files.delete(auditRoot.resolve("META-INF/topolith/modules/audit.properties"));
        Fixtures.module(auditRoot, id, AUDIT, List.of(AUDIT + ".api"));
        Fixtures.resource(auditRoot, "db/modules/" + id + "/db.changelog-master.yaml", "databaseChangeLog: []\n");
        run(Host.class, List.of(auditRoot, hostRoot), List.of("topolith.modules." + id + ".mode=internal"),
                context -> {
                    assertEquals(List.of(TopolithCode.TL0020), codes(context));
                    assertTrue(failure(context).getMessage().contains("63"));
                });
        assertEquals(63, ModuleMigrations.longestTable(ModuleMigrations.Tool.LIQUIBASE, "a".repeat(41)));
        assertEquals(63, ModuleMigrations.longestTable(ModuleMigrations.Tool.FLYWAY, "a".repeat(41)));
    }

    @Test
    void ac_mode_27_a_module_runner_without_any_data_source_fails_with_tl0020_for_each_tool() throws Exception {
        FilteredClassLoader noJpa = new FilteredClassLoader("jakarta.persistence", "org.hibernate",
                "org.springframework.orm", "org.springframework.data");
        for (String module : List.of("ledger", "audit")) {
            run(Host.class, noJpa, List.of(ledgerRoot, auditRoot, hostRoot), List.of("topolith.modules.ledger.mode="
                    + ("ledger".equals(module) ? "internal" : "external"), "topolith.modules.audit.mode="
                    + ("audit".equals(module) ? "internal" : "none"), "spring.autoconfigure.exclude="
                    + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration"), context -> {
                assertEquals(List.of(TopolithCode.TL0020), codes(context), module);
                assertTrue(failure(context).getMessage().contains("no DataSource"), failure(context).getMessage());
            });
        }
    }

    @Test
    void ac_mode_29_a_liquibase_module_standalone_then_composed_applies_nothing_the_second_time() throws Exception {
        liquibaseParity("parity_c", true);
    }

    @Test
    void ac_mode_29_a_liquibase_module_composed_then_standalone_applies_nothing_the_second_time() throws Exception {
        liquibaseParity("parity_d", false);
    }

    private static final String AUDIT_HISTORY = "select id, author, filename, md5sum, dateexecuted, orderexecuted "
            + "from databasechangelog_audit order by orderexecuted";

    private void liquibaseParity(String database, boolean standaloneFirst) throws Exception {
        Fixtures.module(auditRoot, "audit", AUDIT, List.of(AUDIT + ".api"));
        String url = "jdbc:h2:mem:" + database + ";DB_CLOSE_DELAY=-1";
        List<String> shared = List.of("spring.datasource.url=" + url, "spring.datasource.username=sa", VALIDATE);
        List<Map<String, Object>>[] first = new List[1];
        Assertions check = context -> {
            List<Map<String, Object>> history = jdbc(context).queryForList(AUDIT_HISTORY);
            if (first[0] == null) {
                first[0] = history;
                assertEquals(List.of("audit-1", "audit-2"), history.stream().map(row -> row.get("id")).toList());
            } else {
                assertEquals(first[0], history, "the second run finds nothing pending");
            }
        };
        try {
            for (int round = 0; round < 2; round++) {
                if (standaloneFirst == (round == 0)) {
                    run(ServiceHost.class, List.of(auditRoot, hostRoot), plus(shared, "topolith.standalone=audit"),
                            check);
                } else {
                    run(Host.class, List.of(auditRoot, hostRoot), plus(shared, "topolith.modules.audit.mode=internal"),
                            check);
                }
            }
        } finally {
            try (var connection = java.sql.DriverManager.getConnection(url, "sa", "");
                    var statement = connection.createStatement()) {
                statement.execute("drop all objects");
            }
        }
    }

    // ---- AC-MODE-04 with missing libraries -------------------------------------------------------------------

    @Test
    void ac_mode_04_a_host_starts_and_activates_modules_without_jpa_spring_data_flyway_or_liquibase() throws Exception {
        Path userRoot = Fixtures.dir(temp, "user");
        Fixtures.module(userRoot, "user", USER, List.of(USER_API));
        Map<String, String[]> hidden = Map.of("jpa", new String[] {"jakarta.persistence", "org.hibernate",
                "org.springframework.orm", "org.springframework.data"}, "data",
                new String[] {"org.springframework.data"},
                "flyway", new String[] {"org.flywaydb"}, "liquibase", new String[] {"liquibase."},
                "all", new String[] {"jakarta.persistence", "org.hibernate",
                "org.springframework.orm", "org.springframework.data", "org.flywaydb", "liquibase."});
        for (Map.Entry<String, String[]> entry : hidden.entrySet()) {
            for (String mode : List.of("internal", "none")) {
                run(Host.class, new FilteredClassLoader(entry.getValue()), List.of(userRoot, hostRoot),
                        List.of("topolith.modules.user.mode=" + mode), context -> {
                            assertNull(context.getStartupFailure(), entry.getKey() + "/" + mode + ": "
                                    + context.getStartupFailure());
                            assertEquals("internal".equals(mode) ? 1 : 0,
                                    context.getBeanNamesForType(UserComponent.class).length, entry.getKey());
                            assertEquals(!"jpa".equals(entry.getKey()) && !"all".equals(entry.getKey()),
                                    context.containsBean("persistenceManagedTypes"), entry.getKey());
                        });
            }
        }
    }
}
