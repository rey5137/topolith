package io.github.rey5137.topolith.spring.cloud.fixture.pay;

import io.github.rey5137.topolith.spring.cloud.fixture.pay.api.PayContract;
import org.springframework.web.bind.annotation.RestController;

/** The provider of {@link PayContract}, a loopback module in {@code LoopbackLoadBalancerTest}. */
@RestController
public class PayController implements PayContract {
    @Override
    public String ping() {
        return "pong";
    }
}
