package io.github.rey5137.topolith.spring.boot.internal;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import io.github.rey5137.topolith.core.Diagnostic;
import io.github.rey5137.topolith.core.Incubating;
import io.github.rey5137.topolith.core.Mode;
import io.github.rey5137.topolith.core.TopolithCode;
import io.github.rey5137.topolith.core.Topology;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.core.env.Environment;
import org.springframework.util.ClassUtils;

/**
 * A module's {@code remote.retry.*} and {@code remote.circuit-breaker.*} settings (R-EXT-14, R-EXT-15): their
 * validation (TL0003, TL0041), their reading, and their defaults. Not public API (D-109).
 */
@Incubating
public final class ResilienceSettings {
    /** The class whose presence stands for {@code topolith-spring-cloud}'s resilience configuration (TL0041). */
    public static final String CLOUD_CONFIGURATION =
            "io.github.rey5137.topolith.spring.cloud.TopolithResilienceAutoConfiguration";
    /** Spring Cloud's global HTTP-service circuit breaker, which Topolith's per-group retry and breaker exclude. */
    public static final String GLOBAL_BREAKER = "spring.cloud.circuitbreaker.http-services.enabled";

    private static final String MODULES = "topolith.modules";
    private static final Duration DEFAULT_INITIAL = Duration.ofMillis(100);
    private static final Duration FLOOR_MAX = Duration.ofSeconds(1);
    private static final int DEFAULT_WINDOW = 100;
    private static final int DEFAULT_MINIMUM = 10;
    private static final List<String> RETRY_KEYS = List.of("max-attempts", "initial-backoff", "max-backoff",
            "multiplier", "safe-methods-idempotent");
    private static final List<String> BREAKER_KEYS = List.of("enabled", "failure-rate-threshold",
            "sliding-window-size", "minimum-number-of-calls", "wait-duration-in-open-state");

    /** Per-group retry; off while {@code maxAttempts} is {@code 1}. */
    public record Retry(int maxAttempts, Duration initialBackoff, Duration maxBackoff, double multiplier,
            boolean safeMethodsIdempotent) {
        /** Whether the group retries an eligible call. */
        public boolean active() {
            return maxAttempts > 1;
        }
    }

    /** Per-group circuit breaker; off while {@code enabled} is false. */
    public record Breaker(boolean enabled, float failureRateThreshold, int slidingWindowSize,
            int minimumNumberOfCalls, Duration waitDurationInOpenState) {
    }

    private ResilienceSettings() {
    }

    /** Module {@code id}'s retry settings; defaults for what is unset. The values are those {@link #check} accepted. */
    public static Retry retry(Binder binder, String id) {
        return parseRetry(read(binder, id, "retry", RETRY_KEYS), null, "");
    }

    /** Module {@code id}'s circuit-breaker settings; defaults for what is unset. */
    public static Breaker breaker(Binder binder, String id) {
        return parseBreaker(read(binder, id, "circuit-breaker", BREAKER_KEYS), null, "");
    }

    private static Map<String, String> read(Binder binder, String id, String group, List<String> keys) {
        Map<String, String> values = new TreeMap<>();
        // an environment variable cannot carry a dash: ORDERSERVICE binds as "orderservice" (D-50 known limit)
        for (String name : List.of(id, id.replace("-", ""))) {
            String prefix = MODULES + "." + name + ".remote." + group + ".";
            for (String key : keys) {
                binder.bind(prefix + key, String.class).ifBound(value -> values.putIfAbsent(key, value));
            }
        }
        return values;
    }

    /**
     * TL0003 for every retry and circuit-breaker key of a module that is not external or loopback, every unknown key
     * and every invalid value, for the global-breaker conflict, and TL0041 when retry or the breaker is set without
     * {@code topolith-spring-cloud}'s resilience configuration.
     */
    public static void check(Environment env, Binder binder, Topology topology, ClassLoader loader,
            List<Diagnostic> problems) {
        check(env, binder, topology, loader, problems, null, true);
    }

    /**
     * As {@link #check} for the settings of module {@code id} only: what a refresh of that module validates. Spring
     * Cloud reads its global breaker key only at startup, so a refresh never tests it (D-106).
     */
    public static void checkModule(Environment env, Binder binder, Topology topology, ClassLoader loader, String id,
            List<Diagnostic> problems) {
        check(env, binder, topology, loader, problems, id, false);
    }

