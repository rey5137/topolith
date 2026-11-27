package io.github.rey5137.topolith.spring.boot;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What a refresh left behind, free of Spring Cloud types (D-50): the requested modes that differ from the running
 * ones, the modules whose URL/service-id wiring needs a restart, and each module's last failed client refresh. The
 * actuator endpoint reads it; {@link TopolithRefresh} writes it.
 */
final class TopolithRefreshStatus {
    record Failure(String message, Instant at) {
    }

    private volatile Map<String, String> requestedModes = Map.of();
    private volatile Set<String> wiring = Set.of();
    /** Failures by module and part ({@link #ENDPOINT}, {@link #RESILIENCE}): one part's success keeps the other's. */
    private final Map<String, Failure> failures = new ConcurrentHashMap<>();

    static final String ENDPOINT = "endpoint";
    static final String RESILIENCE = "resilience";

    /** Replaces the computed restart state; it is recomputed on every refresh, so it clears by itself (D-13). */
    void update(Map<String, String> requestedModes, Set<String> wiring) {
        this.requestedModes = Map.copyOf(requestedModes);
        this.wiring = Set.copyOf(wiring);
    }

    Map<String, String> requestedModes() {
        return requestedModes;
    }

    Set<String> wiring() {
        return wiring;
    }

    boolean restartPending() {
        return !requestedModes.isEmpty() || !wiring.isEmpty();
    }

    void fail(String module, String part, String message) {
        failures.put(module + "/" + part, new Failure(message, Instant.now()));
    }

    void succeed(String module, String part) {
        failures.remove(module + "/" + part);
    }

    /** The module's latest failure over its parts. */
    Failure failure(String module) {
        Failure latest = null;
        for (String part : new String[] {ENDPOINT, RESILIENCE}) {
            Failure candidate = failures.get(module + "/" + part);
            if (candidate != null && (latest == null || candidate.at().isAfter(latest.at()))) {
                latest = candidate;
            }
        }
        return latest;
    }
}
