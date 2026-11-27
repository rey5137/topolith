package io.github.rey5137.topolith.spring.boot;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import io.github.rey5137.topolith.core.Diagnostic;
import io.github.rey5137.topolith.core.Mode;
import io.github.rey5137.topolith.core.TopolithCode;
import io.github.rey5137.topolith.core.TopolithException;
import io.github.rey5137.topolith.core.Topology;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.util.ClassUtils;
import org.springframework.web.client.support.RestClientHttpServiceGroupConfigurer;

/**
 * Each external and loopback module's outgoing-auth hook (R-EXT-13): a host's {@code ModuleClients.authenticate}
 * interceptor, or the built-in one {@code topolith.modules.<id>.auth.type} names. Holds no Spring Security type, so
 * a host without it still starts (INV-7); the built-in hooks load their Spring Security classes only when present.
 */
final class OutgoingAuth {
    static final String TYPE = "type";
    static final String REGISTRATION = "registration-id";
    static final String RELAY = "relay";
    static final String CLIENT_CREDENTIALS = "client-credentials";
    static final String NONE = "none";
    private static final Set<String> TYPES = Set.of(RELAY, CLIENT_CREDENTIALS, NONE);
    private static final String TOKEN_CLASS =
            "org.springframework.security.oauth2.server.resource.authentication.AbstractOAuth2TokenAuthenticationToken";
    private static final String CLIENT_CLASS =
            "org.springframework.security.oauth2.client.web.client.OAuth2ClientHttpRequestInterceptor";

    /**
     * Each module's request factory for its client-credentials token requests: the connect and read timeouts its
     * client currently uses, and nothing else of its settings (D-96). An external module's refresh replaces it with
     * the call's own (R-EXT-08); a loopback module's stays as it started (D-77).
     */
    static final class TokenRequests {
        private final Function<String, ClientHttpRequestFactory> initial;
        private final Map<String, AtomicReference<ClientHttpRequestFactory>> current = new ConcurrentHashMap<>();

        TokenRequests(Function<String, ClientHttpRequestFactory> initial) {
            this.initial = initial;
        }

        /** A factory that always delegates to module {@code id}'s current one. */
        ClientHttpRequestFactory forModule(String id) {
            AtomicReference<ClientHttpRequestFactory> factory = current.computeIfAbsent(id,
                    key -> new AtomicReference<>(initial.apply(key)));
            return (uri, method) -> factory.get().createRequest(uri, method);
        }

        /** Whether module {@code id} has a token-request factory to {@link #replace}. */
        boolean manages(String id) {
            return current.containsKey(id);
        }

        void replace(String id, ClientHttpRequestFactory factory) {
            AtomicReference<ClientHttpRequestFactory> reference = current.get(id);
            if (reference != null) {
                reference.set(factory);
            }
        }
    }

    /** A module's {@code auth.*} values as {@link #check} accepted them; {@code type} is null when unset. */
    record Settings(String type, String registrationId) {
    }

    private final Topology topology;
    private final Binder binder;
    private final ListableBeanFactory beans;
    private final ClassLoader classLoader;
    private final TokenRequests tokenRequests;
    private Map<String, ClientHttpRequestInterceptor> hooks;
    private Set<String> relays;

    OutgoingAuth(Topology topology, Binder binder, ListableBeanFactory beans, ClassLoader classLoader,
            TokenRequests tokenRequests) {
        this.topology = topology;
        this.binder = binder;
        this.beans = beans;
        this.classLoader = classLoader;
        this.tokenRequests = tokenRequests;
    }

    /**
     * The hook of every external and loopback module that has one, resolved once all hook beans exist: TL0036 for two
     * hooks, TL0037 for a client-credentials hook that cannot be built.
     */
    synchronized Map<String, ClientHttpRequestInterceptor> hooks() {
        if (hooks == null) {
            resolve();
        }
        return hooks;
    }

    /** Whether module {@code id}'s hook relays the calling thread's bearer token, read on the calling thread. */
    boolean relays(String id) {
        hooks();
        return relays.contains(id);
    }

    private void resolve() {
        Map<String, List<ModuleAuthConfigurer>> hosts = new TreeMap<>();
        for (RestClientHttpServiceGroupConfigurer configurer
                : beans.getBeansOfType(RestClientHttpServiceGroupConfigurer.class).values()) {
            if (configurer instanceof ModuleAuthConfigurer host) {
                hosts.computeIfAbsent(host.moduleId(), key -> new ArrayList<>()).add(host);
            }
        }
        List<Diagnostic> problems = new ArrayList<>();
        Map<String, ClientHttpRequestInterceptor> found = new TreeMap<>();
        Set<String> relaying = new TreeSet<>();
        boolean tokens = ClassUtils.isPresent(TOKEN_CLASS, classLoader);
        for (String id : ExternalHttpServicesRegistrar.clientModules(topology)) {
            Settings settings = read(binder, id);
            List<ModuleAuthConfigurer> own = hosts.getOrDefault(id, List.of());
            String key = TopologyResolver.MODES + "." + id + ".auth." + TYPE;
            if (own.size() > 1) {
                problems.add(new Diagnostic(TopolithCode.TL0036, "module '" + id + "' has " + own.size()
                        + " ModuleClients.authenticate hooks.", "Declare one hook per module."));
            } else if (own.size() == 1 && settings.type() != null) {
                problems.add(new Diagnostic(TopolithCode.TL0036, "module '" + id + "' has a "
                        + "ModuleClients.authenticate hook and " + key + "=" + settings.type() + ".",
                        "Remove " + key + " or the hook."));
            } else if (own.size() == 1) {
                found.put(id, own.get(0).interceptor());
            } else if (settings.type() == null || settings.type().equals(RELAY)) {
                // without the resource-server classes there is no token to relay (D-93)
                if (tokens) {
                    found.put(id, new RelayHook());
                    relaying.add(id);
                }
            } else if (settings.type().equals(CLIENT_CREDENTIALS)) {
                ClientHttpRequestInterceptor hook = clientCredentials(id, settings, problems);
                if (hook != null) {
                    found.put(id, hook);
                }
            }
        }
        if (!problems.isEmpty()) {
            throw new TopolithException(problems);
        }
        hooks = Map.copyOf(found);
        relays = Set.copyOf(relaying);
    }

