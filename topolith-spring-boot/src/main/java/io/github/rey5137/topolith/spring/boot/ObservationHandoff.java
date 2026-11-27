package io.github.rey5137.topolith.spring.boot;

import java.util.function.Function;
import java.util.function.Supplier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.service.invoker.HttpExchangeAdapter;
import org.springframework.web.service.invoker.HttpExchangeAdapterDecorator;
import org.springframework.web.service.invoker.HttpRequestValues;

/**
 * Carries the observation current on the calling thread to the thread that runs the HTTP exchange, so
 * {@code http.client.requests} stays a child of {@code topolith.module.call} when a circuit breaker moves the exchange
 * (R-EXT-11). It names no Micrometer type; the handle is whatever the {@link CallObserver} returns.
 */
final class ObservationHandoff {
    static final String OBSERVATION = ObservationHandoff.class.getName() + ".OBSERVATION";

    private ObservationHandoff() {
    }

    /** Stores the current observation on the request, on the calling thread. */
    static void capture(CallObserver observer, HttpRequestValues.Builder values) {
        Object current = observer.current();
        if (current != null) {
            values.addAttribute(OBSERVATION, current);
        }
    }

    /** The innermost decorator: reopens the captured observation around the adapter call. */
    static Function<HttpExchangeAdapter, HttpExchangeAdapter> decorator(CallObserver observer) {
        return adapter -> new Reopening(adapter, observer);
    }

    private static final class Reopening extends HttpExchangeAdapterDecorator {
        private final CallObserver observer;

        Reopening(HttpExchangeAdapter delegate, CallObserver observer) {
            super(delegate);
            this.observer = observer;
        }

        private <T> T within(HttpRequestValues values, Supplier<T> exchange) {
            try (CallObserver.Reopened scope = observer.reopen(values.getAttributes().get(OBSERVATION))) {
                return exchange.get();
            }
        }

        @Override
        public void exchange(HttpRequestValues values) {
            within(values, () -> {
                super.exchange(values);
                return null;
            });
        }

        @Override
        public HttpHeaders exchangeForHeaders(HttpRequestValues values) {
            return within(values, () -> super.exchangeForHeaders(values));
        }

        @Override
        public <T> T exchangeForBody(HttpRequestValues values, ParameterizedTypeReference<T> type) {
            return within(values, () -> super.exchangeForBody(values, type));
        }

        @Override
        public ResponseEntity<Void> exchangeForBodilessEntity(HttpRequestValues values) {
            return within(values, () -> super.exchangeForBodilessEntity(values));
        }

        @Override
        public <T> ResponseEntity<T> exchangeForEntity(HttpRequestValues values, ParameterizedTypeReference<T> type) {
            return within(values, () -> super.exchangeForEntity(values, type));
        }
    }
}
