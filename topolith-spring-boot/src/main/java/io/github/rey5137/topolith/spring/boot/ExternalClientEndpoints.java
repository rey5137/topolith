package io.github.rey5137.topolith.spring.boot;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import io.github.rey5137.topolith.core.Diagnostic;
import io.github.rey5137.topolith.core.TopolithCode;
import io.github.rey5137.topolith.core.TopolithException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.DefaultUriBuilderFactory;
import org.springframework.web.util.UriBuilder;
import org.springframework.web.util.UriBuilderFactory;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * The swappable parts of each external module's client (D-50). The group's one {@link RestClient} and its proxies
 * never change; Topolith installs a URI factory and a request factory that delegate to a reference, and a refresh
 * validates the new settings, builds new delegates and only then sets the references. A request that was already
 * created finishes on the old request factory, which is left open: it may still serve calls in flight.
 */
final class ExternalClientEndpoints {
    private static final Logger LOG = LoggerFactory.getLogger(ExternalClientEndpoints.class);
    /** Userinfo up to the last {@code @} of the authority, with or without a scheme. */
    private static final Pattern USER_INFO = Pattern.compile(
            "(?i)(?:\\b([a-z][a-z0-9+.-]*://)[^/\\s'\"]*@|(?<![\\w/@.:-])[\\w.~%!$&*+;=-]+:[^/\\s'\"]*@(?=[\\w.-]+(?::\\d+)?(?:[/\\s'\"]|$)))");

    private record Client(AtomicReference<UriBuilderFactory> uri, AtomicReference<ClientHttpRequestFactory> request,
            boolean balanced, HttpClientSettings settings, ClientHttpRequestFactoryBuilder<?> factories) {
    }

    private final ConfigurableEnvironment environment;
    private final BeanFactory beanFactory;
    private final TopolithRefreshStatus status;
    private final Map<String, Client> clients = new ConcurrentHashMap<>();

    ExternalClientEndpoints(ConfigurableEnvironment environment, BeanFactory beanFactory,
            TopolithRefreshStatus status) {
        this.environment = environment;
        this.beanFactory = beanFactory;
        this.status = status;
    }

    /** Installs the swappable delegates in module {@code id}'s group builder, from its configured base URL. */
    void install(String id, RestClient.Builder builder, HttpClientSettings settings,
            ClientHttpRequestFactoryBuilder<?> factories) {
        String base = environment.getProperty(baseUrlKey(id));
        if (base == null || base.isBlank()) {
            return;
        }
        Client client = new Client(new AtomicReference<>(new DefaultUriBuilderFactory(effectiveBase(base))),
                new AtomicReference<>(factories.build(settings)), balanced(base), settings, factories);
        clients.put(id, client);
        builder.uriBuilderFactory(new SwappableUriBuilderFactory(client.uri()))
                .requestFactory(new SwappableRequestFactory(client.request()));
    }

    boolean manages(String id) {
        return clients.containsKey(id);
    }

