package fixture.payment.api;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.CONFLICT)
public class PaymentConflict extends RuntimeException {
    public PaymentConflict(String message) { super(message); }
}
