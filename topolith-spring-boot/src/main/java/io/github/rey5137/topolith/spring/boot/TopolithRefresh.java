package io.github.rey5137.topolith.spring.boot;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import io.github.rey5137.topolith.core.Diagnostic;
import io.github.rey5137.topolith.core.Incubating;
import io.github.rey5137.topolith.core.Mode;
import io.github.rey5137.topolith.core.TopolithCode;
import io.github.rey5137.topolith.core.TopolithException;
import io.github.rey5137.topolith.core.Topology;
import io.github.rey5137.topolith.spring.boot.internal.ResilienceRefresh;
import io.github.rey5137.topolith.spring.boot.internal.ResilienceSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.BeanClassLoaderAware;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;

/**
 * What a configuration refresh means for Topolith (R-MODE-05, R-EXT-08, D-50). {@code topolith-spring-cloud} passes it
 * the changed property keys of an {@code EnvironmentChangeEvent}; it holds no Spring Cloud type, so the logic is
 * testable without one. A changed mode or a URL/service-id switch only logs TL0010 and marks restart pending: the
 * running topology never changes (INV-9). A changed URL, service id or timeout rebuilds that external module's client.
 * A loopback module's client settings are restart-only (D-77): a change logs TL0010 and keeps restart pending until
 * every such key again matches its running value. An external or loopback module's {@code auth.*} keys are
 * restart-only in the same way (R-EXT-13).
 */
@Incubating
public final class TopolithRefresh implements BeanClassLoaderAware {
    private static final Logger LOG = LoggerFactory.getLogger(TopolithRefresh.class);
    private static final String PREFIX = TopologyResolver.MODES + ".";

    private final Environment environment;
    private final Topology topology;
    private final TopolithRefreshStatus status;
    private final ObjectProvider<ExternalClientEndpoints> endpoints;
    private final ObjectProvider<ResilienceRefresh> resilience;
    private final Map<String, Boolean> runningBalanced = new HashMap<>();
    private final Map<String, Map<String, String>> runningLoopback = new HashMap<>();
    private final Map<String, Map<String, String>> runningAuth = new HashMap<>();
    private ClassLoader classLoader = Thread.currentThread().getContextClassLoader();

    TopolithRefresh(Environment environment, Topology topology, TopolithRefreshStatus status,
            ObjectProvider<ExternalClientEndpoints> endpoints, ObjectProvider<ResilienceRefresh> resilience) {
        this.resilience = resilience;
        this.environment = environment;
        this.topology = topology;
        this.status = status;
        this.endpoints = endpoints;
        topology.idsOf(Mode.EXTERNAL).forEach(id -> runningBalanced.put(id, balanced(id)));
        topology.idsOf(Mode.LOOPBACK).forEach(id -> runningLoopback.put(id, loopbackSettings(id)));
        // not ExternalHttpServicesRegistrar.clientModules: that class needs spring-web, which is optional (INV-7)
        for (Mode mode : List.of(Mode.EXTERNAL, Mode.LOOPBACK)) {
            topology.idsOf(mode).forEach(id -> runningAuth.put(id, authSettings(id)));
        }
    }

    @Override
    public void setBeanClassLoader(ClassLoader classLoader) {
        this.classLoader = classLoader;
    }