    private static void check(Environment env, Binder binder, Topology topology, ClassLoader loader,
            List<Diagnostic> problems, String only, boolean startup) {
        Map<String, Object> modules;
        try {
            modules = binder.bind(MODULES, Bindable.mapOf(String.class, Object.class)).orElse(Map.of());
        } catch (BindException e) {
            return;
        }
        boolean global = "true".equalsIgnoreCase(String.valueOf(env.getProperty(GLOBAL_BREAKER)).trim());
        boolean cloud = ClassUtils.isPresent(CLOUD_CONFIGURATION, loader);
        Map<String, String> compact = new TreeMap<>();
        topology.known().forEach(known -> compact.put(known.replace("-", ""), known));
        new TreeMap<>(modules).forEach((written, value) -> {
            if (!(value instanceof Map<?, ?> settings) || !(settings.get("remote") instanceof Map<?, ?> remote)) {
                return;
            }
            String id = topology.known().contains(written) ? written
                    : compact.getOrDefault(written.replace("-", ""), written);
            if (only != null && !only.equals(id)) {
                return;
            }
            Map<String, String> retry = new TreeMap<>();
            Map<String, String> breaker = new TreeMap<>();
            String base = MODULES + "." + written + ".remote.";
            flatten(base + "retry", remote.get("retry"), retry);
            flatten(base + "circuit-breaker", remote.get("circuit-breaker"), breaker);
            if (retry.isEmpty() && breaker.isEmpty()) {
                return;
            }
            boolean applies = topology.known().contains(id)
                    && (topology.mode(id) == Mode.EXTERNAL || topology.mode(id) == Mode.LOOPBACK);
            if (!applies) {
                String what = topology.known().contains(id) ? "has mode " + topology.mode(id).id() : "is unknown";
                for (String key : List.of(retry, breaker).stream().flatMap(map -> map.keySet().stream()).toList()) {
                    problems.add(new Diagnostic(TopolithCode.TL0003, "module '" + id + "' " + what + ", so " + key
                            + " does not apply.", "Remove " + key + ", or set the module's mode to external or "
                            + "loopback."));
                }
                return;
            }
            Retry parsedRetry = parseRetry(named(retry, base + "retry", RETRY_KEYS, problems), problems,
                    base + "retry.");
            Breaker parsedBreaker = parseBreaker(named(breaker, base + "circuit-breaker", BREAKER_KEYS, problems),
                    problems, base + "circuit-breaker.");
            if (parsedRetry.active()) {
                clash(startup && global, problems, base + "retry.max-attempts", id);
                needsCloud(cloud, problems, base + "retry.max-attempts", id);
            }
            if (parsedBreaker.enabled()) {
                clash(startup && global, problems, base + "circuit-breaker.enabled", id);
                needsCloud(cloud, problems, base + "circuit-breaker.enabled", id);
            }
        });
    }

    private static void clash(boolean global, List<Diagnostic> problems, String key, String id) {
        if (global) {
            problems.add(new Diagnostic(TopolithCode.TL0003, "module '" + id + "' sets " + key + " while "
                    + GLOBAL_BREAKER + "=true makes Spring Cloud own the HTTP-service circuit breaker.",
                    "Set " + GLOBAL_BREAKER + "=false, or remove " + key + "."));
        }
    }

    /** TL0041: {@code key} (relative to {@code remote.}) is on while the resilience configuration is not running. */
    public static Diagnostic notActive(String id, String key) {
        String full = "topolith.modules." + id + ".remote." + key;
        return new Diagnostic(TopolithCode.TL0041, "module '" + id + "' sets " + full + ", but "
                + "topolith-spring-cloud's resilience configuration is not active: it is missing or excluded.",
                "Activate TopolithResilienceAutoConfiguration, or turn " + full + " off.");
    }

    private static void needsCloud(boolean cloud, List<Diagnostic> problems, String key, String id) {
        if (!cloud) {
            problems.add(new Diagnostic(TopolithCode.TL0041, "module '" + id + "' sets " + key
                    + ", but topolith-spring-cloud's resilience configuration is not on the classpath.",
                    "Add topolith-spring-cloud, or turn " + key + " off."));
        }
    }

    /** The values under their canonical key; an unknown key is a problem. */
    private static Map<String, String> named(Map<String, String> flat, String prefix, List<String> keys,
            List<Diagnostic> problems) {
        Map<String, String> known = new LinkedHashMap<>();
        flat.forEach((key, text) -> {
            String name = key.length() > prefix.length() ? key.substring(prefix.length() + 1) : "";
            String canonical = keys.stream().filter(candidate -> squash(candidate).equals(squash(name)))
                    .findFirst().orElse(null);
            if (canonical == null) {
                problems.add(new Diagnostic(TopolithCode.TL0003, "unknown setting " + key + ".",
                        "Use " + prefix + "." + String.join(", ." , keys) + "."));
            } else {
                known.put(canonical, text);
            }
        });
        return known;
    }

    private static String squash(String name) {
        return name.replace("-", "").toLowerCase(Locale.ROOT);
    }

    private static void flatten(String prefix, Object value, Map<String, String> into) {
        if (value == null) {
            return;
        }
        if (value instanceof Map<?, ?> map) {
            map.forEach((key, inner) -> flatten(prefix + "." + key, inner, into));
        } else {
            into.put(prefix, String.valueOf(value));
        }
    }

