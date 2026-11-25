package io.github.rey5137.topolith.spring.boot;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import io.github.rey5137.topolith.core.Diagnostic;
import io.github.rey5137.topolith.core.Incubating;
import io.github.rey5137.topolith.core.Mode;
import io.github.rey5137.topolith.core.TopolithCode;
import io.github.rey5137.topolith.core.Topology;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.BeanClassLoaderAware;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;

/**
 * What a configuration refresh means for Topolith (R-MODE-05, R-EXT-08, D-50). {@code topolith-spring-cloud} passes it
 * the changed property keys of an {@code EnvironmentChangeEvent}; it holds no Spring Cloud type, so the logic is
 * testable without one. A changed mode or a URL/service-id switch only logs TL0010 and marks restart pending: the
 * running topology never changes (INV-9). A changed URL, service id or timeout rebuilds that external module's client.
 */
@Incubating
public final class TopolithRefresh implements BeanClassLoaderAware {
    private static final Logger LOG = LoggerFactory.getLogger(TopolithRefresh.class);
    private static final String PREFIX = TopologyResolver.MODES + ".";

    private final Environment environment;
    private final Topology topology;
    private final TopolithRefreshStatus status;
    private final ObjectProvider<ExternalClientEndpoints> endpoints;
    private final Map<String, Boolean> runningBalanced = new HashMap<>();
    private ClassLoader classLoader = Thread.currentThread().getContextClassLoader();

    TopolithRefresh(Environment environment, Topology topology, TopolithRefreshStatus status,
            ObjectProvider<ExternalClientEndpoints> endpoints) {
        this.environment = environment;
        this.topology = topology;
        this.status = status;
        this.endpoints = endpoints;
        topology.idsOf(Mode.EXTERNAL).forEach(id -> runningBalanced.put(id, balanced(id)));
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
                changed.computeIfAbsent(key.substring(PREFIX.length(), dot), id -> new HashSet<>())
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
        status.update(modes, wiring);
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
            if (wiring.contains(id) && target) {
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
        });
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
