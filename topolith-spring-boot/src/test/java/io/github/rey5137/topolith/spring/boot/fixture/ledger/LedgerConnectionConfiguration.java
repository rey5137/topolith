package io.github.rey5137.topolith.spring.boot.fixture.ledger;

import java.lang.reflect.Proxy;
import javax.sql.DataSource;

import io.r2dbc.spi.ConnectionFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * A reactive ledger module's own R2DBC connection factory ({@code ledger.connection-factory=true}), and optionally a
 * DataSource of its own ({@code ledger.data-source=true}), for D-134.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "ledger.connection-factory", havingValue = "true")
public class LedgerConnectionConfiguration {
    public static final String URL = "jdbc:h2:mem:ledger-own;DB_CLOSE_DELAY=-1";

    @Bean(defaultCandidate = false)
    public ConnectionFactory ledgerConnectionFactory() {
        return (ConnectionFactory) Proxy.newProxyInstance(ConnectionFactory.class.getClassLoader(),
                new Class<?>[] {ConnectionFactory.class}, (proxy, method, args) -> {
                    return switch (method.getName()) {
                        case "toString" -> "ledgerConnectionFactory";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> throw new UnsupportedOperationException(method.getName());
                    };
                });
    }

    @Bean(defaultCandidate = false)
    @ConditionalOnProperty(name = "ledger.data-source", havingValue = "true")
    public DataSource ledgerDataSource() {
        return new DriverManagerDataSource(URL, "sa", "");
    }
}
