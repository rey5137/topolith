package io.github.rey5137.topolith.samples.payment;

import java.math.BigDecimal;

import org.springframework.boot.context.properties.ConfigurationProperties;

// snippet:start payment-properties
/** The payment module's own defaults; a module JAR carries no root {@code application.yml}. */
@ConfigurationProperties(prefix = "payment")
public class PaymentProperties {
    private BigDecimal maxAmount = new BigDecimal("1000");

    public BigDecimal getMaxAmount() {
        return maxAmount;
    }

    public void setMaxAmount(BigDecimal maxAmount) {
        this.maxAmount = maxAmount;
    }
}
// snippet:end payment-properties