    private ClientHttpRequestInterceptor clientCredentials(String id, Settings settings, List<Diagnostic> problems) {
        String prefix = TopologyResolver.MODES + "." + id + ".auth.";
        if (!ClassUtils.isPresent(CLIENT_CLASS, classLoader)) {
            problems.add(ClientCredentialsHook.problem(id, "Spring Security's OAuth2 client classes are missing",
                    "Add spring-security-oauth2-client, or choose another " + prefix + TYPE + "."));
            return null;
        }
        if (settings.registrationId() == null) {
            problems.add(ClientCredentialsHook.problem(id, prefix + REGISTRATION + " is not set",
                    "Set " + prefix + REGISTRATION + " to a client registration id."));
            return null;
        }
        return ClientCredentialsHook.create(id, settings.registrationId(), beans,
                tokenRequests == null ? null : tokenRequests.forModule(id), problems);
    }

    /** Module {@code id}'s {@code auth.*} settings; the values are those {@link #check} accepted. */
    static Settings read(Binder binder, String id) {
        String type = null;
        String registration = null;
        // an environment variable cannot carry a dash: ORDERSERVICE binds as "orderservice" (D-50 known limit)
        for (String name : List.of(id, id.replace("-", ""))) {
            String prefix = TopologyResolver.MODES + "." + name + ".auth.";
            if (type == null) {
                type = binder.bind(prefix + TYPE, String.class).map(OutgoingAuth::normalized).orElse(null);
            }
            if (registration == null) {
                registration = binder.bind(prefix + REGISTRATION, String.class).map(String::trim)
                        .map(value -> value.isEmpty() ? null : value).orElse(null);
            }
        }
        return new Settings(type, registration);
    }

    private static String normalized(String type) {
        return type.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * TL0003 for every {@code auth.*} key of a module that is neither external nor loopback, every unknown key and
     * {@code auth.type} value, and an {@code auth.registration-id} without {@code auth.type=client-credentials}.
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
            if (!(value instanceof Map<?, ?> settings) || !settings.containsKey("auth")) {
                return;
            }
            String id = topology.known().contains(written) ? written
                    : compact.getOrDefault(written.replace("-", ""), written);
            String prefix = TopologyResolver.MODES + "." + written + ".auth";
            Map<String, String> flat = new TreeMap<>();
            LoopbackFaults.flatten(prefix, settings.get("auth"), flat);
            Mode mode = topology.known().contains(id) ? topology.mode(id) : null;
            if (mode != Mode.EXTERNAL && mode != Mode.LOOPBACK) {
                String what = mode != null ? "has mode " + mode.id() : "is unknown";
                flat.keySet().forEach(key -> problems.add(new Diagnostic(TopolithCode.TL0003, "module '" + id + "' "
                        + what + ", so " + key + " does not apply.", "Remove " + key
                        + ", or set the module's mode to external or loopback.")));
                return;
            }
            String type = null;
            String registration = null;
            for (Map.Entry<String, String> entry : flat.entrySet()) {
                String name = entry.getKey().length() > prefix.length()
                        ? entry.getKey().substring(prefix.length() + 1) : "";
                switch (name.replace("-", "").toLowerCase(Locale.ROOT)) {
                    case "type" -> type = entry.getKey();
                    case "registrationid" -> registration = entry.getKey();
                    default -> problems.add(new Diagnostic(TopolithCode.TL0003, "unknown setting "
                            + entry.getKey() + ".", "Use " + prefix + "." + TYPE + " or " + prefix + "."
                            + REGISTRATION + "."));
                }
            }
            String chosen = type == null ? null : normalized(flat.get(type));
            if (chosen != null && !TYPES.contains(chosen)) {
                problems.add(new Diagnostic(TopolithCode.TL0003, type + " has the unknown value '" + flat.get(type)
                        + "'.", "Use relay, client-credentials or none."));
            } else if (registration != null && !CLIENT_CREDENTIALS.equals(chosen)) {
                problems.add(new Diagnostic(TopolithCode.TL0003, registration + " applies only with " + prefix
                        + "." + TYPE + "=client-credentials.", "Remove " + registration + ", or set " + prefix
                        + "." + TYPE + "=client-credentials."));
            }
        });
    }
}
