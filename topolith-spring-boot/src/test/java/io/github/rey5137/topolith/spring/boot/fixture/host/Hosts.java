package io.github.rey5137.topolith.spring.boot.fixture.host;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;

import org.springframework.boot.autoconfigure.AutoConfigurationExcludeFilter;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.core.io.ResourceLoader;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypesScanner;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Test hosts. They sit in a package disjoint from every fixture module package, because their auto-configuration
 * package would otherwise overlap a module (TL0018, D-32).
 */
public final class Hosts {
    private static final String FIXTURE = "io.github.rey5137.topolith.spring.boot.fixture";

    private Hosts() {
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @EnableScheduling
    public static class Host {
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @EnableScheduling
    @ComponentScan(basePackages = FIXTURE + ".user", excludeFilters = {
            @ComponentScan.Filter(type = FilterType.CUSTOM, classes = TypeExcludeFilter.class),
            @ComponentScan.Filter(type = FilterType.CUSTOM, classes = AutoConfigurationExcludeFilter.class)})
    public static class UserScanningHost {
    }

    /** Scans the whole fixture package, less the hosts and the persistence fixtures, which are not part of M1.3. */
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @EnableScheduling
    @ComponentScan(basePackages = FIXTURE, excludeFilters = {
            @ComponentScan.Filter(type = FilterType.CUSTOM, classes = TypeExcludeFilter.class),
            @ComponentScan.Filter(type = FilterType.CUSTOM, classes = AutoConfigurationExcludeFilter.class),
            @ComponentScan.Filter(type = FilterType.REGEX,
                    pattern = "io\\.github\\.rey5137\\.topolith\\.spring\\.boot\\.fixture\\."
                            + "(host|service|ledger|billing|audit|inventory|rogue|shop|depot)\\..*")})
    public static class ParentScanningHost {
    }

    /** An {@code @EntityScan} of the host's own package: module packages are still added by Topolith. */
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @EntityScan(FIXTURE + ".host")
    public static class EntityScanHost {
    }

    /** An {@code @EntityScan} that reaches into a module's package (TL0018). */
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @EntityScan(FIXTURE + ".ledger")
    public static class LedgerEntityScanHost {
    }

    /** A host that declares its own managed types, leaving every module out (TL0021 for an internal module). */
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    public static class ManagedTypesHost {
        @Bean
        static PersistenceManagedTypes persistenceManagedTypes(ResourceLoader resourceLoader) {
            return new PersistenceManagedTypesScanner(resourceLoader).scan(FIXTURE + ".host");
        }
    }

    /** A host that registers a module's repositories itself with an explicit {@code @EnableJpaRepositories}. */
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @EnableJpaRepositories(basePackages = FIXTURE + ".ledger")
    public static class LedgerRepositoriesHost {
    }

    /** A host whose own managed types include the ledger package. */
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    public static class LedgerManagedTypesHost {
        @Bean
        static PersistenceManagedTypes persistenceManagedTypes(ResourceLoader resourceLoader) {
            return new PersistenceManagedTypesScanner(resourceLoader).scan(FIXTURE + ".host", FIXTURE + ".ledger");
        }
    }

    /** A host with a second entity-manager factory that also manages the ledger package. */
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    public static class SecondFactoryHost {
        @Bean(defaultCandidate = false)
        static LocalContainerEntityManagerFactoryBean secondEntityManagerFactory(DataSource dataSource) {
            LocalContainerEntityManagerFactoryBean factory = new LocalContainerEntityManagerFactoryBean();
            factory.setDataSource(dataSource);
            factory.setPackagesToScan(FIXTURE + ".ledger");
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            return factory;
        }
    }

    /** A host that declares its own Flyway bean: Boot then registers no migration initializer. */
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    public static class CustomFlywayHost {
        @Bean
        static Flyway flyway(DataSource dataSource) {
            return Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load();
        }
    }

    /** A host with two default-candidate DataSources and no primary one. */
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    public static class TwoDataSourcesHost {
        @Bean
        static DataSource firstDataSource() {
            return new DriverManagerDataSource("jdbc:h2:mem:first", "sa", "");
        }

        @Bean
        static DataSource secondDataSource() {
            return new DriverManagerDataSource("jdbc:h2:mem:second", "sa", "");
        }
    }
}