    /**
     * Rebuilds module {@code id}'s client from the current environment. Any invalid setting or failure keeps the old
     * client, is logged as TL0011 and recorded for the actuator (D-14). Returns whether the client was swapped.
     */
    synchronized boolean rebuild(String id) {
        Client client = clients.get(id);
        if (client == null) {
            return false;
        }
        var sources = environment.getPropertySources();
        PropertySource<?> old = sources.get(ExternalClientProperties.SOURCE_NAME);
        try {
            // our mapped keys would look like a Boot value to the conflict checks: validate without them
            sources.replace(ExternalClientProperties.SOURCE_NAME,
                    new MapPropertySource(ExternalClientProperties.SOURCE_NAME, Map.of()));
            Binder binder = Binder.get(environment);
            List<Diagnostic> problems = new ArrayList<>();
            TopologyResolver.checkRemote(environment, binder, id, problems);
            if (!problems.isEmpty()) {
                throw new TopolithException(problems);
            }
            Map<String, Object> mapped = new LinkedHashMap<>();
            if (old != null && old.getSource() instanceof Map<?, ?> previous) {
                previous.forEach((key, value) -> {
                    if (!key.toString().startsWith(ExternalClientProperties.groupPrefix(id))) {
                        mapped.put(key.toString(), value);
                    }
                });
            }
            mapped.putAll(ExternalClientProperties.properties(environment, List.of(id)));
            sources.replace(ExternalClientProperties.SOURCE_NAME,
                    new MapPropertySource(ExternalClientProperties.SOURCE_NAME, Collections.unmodifiableMap(mapped)));
            String base = effectiveBase(environment.getProperty(baseUrlKey(id)));
            URI uri = URI.create(base);
            boolean balanced = balanced(environment.getProperty(baseUrlKey(id)));
            if (balanced != client.balanced()) {
                throw new IllegalStateException("the module switched between a load-balanced and a direct URL");
            }
            if (!balanced && (uri.getHost() == null || !Set.of("http", "https").contains(uri.getScheme()))) {
                throw new IllegalArgumentException("'" + base + "' is not an http(s) URL");
            }
            String prefix = ExternalClientProperties.groupPrefix(id);
            Duration connect = timeout(binder, prefix, "connect-timeout",
                    ExternalClientProperties.CONNECT_TIMEOUT_DEFAULT);
            Duration read = timeout(binder, prefix, "read-timeout", ExternalClientProperties.READ_TIMEOUT_DEFAULT);
            ClientHttpRequestFactory factory = client.factories().build(client.settings().withTimeouts(connect, read));
            client.uri().set(new DefaultUriBuilderFactory(base));
            client.request().set(factory);
        } catch (RuntimeException e) {
            if (old != null) {
                sources.replace(ExternalClientProperties.SOURCE_NAME, old);
            }
            String reason = e.getMessage() == null ? e.getClass().getSimpleName() : stripUserInfo(e.getMessage());
            status.fail(id, reason);
            LOG.warn(new Diagnostic(TopolithCode.TL0011, "the refreshed client settings of external module '" + id
                    + "' were not applied: " + reason, "Fix the topolith.modules." + id + ".remote.* settings; the "
                    + "previous client stays in use.").toString());
            return false;
        }
        status.succeed(id);
        TopologyResolver.Resolution resolution = beanFactory.getBean(TopologyResolver.RESOLUTION_BEAN,
                TopologyResolver.Resolution.class);
        VersionReadiness.startRemote(beanFactory, resolution, Set.of(id));
        return true;
    }

    /** As at startup: the group key (Topolith's or Boot's), else Boot's global key, else the default. */
    private static Duration timeout(Binder binder, String prefix, String key, String fallback) {
        return binder.bind(prefix + key, Duration.class)
                .orElseGet(() -> binder.bind("spring.http.clients." + key, Duration.class)
                        .orElseGet(() -> DurationStyle.detectAndParse(fallback)));
    }

    private static boolean balanced(String configured) {
        return configured.trim().toLowerCase(Locale.ROOT).startsWith(ExternalClientProperties.LB_SCHEME);
    }

    private static String baseUrlKey(String id) {
        return ExternalClientProperties.groupPrefix(id) + "base-url";
    }

    /** What the group's client uses: Cloud LoadBalancer's configurer turns {@code lb://} into {@code http://}. */
    private static String effectiveBase(String configured) {
        URI uri = URI.create(configured.trim());
        if ("lb".equalsIgnoreCase(uri.getScheme())) {
            return UriComponentsBuilder.fromUri(uri).scheme("http").build().toUriString();
        }
        return uri.toString();
    }

    private record SwappableUriBuilderFactory(AtomicReference<UriBuilderFactory> target) implements UriBuilderFactory {
        @Override
        public UriBuilder uriString(String uriTemplate) {
            return target.get().uriString(uriTemplate);
        }

        @Override
        public UriBuilder builder() {
            return target.get().builder();
        }

        @Override
        public URI expand(String uriTemplate, Map<String, ?> uriVariables) {
            return target.get().expand(uriTemplate, uriVariables);
        }

        @Override
        public URI expand(String uriTemplate, Object... uriVariables) {
            return target.get().expand(uriTemplate, uriVariables);
        }
    }

    private record SwappableRequestFactory(AtomicReference<ClientHttpRequestFactory> target)
            implements ClientHttpRequestFactory {
        @Override
        public ClientHttpRequest createRequest(URI uri, HttpMethod httpMethod) throws IOException {
            return target.get().createRequest(uri, httpMethod);
        }
    }

    /** Drops {@code user:password@} from any URL in a message that reaches the endpoint or the log. */
    static String stripUserInfo(String message) {
        return USER_INFO.matcher(message).replaceAll("$1");
    }
}
