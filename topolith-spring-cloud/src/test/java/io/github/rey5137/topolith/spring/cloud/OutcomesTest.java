package io.github.rey5137.topolith.spring.cloud;

import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeoutException;

import io.github.rey5137.topolith.contract.ModuleUnavailableException;
import io.github.rey5137.topolith.core.Diagnostic;
import io.github.rey5137.topolith.core.TopolithCode;
import io.github.rey5137.topolith.core.TopolithException;
import io.github.rey5137.topolith.spring.boot.internal.Outcomes;
import io.github.rey5137.topolith.spring.boot.internal.Outcomes.Outcome;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.client.circuitbreaker.NoFallbackAvailableException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** D-105: one classification of an attempt's failure for retry and the breaker (R-EXT-14, R-EXT-15). */
class OutcomesTest {
    private static RestClientResponseException response(int status, boolean problem) {
        HttpHeaders headers = new HttpHeaders();
        String body = "plain";
        if (problem) {
            headers.setContentType(MediaType.APPLICATION_PROBLEM_JSON);
            body = "{\"type\":\"" + Outcomes.PROBLEM_TYPE + "\",\"status\":" + status + ",\"exception\":\"x.Y\"}";
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        return status < 500 ? HttpClientErrorException.create(HttpStatusCode.valueOf(status), "s", headers, bytes,
                StandardCharsets.UTF_8) : HttpServerErrorException.create(HttpStatusCode.valueOf(status), "s",
                headers, bytes, StandardCharsets.UTF_8);
    }

    private static CallNotPermittedException open() {
        CircuitBreaker breaker = CircuitBreaker.ofDefaults("t");
        breaker.transitionToOpenState();
        return CallNotPermittedException.createCallNotPermittedException(breaker);
    }

    @Test
    void ac_ext_64_transport_failures_and_502_to_504_without_a_problem_are_transient() {
        assertEquals(Outcome.TRANSIENT, Outcomes.classify(new ResourceAccessException("io")));
        assertEquals(Outcome.TRANSIENT, Outcomes.classify(new ResourceAccessException("t",
                new SocketTimeoutException())));
        assertEquals(Outcome.TRANSIENT, Outcomes.classify(new TimeoutException()));
        for (int status : new int[] {502, 503, 504}) {
            assertEquals(Outcome.TRANSIENT, Outcomes.classify(response(status, false)), "status " + status);
        }
    }

    @Test
    void ac_ext_65_other_5xx_and_no_instance_are_failures_the_breaker_records_but_retry_does_not_repeat() {
        assertEquals(Outcome.FAILURE, Outcomes.classify(response(500, false)));
        assertEquals(Outcome.FAILURE, Outcomes.classify(response(501, false)));
        assertEquals(Outcome.FAILURE, Outcomes.classify(new IllegalStateException(Outcomes.NO_INSTANCES + " x")));
    }

    @Test
    void ac_ext_64_everything_else_is_ignored_and_not_retried() {
        for (int status : new int[] {400, 401, 404, 429}) {
            assertEquals(Outcome.OTHER, Outcomes.classify(response(status, false)), "status " + status);
        }
        assertEquals(Outcome.OTHER, Outcomes.classify(new TopolithException(new Diagnostic(TopolithCode.TL0038, "m",
                "f"))));
        assertEquals(Outcome.OTHER, Outcomes.classify(new ModuleUnavailableException("a", "m")));
        assertEquals(Outcome.OTHER, Outcomes.classify(new IllegalStateException("other")));
        assertEquals(Outcome.OTHER, Outcomes.classify(new RuntimeException("host wrapper",
                new ResourceAccessException("io"))));
    }

    @Test
    void ac_ext_64_an_open_circuit_stops_retry_and_is_not_a_breaker_failure() {
        assertEquals(Outcome.OPEN, Outcomes.classify(open()));
        assertEquals(Outcome.OPEN, Outcomes.classify(new NoFallbackAvailableException("n", open())));
    }

    @Test
    void ac_ext_63_one_no_fallback_wrapper_is_looked_through_and_no_other() {
        assertEquals(Outcome.TRANSIENT, Outcomes.classify(new NoFallbackAvailableException("n",
                new ResourceAccessException("io"))));
        assertEquals(Outcome.TRANSIENT, Outcomes.classify(new NoFallbackAvailableException("n",
                new TimeoutException())));
        assertEquals(Outcome.TRANSIENT, Outcomes.classify(new NoFallbackAvailableException("n", response(503, false))));
        assertEquals(Outcome.OTHER, Outcomes.classify(new NoFallbackAvailableException("n", response(404, false))));
        assertEquals(Outcome.OTHER, Outcomes.classify(new NoFallbackAvailableException("n",
                new NoFallbackAvailableException("n", new ResourceAccessException("io")))));
        assertEquals(Outcome.OTHER, Outcomes.classify(new NoFallbackAvailableException("n", null)));
    }
}
