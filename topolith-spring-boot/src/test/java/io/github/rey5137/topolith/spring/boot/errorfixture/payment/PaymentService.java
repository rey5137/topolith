package io.github.rey5137.topolith.spring.boot.errorfixture.payment;

import io.github.rey5137.topolith.spring.boot.errorfixture.payment.api.PaymentContract;
import org.springframework.stereotype.Service;

/** The provider of {@link PaymentContract}, present only when the payment module is internal. */
@Service
public class PaymentService implements PaymentContract {
    @Override
    public String pay() {
        return "ok";
    }
}
