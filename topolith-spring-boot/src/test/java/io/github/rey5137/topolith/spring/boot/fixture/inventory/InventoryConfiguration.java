package io.github.rey5137.topolith.spring.boot.fixture.inventory;

import java.util.Map;
import javax.sql.DataSource;

import jakarta.persistence.EntityManagerFactory;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;

/** Pattern A: the module brings its own database, entity-manager factory and transaction manager (R-MODE-04). */
@Configuration(proxyBeanMethods = false)
@EnableJpaRepositories(basePackageClasses = InventoryRepository.class,
        entityManagerFactoryRef = "inventoryEntityManagerFactory",
        transactionManagerRef = "inventoryTransactionManager")
public class InventoryConfiguration {
    public static final String URL = "jdbc:h2:mem:inventory;DB_CLOSE_DELAY=-1";

    @Bean(defaultCandidate = false)
    @Qualifier("inventory")
    public DataSource inventoryDataSource() {
        return new DriverManagerDataSource(URL, "sa", "");
    }

    @Bean(defaultCandidate = false)
    @Qualifier("inventory")
    public LocalContainerEntityManagerFactoryBean inventoryEntityManagerFactory(
            @Qualifier("inventory") DataSource dataSource) {
        LocalContainerEntityManagerFactoryBean factory = new LocalContainerEntityManagerFactoryBean();
        factory.setDataSource(dataSource);
        factory.setPackagesToScan(InventoryItem.class.getPackageName());
        factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "create-drop"));
        return factory;
    }

    @Bean(defaultCandidate = false)
    @Qualifier("inventory")
    public JpaTransactionManager inventoryTransactionManager(
            @Qualifier("inventory") EntityManagerFactory entityManagerFactory) {
        return new JpaTransactionManager(entityManagerFactory);
    }
}
