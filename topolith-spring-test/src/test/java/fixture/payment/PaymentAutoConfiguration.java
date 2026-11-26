package fixture.payment;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
public class PaymentAutoConfiguration {
    @Bean
    public PaymentMarker paymentMarker() { return new PaymentMarker(); }

    public static class PaymentMarker { }
}