    /** Applies the changed property keys; keys outside {@code topolith.modules.<id>.} are ignored. */
    public synchronized void apply(Set<String> changedKeys) {
        Map<String, Set<String>> changed = new TreeMap<>();
        for (String key : changedKeys) {
            int dot = key.startsWith(PREFIX) ? key.indexOf('.', PREFIX.length()) : -1;
            if (dot > 0) {
                changed.computeIfAbsent(canonical(key.substring(PREFIX.length(), dot)), id -> new HashSet<>())
                        .add(key.substring(dot + 1).replace("-", "").toLowerCase(Locale.ROOT));
            }
        }
        boolean standalone = changedKeys.contains(TopologyResolver.STANDALONE);
        if (changed.isEmpty() && !standalone) {
            return;
        }
        Map<String, String> modes = requestedModes();
        Set<String> wiring = new TreeSet<>();
        for (String id : topology.idsOf(Mode.EXTERNAL)) {
            if (!modes.containsKey(id) && balanced(id) != runningBalanced.get(id)) {
                wiring.add(id);
            }
        }
        for (String id : topology.idsOf(Mode.LOOPBACK)) {
            if (!modes.containsKey(id) && !loopbackSettings(id).equals(runningLoopback.get(id))) {
                wiring.add(id);
            }
        }
        // a module's outgoing-auth hook is restart-only (R-EXT-13): pending until each auth.* key is back
        Set<String> auth = new TreeSet<>();
        runningAuth.forEach((id, running) -> {
            if (!modes.containsKey(id) && !authSettings(id).equals(running)) {
                auth.add(id);
            }
        });
        // turning retry or the breaker on is restart-only while Spring Cloud's global breaker is applied (D-106)
        ResilienceRefresh hook = resilience.getIfAvailable();
        Set<String> global = new TreeSet<>();
        if (hook != null && hook.globalBreaker()) {
            Binder binder = Binder.get(environment);
            for (String id : topology.idsOf(Mode.EXTERNAL)) {
                if (!modes.containsKey(id) && (ResilienceSettings.retry(binder, id).active()
                        || ResilienceSettings.breaker(binder, id).enabled())) {
                    global.add(id);
                }
            }
        }
        Set<String> pending = new TreeSet<>(wiring);
        pending.addAll(auth);
        pending.addAll(global);
        status.update(modes, pending);
        Map<String, Map<String, String>> current = new HashMap<>();
        for (String key : new TreeSet<>(changedKeys)) {
            String id = canonical(moduleOf(key));
            if (id != null && wiring.contains(id) && runningLoopback.containsKey(id) && loopbackKey(key)
                    && differs(key, id, current.computeIfAbsent(id, this::loopbackSettings))) {
                LOG.warn(new Diagnostic(TopolithCode.TL0010, "loopback module '" + id + "' keeps its client "
                        + "settings: the refreshed " + key + " applies only after a restart.",
                        "Restart the application to apply it, or restore the running value"
                        + (urlKey(key) ? "; remote.url and remote.service-id are not allowed on a loopback "
                        + "module (the next start fails with TL0003)." : ".")).toString());
            }
        }
        for (String key : new TreeSet<>(changedKeys)) {
            String id = canonical(moduleOf(key));
            if (id != null && auth.contains(id) && relaxed(restOf(key)).startsWith("auth.")
                    && differs(key, authSettings(id), runningAuth.get(id))) {
                LOG.warn(new Diagnostic(TopolithCode.TL0010, "module '" + id + "' keeps its outgoing-auth hook: "
                        + "the refreshed " + key + " applies only after a restart.",
                        "Restart the application to apply it, or restore the running value.").toString());
            }
        }
        for (String key : new TreeSet<>(changedKeys)) {
            String id = canonical(moduleOf(key));
            String rest = id == null ? "" : relaxed(restOf(key));
            if (id != null && global.contains(id)
                    && (rest.startsWith("remote.retry.") || rest.startsWith("remote.circuitbreaker."))) {
                LOG.warn(new Diagnostic(TopolithCode.TL0010, "external module '" + id + "' keeps retry and its "
                        + "circuit breaker off: the refreshed " + key + " applies only after a restart, because "
                        + "Spring Cloud's global HTTP-service circuit breaker is applied to its group.",
                        "Restart the application to apply it, or restore the running value. Set "
                        + "spring.cloud.circuitbreaker.http-services.enabled=false up front to change retry and the "
                        + "breaker without a restart.").toString());
            }
        }
        modes.forEach((id, requested) -> {
            if (standalone || changed.getOrDefault(id, Set.of()).contains("mode")) {
                LOG.warn(new Diagnostic(TopolithCode.TL0010, "module '" + id + "' runs in mode "
                        + topology.mode(id).id() + " and the refreshed configuration requests '" + requested + "'.",
                        "Restart the application to apply the requested mode; a mode that is not valid fails "
                        + "startup (TL0003).").toString());
            }
        });
        ExternalClientEndpoints clients = endpoints.getIfAvailable();
        changed.forEach((id, rests) -> {
            boolean target = rests.contains("remote.url") || rests.contains("remote.serviceid");
            if (wiring.contains(id) && target && runningBalanced.containsKey(id)) {
                LOG.warn(new Diagnostic(TopolithCode.TL0010, "external module '" + id + "' was switched between "
                        + "remote.url and remote.service-id; the running client keeps its wiring.",
                        "Restart the application to apply it.").toString());
            }
            boolean endpoint = target || rests.contains("remote.connecttimeout")
                    || rests.contains("remote.readtimeout");
            if (clients != null && endpoint && clients.manages(id) && !modes.containsKey(id)
                    && !wiring.contains(id)) {
                clients.rebuild(id);
            }
            boolean retry = rests.stream().anyMatch(rest -> rest.startsWith("remote.retry."));
            boolean breaker = rests.stream().anyMatch(rest -> rest.startsWith("remote.circuitbreaker."));
            if ((retry || breaker) && topology.idsOf(Mode.EXTERNAL).contains(id) && !modes.containsKey(id)
                    && !global.contains(id)) {
                refreshResilience(id, hook, retry, breaker);
            }
        });
    }

