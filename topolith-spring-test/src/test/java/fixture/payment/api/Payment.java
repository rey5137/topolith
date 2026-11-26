package fixture.payment.api;

import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;

@HttpExchange
public interface Payment {
    @GetExchange("/ping")
    String ping();
}
