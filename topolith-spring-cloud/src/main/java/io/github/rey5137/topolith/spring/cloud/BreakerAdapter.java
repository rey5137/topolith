package io.github.rey5137.topolith.spring.cloud;

import java.util.function.Supplier;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.service.invoker.HttpExchangeAdapter;
import org.springframework.web.service.invoker.HttpExchangeAdapterDecorator;
import org.springframework.web.service.invoker.HttpRequestValues;

/** Runs each exchange attempt of one group through its circuit breaker (R-EXT-15). */
final class BreakerAdapter extends HttpExchangeAdapterDecorator {
    private final BreakerCall breaker;

    BreakerAdapter(HttpExchangeAdapter delegate, BreakerCall breaker) {
        super(delegate);
        this.breaker = breaker;
    }

    @Override
    public void exchange(HttpRequestValues values) {
        breaker.run(() -> {
            super.exchange(values);
            return null;
        });
    }

    @Override
    public HttpHeaders exchangeForHeaders(HttpRequestValues values) {
        return breaker.run((Supplier<HttpHeaders>) () -> super.exchangeForHeaders(values));
    }

    @Override
    public <T> T exchangeForBody(HttpRequestValues values, ParameterizedTypeReference<T> type) {
        return breaker.run(() -> super.exchangeForBody(values, type));
    }

    @Override
    public ResponseEntity<Void> exchangeForBodilessEntity(HttpRequestValues values) {
        return breaker.run(() -> super.exchangeForBodilessEntity(values));
    }

    @Override
    public <T> ResponseEntity<T> exchangeForEntity(HttpRequestValues values, ParameterizedTypeReference<T> type) {
        return breaker.run(() -> super.exchangeForEntity(values, type));
    }
}
