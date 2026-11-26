package fixture.payment;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PaymentRoute {
    @GetMapping("/fixture-payment")
    public String payment() { return "payment"; }
}
