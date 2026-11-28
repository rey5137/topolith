package fixture.ledger;

import java.lang.reflect.Proxy;
import java.util.List;

import fixture.invoice.InvoiceRates;
import fixture.ledger.api.Rates;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The listed contract served by beans whose classes lie outside the module's package (D-110). */
@Configuration(proxyBeanMethods = false)
public class LedgerConfiguration {
    /** A JDK proxy: its class lies in a {@code jdk.proxy} package. */
    @Bean
    @ConditionalOnProperty(name = "ledger.shape", havingValue = "proxy")
    Rates rates() {
        Rates target = values -> {
            values.set(0, 99);
            return values.size();
        };
        return (Rates) Proxy.newProxyInstance(Rates.class.getClassLoader(), new Class<?>[] {Rates.class},
                (proxy, method, arguments) -> method.invoke(target, arguments));
    }

    /** A class owned by another module's package. */
    @Bean
    @ConditionalOnProperty(name = "ledger.shape", havingValue = "foreign")
    InvoiceRates invoiceRates() { return new InvoiceRates(); }
}
