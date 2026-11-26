package fixture.payment;

import fixture.payment.api.Payment;
import org.springframework.stereotype.Component;

@Component
public class PaymentImpl implements Payment {
    public String ping() { return "internal"; }
}
