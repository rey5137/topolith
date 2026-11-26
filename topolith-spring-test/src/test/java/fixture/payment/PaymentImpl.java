package fixture.payment;

import fixture.payment.api.Payment;
import org.springframework.web.bind.annotation.RestController;

/** The provider; a controller too, so a loopback call reaches it over HTTP (R-MODE-06). */
@RestController
public class PaymentImpl implements Payment {
    public String ping() { return "internal"; }
}