    private static Retry parseRetry(Map<String, String> values, List<Diagnostic> problems, String prefix) {
        int attempts = 1;
        Duration initial = DEFAULT_INITIAL;
        Duration max = null;
        double multiplier = 2.0;
        boolean safe = false;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            String text = entry.getValue().trim();
            try {
                switch (entry.getKey()) {
                    case "max-attempts" -> {
                        attempts = Integer.parseInt(text);
                        if (attempts < 1 || attempts > 10) {
                            attempts = 1;
                            throw new IllegalArgumentException("outside [1, 10]");
                        }
                    }
                    case "initial-backoff" -> initial = duration(text, true);
                    case "max-backoff" -> max = duration(text, true);
                    case "multiplier" -> {
                        multiplier = Double.parseDouble(text);
                        if (!Double.isFinite(multiplier) || multiplier < 1.0) {
                            multiplier = 2.0;
                            throw new IllegalArgumentException("below 1.0");
                        }
                    }
                    case "safe-methods-idempotent" -> safe = bool(text);
                    default -> { }
                }
            } catch (IllegalArgumentException e) {
                invalid(problems, prefix + entry.getKey(), entry.getValue(), switch (entry.getKey()) {
                    case "max-attempts" -> "Set a whole number from 1 to 10.";
                    case "initial-backoff", "max-backoff" -> "Set a duration of zero or more, such as 200ms.";
                    case "multiplier" -> "Set a finite number of 1.0 or more.";
                    default -> "Set true or false.";
                });
            }
        }
        if (max == null) {
            max = initial.compareTo(FLOOR_MAX) > 0 ? initial : FLOOR_MAX;
        } else if (max.compareTo(initial) < 0) {
            invalid(problems, prefix + "max-backoff", values.get("max-backoff"), "Set a duration of at least "
                    + prefix + "initial-backoff.");
            max = initial;
        }
        return new Retry(attempts, initial, max, multiplier, safe);
    }

    private static Breaker parseBreaker(Map<String, String> values, List<Diagnostic> problems, String prefix) {
        boolean enabled = false;
        float threshold = 50f;
        int window = DEFAULT_WINDOW;
        Integer minimum = null;
        Duration wait = Duration.ofSeconds(60);
        for (Map.Entry<String, String> entry : values.entrySet()) {
            String text = entry.getValue().trim();
            try {
                switch (entry.getKey()) {
                    case "enabled" -> enabled = bool(text);
                    case "failure-rate-threshold" -> {
                        float parsed = Float.parseFloat(text);
                        if (!Float.isFinite(parsed) || parsed <= 0 || parsed > 100) {
                            throw new IllegalArgumentException("outside (0, 100]");
                        }
                        threshold = parsed;
                    }
                    case "sliding-window-size" -> {
                        int parsed = Integer.parseInt(text);
                        if (parsed < 1) {
                            throw new IllegalArgumentException("below 1");
                        }
                        window = parsed;
                    }
                    case "minimum-number-of-calls" -> {
                        int parsed = Integer.parseInt(text);
                        if (parsed < 1) {
                            throw new IllegalArgumentException("below 1");
                        }
                        minimum = parsed;
                    }
                    case "wait-duration-in-open-state" -> {
                        wait = duration(text, false);
                        if (wait.compareTo(Duration.ofMillis(1)) < 0) {
                            throw new IllegalArgumentException("below 1ms");
                        }
                    }
                    default -> { }
                }
            } catch (IllegalArgumentException e) {
                invalid(problems, prefix + entry.getKey(), entry.getValue(), switch (entry.getKey()) {
                    case "failure-rate-threshold" -> "Set a number above 0 and at most 100.";
                    case "sliding-window-size", "minimum-number-of-calls" -> "Set a whole number of 1 or more.";
                    case "wait-duration-in-open-state" -> "Set a duration of at least 1ms, such as 30s.";
                    default -> "Set true or false.";
                });
            }
        }
        if (minimum != null && minimum > window) {
            invalid(problems, prefix + "minimum-number-of-calls", values.get("minimum-number-of-calls"),
                    "Set at most " + prefix + "sliding-window-size (" + window + ").");
        }
        // an unset minimum is the default 10, or the whole window when that is smaller
        int effective = minimum == null ? Math.min(DEFAULT_MINIMUM, window) : Math.min(minimum, window);
        return new Breaker(enabled, threshold, window, effective, wait);
    }

    private static Duration duration(String text, boolean allowZero) {
        Duration parsed = DurationStyle.detectAndParse(text);
        if (parsed.isNegative() || !allowZero && parsed.isZero()) {
            throw new IllegalArgumentException("not positive");
        }
        return parsed;
    }

    private static boolean bool(String text) {
        if (text.equalsIgnoreCase("true")) {
            return true;
        }
        if (text.equalsIgnoreCase("false")) {
            return false;
        }
        throw new IllegalArgumentException("not a boolean");
    }

    private static void invalid(List<Diagnostic> problems, String key, String text, String fix) {
        if (problems != null) {
            problems.add(new Diagnostic(TopolithCode.TL0003, "invalid value '" + text + "' for " + key + ".", fix));
        }
    }
}
