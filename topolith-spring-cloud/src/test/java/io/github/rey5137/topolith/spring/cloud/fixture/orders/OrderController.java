package io.github.rey5137.topolith.spring.cloud.fixture.orders;

import java.util.concurrent.atomic.AtomicInteger;

import io.github.rey5137.topolith.spring.cloud.fixture.orders.api.OrderContract;
import io.github.rey5137.topolith.spring.cloud.fixture.orders.api.OrderRejectedException;
import org.springframework.web.bind.annotation.RestController;

/** The provider of {@link OrderContract}, a loopback module in {@code ResilienceTest}; it counts its calls. */
@RestController
public class OrderController implements OrderContract {
    /** How many times any method ran, to tell the attempts that reached the provider. */
    public final AtomicInteger calls = new AtomicInteger();

    @Override
    public String read() {
        calls.incrementAndGet();
        return "read";
    }

    @Override
    public String replace() {
        calls.incrementAndGet();
        return "replace";
    }

    @Override
    public String peek() {
        calls.incrementAndGet();
        return "peek";
    }

    @Override
    public String save() {
        calls.incrementAndGet();
        return "save";
    }

    @Override
    public String create() {
        calls.incrementAndGet();
        return "create";
    }

    @Override
    public String remove() {
        calls.incrementAndGet();
        return "remove";
    }

    @Override
    public String reject() {
        calls.incrementAndGet();
        throw new OrderRejectedException("rejected");
    }

    @Override
    public String missing() {
        calls.incrementAndGet();
        return "missing";
    }
}