    /**
     * Validates module {@code id}'s refreshed retry and circuit-breaker settings and hands the changed group to the
     * resilience hook, which builds before it swaps, so one failure changes neither. Any invalid setting or failure
     * keeps the running policy and breaker, is logged as TL0011 and recorded for the actuator (D-14, D-104). Its
     * failure is kept apart from the client refresh's, so a success of one never clears the other's.
     */
    private void refreshResilience(String id, ResilienceRefresh hook, boolean retry, boolean breaker) {
        try {
            List<Diagnostic> problems = new ArrayList<>();
            Binder binder = Binder.get(environment);
            ResilienceSettings.checkModule(environment, binder, topology, classLoader, id, problems);
            if (!problems.isEmpty()) {
                throw new TopolithException(problems);
            }
            if (hook != null) {
                hook.apply(id, retry ? ResilienceSettings.retry(binder, id) : null,
                        breaker ? ResilienceSettings.breaker(binder, id) : null);
            }
        } catch (RuntimeException | LinkageError e) {
            String reason = e.getMessage() == null ? e.getClass().getSimpleName()
                    : ExternalClientEndpoints.stripUserInfo(e.getMessage());
            status.fail(id, TopolithRefreshStatus.RESILIENCE, reason);
            LOG.warn(new Diagnostic(TopolithCode.TL0011, "the refreshed retry and circuit-breaker settings of "
                    + "external module '" + id + "' were not applied: " + reason, "Fix the topolith.modules." + id
                    + ".remote.retry.* and remote.circuit-breaker.* settings; the running policy and breaker stay "
                    + "in use.").toString());
            return;
        }
        status.succeed(id, TopolithRefreshStatus.RESILIENCE);
    }

    private String moduleOf(String key) {
        int dot = key.startsWith(PREFIX) ? key.indexOf('.', PREFIX.length()) : -1;
        return dot > 0 ? key.substring(PREFIX.length(), dot) : null;
    }

    /** The topology id a written module id names, dashes or not (an environment variable has none), else null. */
    private String canonical(String written) {
        if (written == null) {
            return null;
        }
        for (String known : topology.known()) {
            if (AuthSpellings.of(known, topology.known()).contains(written)) {
                return known;
            }
        }
        return written;
    }

    private static String restOf(String key) {
        return key.substring(key.indexOf('.', PREFIX.length()) + 1);
    }

    /** Whether {@code key} is a loopback module's {@code remote.url} or {@code remote.service-id}. */
    private static boolean urlKey(String key) {
        String rest = relaxed(restOf(key));
        return rest.equals("remote.url") || rest.equals("remote.serviceid");
    }

    /** Whether the refreshed {@code key} of loopback module {@code id} differs from its running value (D-77). */
    private boolean differs(String key, String id, Map<String, String> current) {
        return differs(key, current, runningLoopback.get(id));
    }

    private static boolean differs(String key, Map<String, String> current, Map<String, String> running) {
        String wanted = relaxed(restOf(key));
        return !Objects.equals(valueOf(current, wanted), valueOf(running, wanted));
    }

    /** Module {@code id}'s {@code auth.*} settings, by key, as its outgoing-auth hook was built from. */
    private Map<String, String> authSettings(String id) {
        Map<String, String> settings = new TreeMap<>();
        if (environment instanceof ConfigurableEnvironment configurable) {
            // the dash-less spelling is what an environment variable binds to; the dashed one wins (D-50)
            List<String> names = AuthSpellings.of(id, topology.known()).reversed();
            for (String name : names) {
                try {
                    Binder.get(configurable).bind(PREFIX + name + ".auth", Bindable.mapOf(String.class, String.class))
                            .ifBound(values -> values.forEach((key, value) -> settings.put("auth." + key, value)));
                } catch (BindException e) {
                    settings.put("auth", "invalid: " + e.getMessage());
                }
            }
        }
        return settings;
    }

