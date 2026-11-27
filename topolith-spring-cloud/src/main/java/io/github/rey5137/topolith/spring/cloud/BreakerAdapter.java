package io.github.rey5137.topolith.spring.cloud;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.service.invoker.HttpExchangeAdapter;
import org.springframework.web.service.invoker.HttpExchangeAdapterDecorator;
import org.springframework.web.service.invoker.HttpRequestValues;

/**
 * Runs each exchange attempt of one group through its circuit breaker (R-EXT-15). The breaker is the one the call
 * captured when it started (D-107), else the group's current one; an empty reference means the group has none.
 */
final class BreakerAdapter extends HttpExchangeAdapterDecorator {
    private final AtomicReference<BreakerCall> current;

    BreakerAdapter(HttpExchangeAdapter delegate, AtomicReference<BreakerCall> current) {
        super(delegate);
        this.current = current;
    }

    private <T> T run(Supplier<T> attempt) {
        return CallScope.breaker(current).run(attempt);
    }

    @Override
    public void exchange(HttpRequestValues values) {
        run(() -> {
            super.exchange(values);
            return null;
        });
    }

    @Override
    public HttpHeaders exchangeForHeaders(HttpRequestValues values) {
        return run((Supplier<HttpHeaders>) () -> super.exchangeForHeaders(values));
    }

    @Override
    public <T> T exchangeForBody(HttpRequestValues values, ParameterizedTypeReference<T> type) {
        return run(() -> super.exchangeForBody(values, type));
    }

    @Override
    public ResponseEntity<Void> exchangeForBodilessEntity(HttpRequestValues values) {
        return run(() -> super.exchangeForBodilessEntity(values));
    }

    @Override
    public <T> ResponseEntity<T> exchangeForEntity(HttpRequestValues values, ParameterizedTypeReference<T> type) {
        return run(() -> super.exchangeForEntity(values, type));
    }
}
