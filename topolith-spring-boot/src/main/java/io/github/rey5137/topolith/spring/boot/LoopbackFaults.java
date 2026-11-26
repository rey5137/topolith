package io.github.rey5137.topolith.spring.boot;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;

import io.github.rey5137.topolith.core.Diagnostic;
import io.github.rey5137.topolith.core.Mode;
import io.github.rey5137.topolith.core.TopolithCode;
import io.github.rey5137.topolith.core.Topology;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.convert.DurationStyle;

/**
 * A loopback module's latency and fault settings, {@code topolith.modules.<id>.loopback.*} (R-EXT-10, D-78): their
 * validation (TL0003), their reading, and the module's seeded fault sequence.
 */
final class LoopbackFaults {
    /** How a chosen call fails. */
    enum Kind {
        CONNECTION_FAILURE("connection-failure"), UNAVAILABLE("unavailable"), TIMEOUT("timeout");

        private final String id;

        Kind(String id) {
            this.id = id;
        }

        static Kind of(String value) {
            for (Kind kind : values()) {
                if (kind.id.equals(value.trim().toLowerCase(Locale.ROOT))) {
                    return kind;
                }
            }
            throw new IllegalArgumentException("unknown fault kind '" + value + "'");
        }
    }

    record Settings(Duration latency, double rate, Kind kind, long seed) {
        static final Settings NONE = new Settings(Duration.ZERO, 0, Kind.CONNECTION_FAILURE, 0);
    }

    /** The read timeout of a group that sets none, parsed once from the shared default. */
    static final Duration DEFAULT_READ_TIMEOUT = DurationStyle.detectAndParse(
            ExternalClientProperties.READ_TIMEOUT_DEFAULT);
    private static final String LATENCY = "latency";
    private static final String RATE = "fault.rate";
    private static final String KIND = "fault.kind";
    private static final String SEED = "fault.seed";
    private static final Set<String> KEYS = Set.of(LATENCY, RATE, KIND, SEED);

    private final Settings settings;
    private Random random;

    LoopbackFaults(Settings settings) {
        this.settings = settings;
        this.random = new Random(settings.seed());
    }

    Settings settings() {
        return settings;
    }

    /** Whether the next call fails: exact at rate 0 and 1, else the next draw of the seeded sequence. */
    synchronized boolean next() {
        if (settings.rate() <= 0) {
            return false;
        }
        return settings.rate() >= 1 || random.nextDouble() < settings.rate();
    }

    synchronized void restart() {
        random = new Random(settings.seed());
    }

    /** Module {@code id}'s settings; defaults for what is unset. The values are those {@link #check} accepted. */
    static Settings read(Binder binder, String id) {
        Map<String, String> values = new TreeMap<>();
        // an environment variable cannot carry a dash: ORDERSERVICE binds as "orderservice" (D-50 known limit)
        for (String name : List.of(id, id.replace("-", ""))) {
            String prefix = TopologyResolver.MODES + "." + name + ".loopback.";
            for (String key : KEYS) {
                binder.bind(prefix + key, String.class).ifBound(value -> values.putIfAbsent(key, value));
            }
        }
        return parse(values, null, id);
    }

    /**
     * TL0003 for every {@code loopback.*} key of a module that is not loopback, every unknown key and every invalid
     * value.
     */
    static void check(Binder binder, Topology topology, List<Diagnostic> problems) {
        Map<String, Object> modules;
        try {
            modules = binder.bind(TopologyResolver.MODES, Bindable.mapOf(String.class, Object.class))
                    .orElse(Map.of());
        } catch (BindException e) {
            return;
        }
        Map<String, String> compact = new TreeMap<>();
        topology.known().forEach(known -> compact.put(known.replace("-", ""), known));
        new TreeMap<>(modules).forEach((written, value) -> {
            if (!(value instanceof Map<?, ?> settings) || !settings.containsKey("loopback")) {
                return;
            }
            String id = topology.known().contains(written) ? written
                    : compact.getOrDefault(written.replace("-", ""), written);
            String prefix = TopologyResolver.MODES + "." + written + ".loopback";
            Map<String, String> flat = new TreeMap<>();
            flatten(prefix, settings.get("loopback"), flat);
            if (!topology.known().contains(id) || topology.mode(id) != Mode.LOOPBACK) {
                String what = topology.known().contains(id) ? "has mode " + topology.mode(id).id() : "is unknown";
                flat.keySet().forEach(key -> problems.add(new Diagnostic(TopolithCode.TL0003, "module '" + id + "' "
                        + what + ", so " + key + " does not apply.", "Remove " + key
                        + ", or set the module's mode to loopback.")));
                return;
            }
            Map<String, String> known = new TreeMap<>();
            flat.forEach((key, text) -> {
                String name = key.length() > prefix.length() ? key.substring(prefix.length() + 1) : "";
                if (KEYS.contains(name)) {
                    known.put(name, text);
                } else {
                    problems.add(new Diagnostic(TopolithCode.TL0003, "unknown setting " + key + ".",
                            "Use " + prefix + ".latency, .fault.rate, .fault.kind or .fault.seed."));
                }
            });
            parse(known, problems, written);
        });
    }

    private static void flatten(String prefix, Object value, Map<String, String> into) {
        if (value instanceof Map<?, ?> map) {
            map.forEach((key, inner) -> flatten(prefix + "." + key, inner, into));
        } else {
            into.put(prefix, String.valueOf(value));
        }
    }

    /** The settings of {@code values}; with {@code problems}, each invalid value is reported and defaulted. */
    private static Settings parse(Map<String, String> values, List<Diagnostic> problems, String id) {
        String prefix = TopologyResolver.MODES + "." + id + ".loopback.";
        Duration latency = Duration.ZERO;
        double rate = 0;
        Kind kind = Kind.CONNECTION_FAILURE;
        long seed = 0;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            String text = entry.getValue();
            try {
                switch (entry.getKey()) {
                    case LATENCY -> {
                        latency = DurationStyle.detectAndParse(text.trim());
                        if (latency.isNegative()) {
                            throw new IllegalArgumentException("negative");
                        }
                    }
                    case RATE -> {
                        BigDecimal decimal = new BigDecimal(text.trim());
                        if (decimal.signum() < 0 || decimal.compareTo(BigDecimal.ONE) > 0) {
                            throw new IllegalArgumentException("outside [0, 1]");
                        }
                        rate = decimal.doubleValue();
                    }
                    case KIND -> kind = Kind.of(text);
                    case SEED -> seed = Long.parseLong(text.trim());
                    default -> { }
                }
            } catch (IllegalArgumentException e) {
                if (problems != null) {
                    problems.add(new Diagnostic(TopolithCode.TL0003, "invalid value '" + text + "' for " + prefix
                            + entry.getKey() + ".", expected(entry.getKey())));
                }
            }
        }
        return new Settings(latency, rate, kind, seed);
    }

    private static String expected(String key) {
        return switch (key) {
            case LATENCY -> "Set a duration of zero or more, such as 200ms.";
            case RATE -> "Set a decimal from 0 to 1.";
            case KIND -> "Set connection-failure, unavailable or timeout.";
            default -> "Set a whole number.";
        };
    }
}