    /** A setting's value by its relaxed name, so {@code readTimeout} and {@code read-timeout} name one key. */
    private static String valueOf(Map<String, String> settings, String relaxedKey) {
        for (Map.Entry<String, String> entry : settings.entrySet()) {
            if (relaxed(entry.getKey()).equals(relaxedKey)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static String relaxed(String key) {
        return key.replace("-", "").toLowerCase(Locale.ROOT);
    }

    /** Whether {@code key} is one of a loopback module's restart-only client settings (D-77). */
    private static boolean loopbackKey(String key) {
        String rest = relaxed(restOf(key));
        return rest.equals("remote.connecttimeout") || rest.equals("remote.readtimeout")
                || rest.equals("remote.url") || rest.equals("remote.serviceid")
                || rest.startsWith("remote.httpinterface.") || rest.startsWith("loopback.")
                || rest.startsWith("remote.retry.") || rest.startsWith("remote.circuitbreaker.");
    }

    /** Loopback module {@code id}'s client settings, by key: its timeouts, HTTP interfaces and {@code loopback.*}. */
    private Map<String, String> loopbackSettings(String id) {
        Map<String, String> settings = new TreeMap<>();
        String prefix = PREFIX + id + ".";
        for (String key : List.of("remote.connect-timeout", "remote.read-timeout")) {
            String value = environment.getProperty(prefix + key);
            if (value != null) {
                settings.put(key, value);
            }
        }
        // a loopback module sets neither (TL0003): a refreshed value logs, and the next start would fail
        for (String key : List.of("remote.url", "remote.service-id")) {
            String value = environment.getProperty(prefix + key);
            if (value != null && !value.isBlank()) {
                settings.put(key, value);
            }
        }
        if (environment instanceof ConfigurableEnvironment configurable) {
            Binder binder = Binder.get(configurable);
            for (String group : List.of("remote.http-interface", "loopback", "remote.retry",
                    "remote.circuit-breaker")) {
                try {
                    binder.bind(prefix + group, Bindable.mapOf(String.class, String.class))
                            .ifBound(values -> values.forEach((key, value) -> settings.put(group + "." + key, value)));
                } catch (BindException e) {
                    settings.put(group, "invalid: " + e.getMessage());
                }
            }
        }
        return settings;
    }

    /**
     * Modules whose requested mode differs from the running one, with the requested mode (D-13). When the refreshed
     * topology does not resolve, the raw requested value is named instead: an invalid mode is a pending restart.
     */
    private Map<String, String> requestedModes() {
        Map<String, String> differing = new TreeMap<>();
        try {
            Topology requested = TopologyResolver.requested(environment, classLoader);
            for (String id : topology.known()) {
                if (requested.known().contains(id) && requested.mode(id) != topology.mode(id)) {
                    differing.put(id, requested.mode(id).id());
                }
            }
        } catch (RuntimeException e) {
            // the refreshed topology cannot start: every module whose declared mode is not the running one is pending
            differing.clear();
            for (String id : topology.known()) {
                String raw = environment.getProperty(PREFIX + id + ".mode");
                if (raw != null && !raw.trim().equalsIgnoreCase(topology.mode(id).id())) {
                    differing.put(id, raw.trim());
                }
            }
        }
        return differing;
    }

    /**
     * True when the module's base URL goes through the load balancer: a service id, or a URL with the {@code lb://}
     * scheme (a URL wins when both are set, as in the mapped properties). The startup answer fixes the wiring (D-50).
     */
    private boolean balanced(String id) {
        String prefix = PREFIX + id + ".remote.";
        String url = environment.getProperty(prefix + "url");
        String service = environment.getProperty(prefix + "service-id");
        if (url != null && !url.isBlank()) {
            return url.trim().toLowerCase(Locale.ROOT).startsWith(ExternalClientProperties.LB_SCHEME);
        }
        return service != null && !service.isBlank();
    }
}
